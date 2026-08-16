package com.nikhil.sentinelx.desktop.core.statement

/*
 * ⚠️ MIRRORED FILE — see StatementGrid.kt. Byte-identical to the Android copy
 * apart from the package line.
 */

/**
 * HTML `<table>` extraction.
 *
 * This exists mostly for the fake-`.xls` files several banks serve — an HTML
 * page with an Excel extension, which Excel happily opens and every real XLS
 * parser rejects. The dispatcher sniffs content, not extensions, and lands
 * those here. Genuine saved-page statements work too.
 *
 * When the document holds several tables (nav bars, disclaimers), the one with
 * the most rows wins — the statement is always the big one.
 */
internal object HtmlTable {

    fun read(html: String): StatementGrid {
        val tables = extractTables(html)
        val best = tables.maxByOrNull { it.size }
            ?: throw StatementReadException("No table found in the HTML file.")
        val warnings = if (tables.size > 1)
            listOf("The file held ${tables.size} tables; the largest (${best.size} rows) was used.")
        else emptyList()
        return StatementGrid(best, "HTML", warnings)
    }

    private fun extractTables(html: String): List<List<List<String>>> {
        val tables = ArrayList<List<List<String>>>()
        var i = 0
        while (true) {
            val tag = MiniXml.nextTag(html, i) ?: break
            if (!tag.closing && tag.name.equals("table", true) && !tag.selfClosing) {
                val inner = MiniXml.innerOf(html, tag.name, tag.end)
                if (inner != null) {
                    // Nested tables: the inner extraction sees them again on its own
                    // pass, so recursing here would double-count. Take this table's
                    // direct rows; recursion happens naturally via the outer loop
                    // continuing past `inner.second`... except nested tables sit
                    // *inside* that range. Handle them by scanning the inner content
                    // separately and keeping whichever grids result.
                    tables.add(rowsOf(inner.first))
                    tables.addAll(extractTables(inner.first))
                    i = inner.second
                    continue
                }
            }
            i = tag.end
        }
        return tables.filter { it.isNotEmpty() }
    }

    private fun rowsOf(tableInner: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var i = 0
        var depth = 0
        while (true) {
            val tag = MiniXml.nextTag(tableInner, i) ?: break
            when {
                tag.name.equals("table", true) && !tag.closing && !tag.selfClosing -> {
                    depth++; i = tag.end
                }
                tag.name.equals("table", true) && tag.closing -> {
                    depth--; i = tag.end
                }
                depth == 0 && tag.name.equals("tr", true) && !tag.closing && !tag.selfClosing -> {
                    val inner = MiniXml.innerOf(tableInner, tag.name, tag.end)
                    if (inner == null) { i = tag.end; continue }
                    rows.add(cellsOf(inner.first))
                    i = inner.second
                }
                else -> i = tag.end
            }
        }
        return rows
    }

    private fun cellsOf(rowInner: String): List<String> {
        val cells = ArrayList<String>()
        var i = 0
        while (true) {
            val tag = MiniXml.nextTag(rowInner, i) ?: break
            val isCell = tag.name.equals("td", true) || tag.name.equals("th", true)
            if (isCell && !tag.closing) {
                if (tag.selfClosing) {
                    cells.add("")
                    i = tag.end
                    continue
                }
                val inner = MiniXml.innerOf(rowInner, tag.name, tag.end)
                if (inner == null) { i = tag.end; continue }
                cells.add(cleanCell(MiniXml.textOf(inner.first)))
                // colspan pads empty cells so later columns keep their index.
                val span = tag.attrs["colspan"]?.toIntOrNull() ?: 1
                repeat((span - 1).coerceIn(0, 30)) { cells.add("") }
                i = inner.second
            } else {
                i = tag.end
            }
        }
        return cells
    }

    private fun cleanCell(text: String): String =
        text.replace('\u00A0', ' ').replace(Regex("\\s+"), " ").trim()
}
