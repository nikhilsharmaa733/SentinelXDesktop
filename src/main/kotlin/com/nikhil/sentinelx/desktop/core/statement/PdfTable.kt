package com.nikhil.sentinelx.desktop.core.statement

/*
 * ⚠️ MIRRORED FILE — see StatementGrid.kt. Byte-identical to the Android copy
 * apart from the package line.
 */

/**
 * Turns PDF page content into a [StatementGrid].
 *
 * Text shows are collected as positioned runs (the text matrix × CTM gives
 * device x/y), runs on one baseline become a line, gap analysis joins word
 * runs into cells, and the header row — found by the same keyword scoring
 * [StatementParse] uses — anchors the column bands every other row is
 * assigned into. That last step is what survives right-aligned amount columns
 * and wrapped narrations, which naive x-clustering does not.
 */
internal object PdfTable {

    fun read(bytes: ByteArray, password: String?): StatementGrid {
        val doc = PdfDocument.open(bytes, password)
        val pages = doc.pages()
        if (pages.isEmpty()) throw StatementReadException("The PDF has no readable pages.")

        val runs = ArrayList<Run>()
        pages.forEachIndexed { pageIndex, page ->
            runCatching { interpret(page, pageIndex, runs) }
        }
        if (runs.isEmpty())
            throw StatementReadException(
                "No selectable text in this PDF — it is probably a scanned image, which needs OCR."
            )

        val lines = buildLines(runs)
        val grid = buildGrid(lines)
        if (grid.isEmpty()) throw StatementReadException("Could not reconstruct rows from the PDF text.")
        return StatementGrid(grid, "PDF" + if (password.isNullOrEmpty()) "" else " (decrypted)")
    }

    // ── Content interpretation ───────────────────────────────────────────────

    private class Run(val page: Int, val x: Double, val y: Double, val size: Double, val text: String)

