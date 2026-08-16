package com.nikhil.sentinelx.desktop.core.statement

/*
 * ⚠️ MIRRORED FILE — see StatementGrid.kt. Byte-identical to the Android copy
 * apart from the package line.
 */

/**
 * Legacy `.xls` reader — an OLE2 compound file holding a BIFF8 (or BIFF5)
 * `Workbook` stream.
 *
 * Only the records a bank statement can contain are understood: SST/LABELSST,
 * LABEL, NUMBER, RK, MULRK, cached FORMULA strings, BOOLERR — plus XF/FORMAT
 * for date detection and DATEMODE for the 1904 epoch. Charts, macros and
 * everything else are skipped by record id, which is what makes a minimal
 * reader safe: an unknown record has a declared length and costs one `skip`.
 *
 * The infamous part is SST continuation: one shared-string table splits across
 * CONTINUE records, and each split point *re-states the character width*.
 * [SstCursor] models exactly that.
 */
internal object XlsTable {

    private const val END_OF_CHAIN = 0xFFFFFFFEL
    private const val FREE = 0xFFFFFFFFL

    fun read(bytes: ByteArray): StatementGrid {
        val workbook = openWorkbookStream(bytes)
        return parseWorkbook(workbook)
    }

    // ── OLE2 compound file ───────────────────────────────────────────────────

    private fun openWorkbookStream(file: ByteArray): ByteArray {
        if (file.size < 512) throw StatementReadException("Truncated .xls file.")
        val header = ByteCursor(file)
        header.skip(30)
        val sectorShift = header.u16()
        val miniShift = header.u16()
        if (sectorShift !in 7..16 || miniShift !in 4..sectorShift)
            throw StatementReadException("Corrupt .xls container.")
        val sectorSize = 1 shl sectorShift
        val miniSize = 1 shl miniShift
        header.pos = 44
        val fatSectorCount = header.u32().toInt()
        val firstDirSector = header.u32()
        header.pos = 56
        val miniCutoff = header.u32()
        val firstMiniFatSector = header.u32()
        val miniFatCount = header.u32().toInt()
        val firstDifatSector = header.u32()
        val difatCount = header.u32().toInt()

        fun sectorAt(n: Long): Int = (512 + n * sectorSize).toInt()

        // DIFAT: 109 entries in the header, then chained DIFAT sectors.
        val fatSectors = ArrayList<Long>(fatSectorCount)
        run {
            val cursor = ByteCursor(file, 76)
            repeat(109) {
                val s = cursor.u32()
                if (s != FREE && s != END_OF_CHAIN) fatSectors.add(s)
            }
            var difat = firstDifatSector
            var hops = 0
            while (difat != END_OF_CHAIN && difat != FREE && hops < difatCount + 4) {
                val c = ByteCursor(file, sectorAt(difat))
                repeat(sectorSize / 4 - 1) {
                    val s = c.u32()
                    if (s != FREE && s != END_OF_CHAIN) fatSectors.add(s)
                }
                difat = c.u32()
                hops++
            }
        }

        // FAT: next-sector table.
        val fat = ArrayList<Long>(fatSectors.size * (sectorSize / 4))
        for (s in fatSectors) {
            val c = ByteCursor(file, sectorAt(s))
            repeat(sectorSize / 4) { fat.add(c.u32()) }
        }

        fun chain(start: Long): List<Long> {
            val sectors = ArrayList<Long>()
            var s = start
            val seen = HashSet<Long>()
            while (s != END_OF_CHAIN && s != FREE && s >= 0) {
                if (!seen.add(s) || sectors.size > 1 shl 20)
                    throw StatementReadException("Corrupt .xls sector chain.")
                sectors.add(s)
                s = fat.getOrNull(s.toInt()) ?: break
            }
            return sectors
        }

        fun readChain(start: Long, size: Int): ByteArray {
            val out = ByteArray(size)
            var written = 0
            for (s in chain(start)) {
                if (written >= size) break
                val offset = sectorAt(s)
                val n = minOf(sectorSize, size - written, file.size - offset)
                if (n <= 0) break
                System.arraycopy(file, offset, out, written, n)
                written += n
            }
            return out
        }

        // Directory entries.
        data class Entry(val name: String, val type: Int, val start: Long, val size: Int)

        val entries = ArrayList<Entry>()
        for (s in chain(firstDirSector)) {
            val base = sectorAt(s)
            var offset = 0
            while (offset + 128 <= sectorSize && base + offset + 128 <= file.size) {
                val c = ByteCursor(file, base + offset)
                val nameBytes = c.bytes(64)
                val nameLen = c.u16().coerceIn(0, 64)
                val type = c.u8()
                val name = if (nameLen >= 2) String(nameBytes, 0, nameLen - 2, Charsets.UTF_16LE) else ""
                c.pos = base + offset + 116
                val start = c.u32()
                val size = c.u32().toInt()
                if (type != 0) entries.add(Entry(name, type, start, size))
                offset += 128
            }
        }

        val root = entries.firstOrNull { it.type == 5 }
            ?: throw StatementReadException("Corrupt .xls directory.")
        val target = entries.firstOrNull { it.type == 2 && it.name.equals("Workbook", true) }
            ?: entries.firstOrNull { it.type == 2 && it.name.equals("Book", true) }
            ?: throw StatementReadException("No Workbook stream — not an Excel file.")

        return if (target.size >= miniCutoff) {
            readChain(target.start, target.size)
        } else {
            // Mini stream: the root entry's chain holds it; the miniFAT chains
            // 64-byte sectors within it.
            val miniStream = readChain(root.start, root.size)
            val miniFatBytes = if (miniFatCount > 0) readChain(firstMiniFatSector, miniFatCount * sectorSize) else ByteArray(0)
            val miniFat = ArrayList<Long>(miniFatBytes.size / 4)
            run {
                val c = ByteCursor(miniFatBytes)
                repeat(miniFatBytes.size / 4) { miniFat.add(c.u32()) }
            }
            val out = ByteArray(target.size)
            var written = 0
            var s = target.start
            val seen = HashSet<Long>()
            while (s != END_OF_CHAIN && s != FREE && s >= 0 && written < target.size) {
                if (!seen.add(s)) throw StatementReadException("Corrupt .xls mini chain.")
                val offset = (s * miniSize).toInt()
                val n = minOf(miniSize, target.size - written, miniStream.size - offset)
                if (n <= 0) break
                System.arraycopy(miniStream, offset, out, written, n)
                written += n
                s = miniFat.getOrNull(s.toInt()) ?: break
            }
            out
        }
    }

