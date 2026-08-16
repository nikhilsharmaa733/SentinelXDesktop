package com.nikhil.sentinelx.desktop.core.statement

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/*
 * ⚠️ MIRRORED FILE — see StatementGrid.kt. Byte-identical to the Android copy
 * apart from the package line.
 */

/**
 * XLSX reader — a zip of SpreadsheetML, walked with [MiniXml].
 *
 * Understands exactly the subset bank exports use: shared strings (plain and
 * rich-run), inline strings, numbers, cached formula strings, and date cells,
 * which are resolved through styles.xml so the grid carries `2026-08-01`
 * rather than the serial `46215`. Multiple sheets: the one with the most
 * non-empty rows is the statement (sheet 1 is sometimes a cover page).
 */
internal object XlsxTable {

    fun read(bytes: ByteArray): StatementGrid {
        val entries = HashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) {
                    val name = entry.name.removePrefix("/")
                    // Cap per entry: a statement sheet is a few MB at most; a
                    // zip-bomb should die here, not in the heap.
                    if (name.startsWith("xl/") || name == "[Content_Types].xml") {
                        entries[name] = zip.readBytes().also {
                            if (it.size > 64 * 1024 * 1024) throw StatementReadException("Spreadsheet entry too large.")
                        }
                    }
                }
                zip.closeEntry()
            }
        }
        if (entries.keys.none { it.startsWith("xl/worksheets/") })
            throw StatementReadException("Not an Excel workbook (no worksheets inside).")

        val date1904 = entries["xl/workbook.xml"]?.toString(Charsets.UTF_8)
            ?.let { wb ->
                Regex("date1904\\s*=\\s*\"(1|true)\"", RegexOption.IGNORE_CASE).containsMatchIn(wb)
            } ?: false

        val shared = entries["xl/sharedStrings.xml"]?.toString(Charsets.UTF_8)
            ?.let(::parseSharedStrings) ?: emptyList()

        val dateStyles = entries["xl/styles.xml"]?.toString(Charsets.UTF_8)
            ?.let(::parseDateStyles) ?: emptySet()

        val sheets = entries.filterKeys {
            it.startsWith("xl/worksheets/") && it.endsWith(".xml") && "/_rels/" !in it
        }
        val parsed = sheets.entries
            .sortedBy { it.key }
            .map { (_, data) -> parseSheet(data.toString(Charsets.UTF_8), shared, dateStyles, date1904) }
        val best = parsed.maxByOrNull { grid -> grid.count { row -> row.any { it.isNotBlank() } } }
            ?: throw StatementReadException("The workbook holds no rows.")

        val warnings = if (parsed.size > 1)
            listOf("The workbook has ${parsed.size} sheets; the fullest (${best.size} rows) was used.")
        else emptyList()
        return StatementGrid(best, "XLSX", warnings)
    }

    // ── Shared strings ───────────────────────────────────────────────────────

    private fun parseSharedStrings(xml: String): List<String> {
        val strings = ArrayList<String>()
        var i = 0
        while (true) {
            val tag = MiniXml.nextTag(xml, i) ?: break
            if (!tag.closing && tag.name == "si") {
                if (tag.selfClosing) {
                    strings.add("")
                    i = tag.end
                    continue
                }
                val inner = MiniXml.innerOf(xml, "si", tag.end)
                if (inner == null) { i = tag.end; continue }
                strings.add(collectT(inner.first))
                i = inner.second
            } else {
                i = tag.end
            }
        }
        return strings
    }

    /** Concatenates every `<t>` in an `<si>` — rich-text runs split one string across several. */
    private fun collectT(inner: String): String {
        val sb = StringBuilder()
        var i = 0
        while (true) {
            val tag = MiniXml.nextTag(inner, i) ?: break
            if (!tag.closing && tag.name == "t") {
                if (tag.selfClosing) { i = tag.end; continue }
                val body = MiniXml.innerOf(inner, "t", tag.end)
                if (body == null) { i = tag.end; continue }
                sb.append(MiniXml.decode(body.first))
                i = body.second
            } else {
                i = tag.end
            }
        }
        return sb.toString()
    }

    // ── Styles: which cellXf indices are dates ───────────────────────────────

    private fun parseDateStyles(xml: String): Set<Int> {
        // Custom format codes by id.
        val custom = HashMap<Int, String>()
        var i = 0
        while (true) {
            val tag = MiniXml.nextTag(xml, i) ?: break
            if (!tag.closing && tag.name == "numFmt") {
                val id = tag.attrs["numFmtId"]?.toIntOrNull()
                val code = tag.attrs["formatCode"]
                if (id != null && code != null) custom[id] = code
            }
            i = tag.end
        }

        // The <cellXfs> list, in order — a cell's s="n" indexes into it.
        val dateXfs = HashSet<Int>()
        val cellXfs = findSection(xml, "cellXfs") ?: return emptySet()
        var index = 0
        i = 0
        while (true) {
            val tag = MiniXml.nextTag(cellXfs, i) ?: break
            if (!tag.closing && tag.name == "xf") {
                val fmt = tag.attrs["numFmtId"]?.toIntOrNull() ?: 0
                if (ExcelDates.isDateFormat(fmt, custom[fmt])) dateXfs.add(index)
                index++
                i = if (tag.selfClosing) tag.end else (MiniXml.innerOf(cellXfs, "xf", tag.end)?.second ?: tag.end)
            } else {
                i = tag.end
            }
        }
        return dateXfs
    }

    private fun findSection(xml: String, name: String): String? {
        var i = 0
        while (true) {
            val tag = MiniXml.nextTag(xml, i) ?: return null
            if (!tag.closing && tag.name == name && !tag.selfClosing)
                return MiniXml.innerOf(xml, name, tag.end)?.first
            i = tag.end
        }
    }

    // ── Sheet ────────────────────────────────────────────────────────────────

    private fun parseSheet(
        xml: String,
        shared: List<String>,
        dateXfs: Set<Int>,
        date1904: Boolean
    ): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var i = 0
        while (true) {
            val tag = MiniXml.nextTag(xml, i) ?: break
            if (!tag.closing && tag.name == "row") {
                if (tag.selfClosing) { rows.add(emptyList()); i = tag.end; continue }
                val inner = MiniXml.innerOf(xml, "row", tag.end)
                if (inner == null) { i = tag.end; continue }
                rows.add(parseRow(inner.first, shared, dateXfs, date1904))
                i = inner.second
            } else {
                i = tag.end
            }
        }
        return rows
    }

    private fun parseRow(
        inner: String,
        shared: List<String>,
        dateXfs: Set<Int>,
        date1904: Boolean
    ): List<String> {
        val cells = ArrayList<String>()
        var nextCol = 0
        var i = 0
        while (true) {
            val tag = MiniXml.nextTag(inner, i) ?: break
            if (!tag.closing && tag.name == "c") {
                val col = tag.attrs["r"]?.let(::columnIndex) ?: nextCol
                while (cells.size < col) cells.add("")
                val type = tag.attrs["t"] ?: "n"
                val style = tag.attrs["s"]?.toIntOrNull() ?: -1

                val body: String?
                if (tag.selfClosing) {
                    body = null
                    i = tag.end
                } else {
                    val cellInner = MiniXml.innerOf(inner, "c", tag.end)
                    if (cellInner == null) { i = tag.end; continue }
                    body = cellInner.first
                    i = cellInner.second
                }

                val value = if (body == null) "" else cellValue(body, type, style, shared, dateXfs, date1904)
                while (cells.size <= col) cells.add("")
                cells[col] = value
                nextCol = col + 1
            } else {
                i = tag.end
            }
        }
        return cells
    }

    private fun cellValue(
        body: String,
        type: String,
        style: Int,
        shared: List<String>,
        dateXfs: Set<Int>,
        date1904: Boolean
    ): String {
        // Inline string: <is><t>…</t></is>
        if (type == "inlineStr") {
            val isBody = MiniXml.nextTag(body, 0)
                ?.takeIf { it.name == "is" && !it.closing && !it.selfClosing }
                ?.let { MiniXml.innerOf(body, "is", it.end)?.first }
            return collectT(isBody ?: body).trim()
        }

        val v = findSection(body, "v")?.let { MiniXml.decode(it).trim() } ?: return ""
        return when (type) {
            "s" -> v.toIntOrNull()?.let { shared.getOrNull(it) }?.trim() ?: ""
            "str" -> v            // cached formula result, already text
            "b" -> if (v == "1") "TRUE" else "FALSE"
            "e" -> ""             // error cell — blank beats "#N/A" in a narration
            "d" -> v.substringBefore('T')  // ISO date type (rare)
            else -> {
                val number = v.toDoubleOrNull() ?: return v
                if (style in dateXfs) ExcelDates.serialToIso(number, date1904) ?: ExcelDates.renderNumber(number)
                else ExcelDates.renderNumber(number)
            }
        }
    }

    /** `"BC12"` → 54. Letters only matter; digits are the row. */
    private fun columnIndex(ref: String): Int? {
        var col = 0
        var seen = false
        for (ch in ref) {
            if (ch.isLetter()) {
                col = col * 26 + (ch.uppercaseChar() - 'A' + 1)
                seen = true
            } else break
        }
        return if (seen) col - 1 else null
    }
}