    private class Matrix(
        val a: Double, val b: Double, val c: Double,
        val d: Double, val e: Double, val f: Double
    ) {
        fun multiply(m: Matrix): Matrix = Matrix(
            a * m.a + b * m.c, a * m.b + b * m.d,
            c * m.a + d * m.c, c * m.b + d * m.d,
            e * m.a + f * m.c + m.e, e * m.b + f * m.d + m.f
        )

        companion object {
            val IDENTITY = Matrix(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
            fun translate(tx: Double, ty: Double) = Matrix(1.0, 0.0, 0.0, 1.0, tx, ty)
        }
    }

    private fun interpret(page: PdfDocument.Page, pageIndex: Int, out: MutableList<Run>) {
        val lexer = PdfLexer(page.content, 0)
        val operands = ArrayList<Any?>()

        var ctm = Matrix.IDENTITY
        val ctmStack = ArrayDeque<Matrix>()
        var tm = Matrix.IDENTITY
        var tlm = Matrix.IDENTITY
        var leading = 0.0
        var fontSize = 10.0
        var font: PdfDocument.Font? = null

        // Text accumulated since the last positioning op, flushed as one run.
        val buffer = StringBuilder()
        var runOrigin: Matrix? = null
        var runSize = 10.0

        fun flush() {
            val origin = runOrigin
            if (origin != null && buffer.isNotBlank()) {
                val device = origin.multiply(ctm)
                out.add(Run(pageIndex, device.e, device.f, runSize, buffer.toString()))
            }
            buffer.setLength(0)
            runOrigin = null
        }

        fun num(i: Int): Double = when (val v = operands.getOrNull(operands.size - i)) {
            is Long -> v.toDouble(); is Double -> v; else -> 0.0
        }

        fun show(value: Any?) {
            val string = value as? PdfString ?: return
            if (runOrigin == null) {
                runOrigin = tm
                // Effective glyph size ≈ Tf size × the text matrix's horizontal
                // scale (writers often set Tf 1 and put the size in Tm). Clamped:
                // the value only feeds gap heuristics, never rendering.
                runSize = (fontSize * kotlin.math.abs(tm.a).coerceAtLeast(0.05))
                    .coerceIn(4.0, 40.0)
            }
            buffer.append(decode(string.bytes, font))
        }

        fun newline(ty: Double) {
            flush()
            tlm = Matrix.translate(0.0, ty).multiply(tlm)
            tm = tlm
        }

        while (lexer.pos < page.content.size) {
            val token = lexer.next()
            if (token == null) {
                if (lexer.pos >= page.content.size) break else continue
            }
            val isOperand = token is PdfDocument.Name || token is Long || token is Double ||
                token is PdfString || token is List<*> || token is Map<*, *> ||
                token is Boolean || token === PdfLexer.NULL_SENTINEL
            if (isOperand) {
                operands.add(token)
                if (operands.size > 32) operands.removeAt(0)
                continue
            }

            // Anything else was a bare word — a content operator.
            val word = lexer.lastWord ?: continue
            when (word) {
                "q" -> { ctmStack.addLast(ctm); operands.clear() }
                "Q" -> { flush(); ctm = ctmStack.removeLastOrNull() ?: Matrix.IDENTITY; operands.clear() }
                "cm" -> {
                    flush()
                    if (operands.size >= 6) {
                        ctm = Matrix(num(6), num(5), num(4), num(3), num(2), num(1)).multiply(ctm)
                    }
                    operands.clear()
                }
                "BT" -> { flush(); tm = Matrix.IDENTITY; tlm = Matrix.IDENTITY; operands.clear() }
                "ET" -> { flush(); operands.clear() }
                "Td" -> {
                    flush()
                    tlm = Matrix.translate(num(2), num(1)).multiply(tlm)
                    tm = tlm
                    operands.clear()
                }
                "TD" -> {
                    flush()
                    leading = -num(1)
                    tlm = Matrix.translate(num(2), num(1)).multiply(tlm)
                    tm = tlm
                    operands.clear()
                }
                "Tm" -> {
                    flush()
                    if (operands.size >= 6) {
                        tlm = Matrix(num(6), num(5), num(4), num(3), num(2), num(1))
                        tm = tlm
                    }
                    operands.clear()
                }
                "T*" -> { newline(-leading); operands.clear() }
                "TL" -> { leading = num(1); operands.clear() }
                "Tf" -> {
                    fontSize = num(1)
                    val name = operands.getOrNull(operands.size - 2) as? PdfDocument.Name
                    font = name?.let { page.fonts[it.value] }
                    operands.clear()
                }
                "Tj" -> { show(operands.lastOrNull()); operands.clear() }
                "'" -> { newline(-leading); show(operands.lastOrNull()); operands.clear() }
                "\"" -> { newline(-leading); show(operands.lastOrNull()); operands.clear() }
                "TJ" -> {
                    val array = operands.lastOrNull() as? List<*>
                    array?.forEach { element ->
                        when (element) {
                            is PdfString -> show(element)
                            is Long -> if (element < -160) buffer.append(' ')
                            is Double -> if (element < -160) buffer.append(' ')
                        }
                    }
                    operands.clear()
                }
                "BI" -> {
                    // Inline image: skip to EI.
                    operands.clear()
                    val content = page.content
                    var i = lexer.pos
                    while (i < content.size - 2) {
                        if (content[i] == 'E'.code.toByte() && content[i + 1] == 'I'.code.toByte() &&
                            (i + 2 >= content.size || content[i + 2] <= ' '.code.toByte())
                        ) break
                        i++
                    }
                    lexer.pos = (i + 2).coerceAtMost(content.size)
                }
                "Do", "gs", "re", "W", "n", "f", "S", "B", "cs", "CS", "sc", "scn", "SC", "SCN",
                "g", "G", "rg", "RG", "k", "K", "w", "J", "j", "M", "d", "ri", "i",
                "m", "l", "c", "v", "y", "h", "Tc", "Tw", "Tz", "Ts", "Tr", "BDC", "BMC", "EMC", "MP", "DP" ->
                    operands.clear()
            }
        }
        flush()
    }

    private fun decode(bytes: ByteArray, font: PdfDocument.Font?): String {
        if (font == null) return String(bytes, Charsets.ISO_8859_1)
        val sb = StringBuilder()
        if (font.twoByte) {
            var i = 0
            while (i + 1 < bytes.size) {
                val code = ((bytes[i].toInt() and 0xFF) shl 8) or (bytes[i + 1].toInt() and 0xFF)
                val mapped = font.toUnicode?.get(code)
                when {
                    mapped != null -> sb.append(mapped)
                    code in 32..126 -> sb.append(code.toChar())  // some writers use ASCII CIDs
                    else -> sb.append(' ')
                }
                i += 2
            }
        } else {
            for (b in bytes) {
                val code = b.toInt() and 0xFF
                val mapped = font.toUnicode?.get(code)
                if (mapped != null) sb.append(mapped) else sb.append(code.toChar())
            }
        }
        return sb.toString()
    }

    // ── Lines and cells ──────────────────────────────────────────────────────

    private class Cell(val x: Double, val end: Double, val text: String)
    private class Line(val page: Int, val y: Double, val cells: List<Cell>, val runs: List<Run>)

    private fun buildLines(runs: List<Run>): List<Line> {
        val sorted = runs.filter { it.text.isNotBlank() }
            .sortedWith(compareBy({ it.page }, { -it.y }, { it.x }))

        val lines = ArrayList<Line>()
        var current = ArrayList<Run>()

        fun flushLine() {
            if (current.isEmpty()) return
            val line = current.sortedBy { it.x }
            val cells = ArrayList<Cell>()
            var cellStart = line[0].x
            var cellEnd = line[0].x
            val text = StringBuilder()
            for (run in line) {
                val estimate = run.text.length * run.size * 0.52
                val gapLimit = (run.size * 1.1).coerceAtLeast(5.0)
                if (text.isNotEmpty() && run.x - cellEnd > gapLimit) {
                    cells.add(Cell(cellStart, cellEnd, text.toString().trim()))
                    text.setLength(0)
                    cellStart = run.x
                }
                if (text.isNotEmpty() && run.x - cellEnd > run.size * 0.12) text.append(' ')
                text.append(run.text)
                cellEnd = maxOf(cellEnd, run.x + estimate)
            }
            if (text.isNotEmpty()) cells.add(Cell(cellStart, cellEnd, text.toString().trim()))
            lines.add(Line(current[0].page, current[0].y, cells.filter { it.text.isNotEmpty() }, line))
            current = ArrayList()
        }

        for (run in sorted) {
            val head = current.firstOrNull()
            val tolerance = (run.size * 0.4).coerceAtLeast(2.5)
            if (head == null || head.page != run.page || kotlin.math.abs(head.y - run.y) > tolerance) {
                flushLine()
            }
            current.add(run)
        }
        flushLine()
        return lines.filter { it.cells.isNotEmpty() }
    }

    // ── Column assignment ────────────────────────────────────────────────────

    private fun buildGrid(lines: List<Line>): List<List<String>> {
        // The header line anchors the column bands.
        val header = lines.firstOrNull { line ->
            line.cells.size >= 3 && StatementParse.headerRowScore(line.cells.map { it.text }) >= 3
        }

        if (header != null) {
            // With bands known, assign the RAW runs — not the merged cells. A
            // long narration's estimated width can overrun the next column and
            // would fuse "…YES BANK" with "500.00" into one cell; individual
            // word runs are never wide enough to be misassigned by their start.
            val edges = header.cells.map { it.x }
            fun bandOfX(x: Double): Int {
                var band = 0
                for (i in edges.indices) if (x >= edges[i] - 3.0) band = i
                return band
            }
            return lines.map { line ->
                val row = Array(edges.size) { "" }
                for (run in line.runs) {
                    val band = bandOfX(run.x)
                    row[band] = if (row[band].isEmpty()) run.text.trim()
                    else row[band] + " " + run.text.trim()
                }
                row.map { it.trim() }
            }
        }

        // Fallback, no header: cluster the x-starts of the modal-cell-count rows.
        val modal = lines.groupingBy { it.cells.size }.eachCount()
            .filterKeys { it >= 3 }.maxByOrNull { it.value }?.key
        val xs = lines.filter { it.cells.size == modal }
            .flatMap { it.cells.map { c -> c.x } }.sorted()
        if (xs.isEmpty()) return lines.map { line -> line.cells.map { it.text } }
        val clusters = ArrayList<Double>()
        for (x in xs) {
            if (clusters.isEmpty() || x - clusters.last() > 14.0) clusters.add(x)
        }
        if (clusters.isEmpty()) return lines.map { line -> line.cells.map { it.text } }

        fun bandOf(cell: Cell): Int {
            var best = 0
            var bestOverlap = -1.0
            for (i in clusters.indices) {
                val bandStart = if (i == 0) Double.NEGATIVE_INFINITY else clusters[i] - 3.0
                val bandEnd = if (i == clusters.lastIndex) Double.POSITIVE_INFINITY else clusters[i + 1] - 3.0
                val overlap = minOf(cell.end, bandEnd) - maxOf(cell.x, bandStart)
                if (overlap > bestOverlap) {
                    bestOverlap = overlap
                    best = i
                }
            }
            return best
        }

        return lines.map { line ->
            val row = Array(clusters.size) { "" }
            for (cell in line.cells) {
                val band = bandOf(cell)
                row[band] = if (row[band].isEmpty()) cell.text else row[band] + " " + cell.text
            }
            row.toList()
        }
    }
}