    // ── BIFF records ─────────────────────────────────────────────────────────

    private class Record(val id: Int, val offset: Int, val length: Int)

    private fun recordsOf(stream: ByteArray): List<Record> {
        val records = ArrayList<Record>()
        var pos = 0
        while (pos + 4 <= stream.size) {
            val id = (stream[pos].toInt() and 0xFF) or ((stream[pos + 1].toInt() and 0xFF) shl 8)
            val len = (stream[pos + 2].toInt() and 0xFF) or ((stream[pos + 3].toInt() and 0xFF) shl 8)
            if (pos + 4 + len > stream.size) break
            records.add(Record(id, pos + 4, len))
            pos += 4 + len
        }
        return records
    }

    /** Reads across an SST record and its CONTINUEs, honouring re-stated widths. */
    private class SstCursor(private val data: ByteArray, private val segments: List<Record>) {
        private var seg = 0
        private var off = 0

        private fun ensure() {
            while (seg < segments.size && off >= segments[seg].length) {
                seg++
                off = 0
            }
        }

        val atSegmentBoundary: Boolean
            get() {
                ensure()
                return off == 0 && seg > 0
            }

        fun hasMore(): Boolean {
            ensure()
            return seg < segments.size
        }

        fun u8(): Int {
            ensure()
            if (seg >= segments.size) throw StatementReadException("Truncated string table.")
            val b = data[segments[seg].offset + off].toInt() and 0xFF
            off++
            return b
        }

        fun u16(): Int = u8() or (u8() shl 8)
        fun i32(): Int = u16() or (u16() shl 16)
        fun skip(n: Int) = repeat(n) { u8() }

        /** XLUnicodeRichExtendedString. */
        fun readString(): String {
            val cch = u16()
            var grbit = u8()
            var high = grbit and 0x01 != 0
            val ext = grbit and 0x04 != 0
            val rich = grbit and 0x08 != 0
            val runs = if (rich) u16() else 0
            val extBytes = if (ext) i32() else 0

            val sb = StringBuilder(cch)
            var read = 0
            while (read < cch) {
                // A string split across CONTINUE re-states the width byte at the
                // split — the halves can genuinely differ in encoding.
                if (atSegmentBoundary) {
                    grbit = u8()
                    high = grbit and 0x01 != 0
                }
                if (high) sb.append(u16().toChar()) else sb.append(u8().toChar())
                read++
            }
            skip(runs * 4 + extBytes)
            return sb.toString()
        }
    }

