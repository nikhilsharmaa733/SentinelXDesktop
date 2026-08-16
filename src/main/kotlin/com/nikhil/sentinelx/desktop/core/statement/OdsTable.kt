package com.nikhil.sentinelx.desktop.core.statement

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/*
 * ⚠️ MIRRORED FILE — see StatementGrid.kt. Byte-identical to the Android copy
 * apart from the package line.
 */

/**
 * ODS (OpenDocument spreadsheet) reader.
 *
 * Here because this machine's owner lives on Linux: a statement opened in
 * LibreOffice and re-saved comes out as `.ods`. Content is one XML file;
 * `office:value` / `office:date-value` attributes carry typed values, so no
 * style resolution is needed. The one trap is `number-columns-repeated`, which
 * ODS uses aggressively — a single empty cell "repeated 16384 times" pads
 * every row; the repeat expansion is capped and trailing blanks are trimmed.
 */
internal object OdsTable {

    fun read(bytes: ByteArray): StatementGrid {
        var content: ByteArray? = null
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == "content.xml") {
                    content = zip.readBytes().also {
                        if (it.size > 128 * 1024 * 1024) throw StatementReadException("Spreadsheet too large.")
                    }
                }
                zip.closeEntry()
            }
        }
        val xml = content?.toString(Charsets.UTF_8)
            ?: throw StatementReadException("Not an OpenDocument spreadsheet (no content.xml).")

        val tables = parseTables(xml)
        val best = tables.maxByOrNull { grid -> grid.count { row -> row.any { it.isNotBlank() } } }
            ?: throw StatementReadException("The spreadsheet holds no rows.")
        val warnings = if (tables.size > 1)
            listOf("The document has ${tables.size} sheets; the fullest was used.")
        else emptyList()
        return StatementGrid(best, "ODS", warnings)
    }

    private fun parseTables(xml: String): List<List<List<String>>> {
        val tables = ArrayList<List<List<String>>>()
        var i = 0
        while (true) {
            val tag = MiniXml.nextTag(xml, i) ?: break
            if (!tag.closing && tag.name == "table" && !tag.selfClosing) {
                val inner = MiniXml.innerOf(xml, "table", tag.end)
                if (inner == null) { i = tag.end; continue }
                tables.add(parseRows(inner.first))
                i = inner.second
            } else {
                i = tag.end
            }
        }
        return tables.filter { it.isNotEmpty() }
    }

    private fun parseRows(tableInner: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var i = 0
        while (true) {
            val tag = MiniXml.nextTag(tableInner, i) ?: break
            if (!tag.closing && tag.name == "table-row") {
                val repeat = (tag.attrs["number-rows-repeated"]?.toIntOrNull() ?: 1).coerceIn(1, 1000)
                if (tag.selfClosing) {
                    repeat(minOf(repeat, 3)) { rows.add(emptyList()) }
                    i = tag.end
                    continue
                }
                val inner = MiniXml.innerOf(tableInner, "table-row", tag.end)
                if (inner == null) { i = tag.end; continue }
                val cells = parseCells(inner.first)
                // A row "repeated" thousands of times is empty filler; a real
                // data row never repeats more than a handful.
                repeat(if (cells.any { it.isNotBlank() }) minOf(repeat, 50) else 1) { rows.add(cells) }
                i = inner.second
            } else {
                i = tag.end
            }
        }
        // Drop the trailing run of fully-blank rows the repeat filler produces.
        while (rows.isNotEmpty() && rows.last().all { it.isBlank() }) rows.removeAt(rows.lastIndex)
        return rows
    }

    private fun parseCells(rowInner: String): List<String> {
        val cells = ArrayList<String>()
        var i = 0
        while (true) {
            val tag = MiniXml.nextTag(rowInner, i) ?: break
            if (!tag.closing && (tag.name == "table-cell" || tag.name == "covered-table-cell")) {
                val repeat = (tag.attrs["number-columns-repeated"]?.toIntOrNull() ?: 1).coerceIn(1, 512)
                val value: String
                if (tag.selfClosing) {
                    value = typedValue(tag.attrs, "")
                    i = tag.end
                } else {
                    val inner = MiniXml.innerOf(rowInner, tag.name, tag.end)
                    if (inner == null) { i = tag.end; continue }
                    value = typedValue(tag.attrs, textOfParagraphs(inner.first))
                    i = inner.second
                }
                // An empty cell repeated hundreds of times is column filler.
                repeat(if (value.isBlank()) minOf(repeat, 3) else repeat) { cells.add(value) }
            } else {
                i = tag.end
            }
        }
        while (cells.isNotEmpty() && cells.last().isBlank()) cells.removeAt(cells.lastIndex)
        return cells
    }

    /** Typed attributes beat display text — they are locale-independent. */
    private fun typedValue(attrs: Map<String, String>, displayText: String): String {
        attrs["date-value"]?.let { return it.substringBefore('T') }
        attrs["value"]?.toDoubleOrNull()?.let { return ExcelDates.renderNumber(it) }
        return displayText.trim()
    }

    /** Cell text lives in `<text:p>` paragraphs; several mean line breaks. */
    private fun textOfParagraphs(cellInner: String): String {
        val parts = ArrayList<String>()
        var i = 0
        while (true) {
            val tag = MiniXml.nextTag(cellInner, i) ?: break
            if (!tag.closing && tag.name == "p") {
                if (tag.selfClosing) { parts.add(""); i = tag.end; continue }
                val inner = MiniXml.innerOf(cellInner, "p", tag.end)
                if (inner == null) { i = tag.end; continue }
                parts.add(MiniXml.textOf(inner.first))
                i = inner.second
            } else {
                i = tag.end
            }
        }
        return parts.joinToString(" ")
    }
}