    private fun parseWorkbook(stream: ByteArray): StatementGrid {
        val records = recordsOf(stream)
        if (records.isEmpty() || records[0].id != 0x0809)
            throw StatementReadException("Not a BIFF workbook stream.")
        val biffVersion = if (records[0].length >= 2)
            (stream[records[0].offset].toInt() and 0xFF) or ((stream[records[0].offset + 1].toInt() and 0xFF) shl 8)
        else 0x0600
        val biff8 = biffVersion >= 0x0600

        // ── Globals: SST, XF list, FORMAT map, DATEMODE, sheet offsets ───────
        val shared = ArrayList<String>()
        val xfFormats = ArrayList<Int>()
        val formatCodes = HashMap<Int, String>()
        var date1904 = false
        val sheetOffsets = ArrayList<Int>()

        var index = 0
        // The globals substream runs to the first EOF.
        while (index < records.size) {
            val r = records[index]
            when (r.id) {
                0x000A -> { index++; break }
                0x0022 -> if (r.length >= 2) date1904 = (stream[r.offset].toInt() and 0xFF) == 1
                0x0085 -> {
                    val c = ByteCursor(stream, r.offset)
                    sheetOffsets.add(c.i32())
                }
                0x00E0 -> {
                    val c = ByteCursor(stream, r.offset)
                    c.skip(2)
                    xfFormats.add(c.u16())
                }
                0x041E, 0x001E -> {
                    val c = ByteCursor(stream, r.offset)
                    val id = c.u16()
                    formatCodes[id] = if (biff8) readBiff8String(c) else readBiff5String(c, r.offset + r.length)
                }
                0x00FC -> {
                    // SST plus the CONTINUE records that immediately follow it.
                    val segments = ArrayList<Record>()
                    segments.add(r)
                    var j = index + 1
                    while (j < records.size && records[j].id == 0x003C) {
                        segments.add(records[j])
                        j++
                    }
                    val cursor = SstCursor(stream, segments)
                    cursor.skip(4)
                    val unique = cursor.i32()
                    var count = 0
                    while (count < unique && cursor.hasMore()) {
                        shared.add(runCatching { cursor.readString() }.getOrElse { "" })
                        count++
                    }
                }
            }
            index++
        }

        fun isDateXf(ixf: Int): Boolean {
            val ifmt = xfFormats.getOrNull(ixf) ?: return false
            return ExcelDates.isDateFormat(ifmt, formatCodes[ifmt])
        }

        // ── Sheets ───────────────────────────────────────────────────────────
        val sheets = ArrayList<List<List<String>>>()
        val starts = if (sheetOffsets.isNotEmpty()) sheetOffsets
        else listOf(records.getOrNull(index)?.let { it.offset - 4 } ?: return StatementGrid(emptyList(), "XLS"))

        for (start in starts) {
            val cells = HashMap<Long, String>()
            var maxRow = -1
            var maxCol = -1

            fun put(row: Int, col: Int, value: String) {
                if (row < 0 || col < 0 || row > 200_000 || col > 512) return
                cells[row.toLong() shl 16 or col.toLong()] = value
                if (row > maxRow) maxRow = row
                if (col > maxCol) maxCol = col
            }

            var i = records.indexOfFirst { it.offset - 4 == start }
            if (i < 0) continue
            var pendingString: Pair<Int, Int>? = null  // FORMULA awaiting its STRING record
            var depth = 0
            while (i < records.size) {
                val r = records[i]
                when (r.id) {
                    0x0809 -> depth++
                    0x000A -> {
                        depth--
                        if (depth <= 0) break
                    }
                    0x00FD -> {
                        val c = ByteCursor(stream, r.offset)
                        val row = c.u16(); val col = c.u16(); c.skip(2)
                        val isst = c.i32()
                        put(row, col, shared.getOrNull(isst)?.trim() ?: "")
                    }
                    0x0203 -> {
                        val c = ByteCursor(stream, r.offset)
                        val row = c.u16(); val col = c.u16(); val ixf = c.u16()
                        put(row, col, renderNumeric(c.f64(), isDateXf(ixf), date1904))
                    }
                    0x027E -> {
                        val c = ByteCursor(stream, r.offset)
                        val row = c.u16(); val col = c.u16(); val ixf = c.u16()
                        put(row, col, renderNumeric(decodeRk(c.i32()), isDateXf(ixf), date1904))
                    }
                    0x00BD -> {
                        val c = ByteCursor(stream, r.offset)
                        val row = c.u16(); val colFirst = c.u16()
                        val pairs = (r.length - 6) / 6
                        repeat(pairs) { k ->
                            val ixf = c.u16()
                            put(row, colFirst + k, renderNumeric(decodeRk(c.i32()), isDateXf(ixf), date1904))
                        }
                    }
                    0x0204 -> {
                        val c = ByteCursor(stream, r.offset)
                        val row = c.u16(); val col = c.u16(); c.skip(2)
                        val text = if (biff8) readBiff8String(c) else readBiff5String(c, r.offset + r.length)
                        put(row, col, text.trim())
                    }
                    0x00D6 -> {  // RSTRING — BIFF5 label with in-cell formatting
                        val c = ByteCursor(stream, r.offset)
                        val row = c.u16(); val col = c.u16(); c.skip(2)
                        val cch = c.u16()
                        val bytes = c.bytes(minOf(cch, c.remaining))
                        put(row, col, String(bytes, Charsets.ISO_8859_1).trim())
                    }
                    0x0006 -> {
                        val c = ByteCursor(stream, r.offset)
                        val row = c.u16(); val col = c.u16(); val ixf = c.u16()
                        val result = c.bytes(8)
                        if ((result[6].toInt() and 0xFF) == 0xFF && (result[7].toInt() and 0xFF) == 0xFF) {
                            when (result[0].toInt() and 0xFF) {
                                0 -> pendingString = row to col
                                1 -> put(row, col, if (result[2].toInt() != 0) "TRUE" else "FALSE")
                                3 -> put(row, col, "")
                            }
                        } else {
                            var bits = 0L
                            for (b in 7 downTo 0) bits = (bits shl 8) or (result[b].toLong() and 0xFF)
                            put(row, col, renderNumeric(Double.fromBits(bits), isDateXf(ixf), date1904))
                        }
                    }
                    0x0207 -> {
                        pendingString?.let { (row, col) ->
                            val c = ByteCursor(stream, r.offset)
                            val text = if (biff8) readBiff8String(c) else readBiff5String(c, r.offset + r.length)
                            put(row, col, text.trim())
                        }
                        pendingString = null
                    }
                    0x0205 -> {
                        val c = ByteCursor(stream, r.offset)
                        val row = c.u16(); val col = c.u16(); c.skip(2)
                        val value = c.u8(); val isErr = c.u8()
                        put(row, col, if (isErr != 0) "" else if (value != 0) "TRUE" else "FALSE")
                    }
                }
                i++
                if (r.id == 0x000A && depth <= 0) break
            }

            if (maxRow >= 0) {
                sheets.add((0..maxRow).map { row ->
                    (0..maxCol).map { col -> cells[row.toLong() shl 16 or col.toLong()] ?: "" }
                })
            }
        }

        val best = sheets.maxByOrNull { grid -> grid.count { row -> row.any { it.isNotBlank() } } }
            ?: throw StatementReadException("The workbook holds no rows.")
        val warnings = if (sheets.size > 1)
            listOf("The workbook has ${sheets.size} sheets; the fullest was used.")
        else emptyList()
        return StatementGrid(best, if (biff8) "XLS" else "XLS (BIFF5)", warnings)
    }

    private fun renderNumeric(value: Double, isDate: Boolean, date1904: Boolean): String =
        if (isDate) ExcelDates.serialToIso(value, date1904) ?: ExcelDates.renderNumber(value)
        else ExcelDates.renderNumber(value)

    private fun decodeRk(rk: Int): Double {
        val div100 = rk and 0x01 != 0
        val isInt = rk and 0x02 != 0
        var value = if (isInt) (rk shr 2).toDouble()
        else Double.fromBits((rk.toLong() and 0xFFFFFFFCL) shl 32)
        if (div100) value /= 100.0
        return value
    }

    /** XLUnicodeString: u16 cch, u8 grbit, chars. */
    private fun readBiff8String(c: ByteCursor): String {
        if (c.remaining < 3) return ""
        val cch = c.u16()
        val grbit = c.u8()
        val high = grbit and 0x01 != 0
        val rich = grbit and 0x08 != 0
        val ext = grbit and 0x04 != 0
        val runs = if (rich && c.remaining >= 2) c.u16() else 0
        val extBytes = if (ext && c.remaining >= 4) c.i32() else 0
        val sb = StringBuilder(cch)
        repeat(cch) {
            if (high) {
                if (c.remaining < 2) return sb.toString()
                sb.append(c.u16().toChar())
            } else {
                if (c.remaining < 1) return sb.toString()
                sb.append(c.u8().toChar())
            }
        }
        c.skip(minOf(runs * 4 + extBytes, c.remaining))
        return sb.toString()
    }

    /** BIFF5 byte string: u16 cch, cch bytes (codepage — Latin-1 approximation). */
    private fun readBiff5String(c: ByteCursor, limit: Int): String {
        if (c.remaining < 2) return ""
        val cch = c.u16()
        val n = minOf(cch, limit - c.pos, c.remaining)
        if (n <= 0) return ""
        return String(c.bytes(n), Charsets.ISO_8859_1)
    }
}
