package com.nikhil.sentinelx.desktop.core.statement

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The statement engine, format by format. Every fixture is built
 * programmatically inside the test — a crafted zip, a crafted OLE2 container,
 * a crafted PDF — so the suite proves the readers against the *format*, not
 * against one bank's sample file that happens to be lying around.
 */
class StatementEngineTest {

    // The user's real-world example, verbatim.
    private val exampleNarration =
        "UPI/P2M/083743276468/RATHOD LAXMAN BHOJU /NO REM/YES BANK LIMITED YBS"

    // ── Narration splitting ──────────────────────────────────────────────────

    @Test
    fun `example narration splits into all six fields`() {
        val fields = StatementParse.splitNarration(exampleNarration)
        assertEquals("UPI", fields.mode)
        assertEquals("P2M", fields.channel)
        assertEquals("083743276468", fields.reference)
        assertEquals("RATHOD LAXMAN BHOJU", fields.party)
        assertEquals("NO REM", fields.remark)
        assertEquals("YES BANK LIMITED YBS", fields.bankName)
    }

    @Test
    fun `p2m with no keyword category lands as merchant`() {
        val fields = StatementParse.splitNarration(exampleNarration)
        assertEquals("Merchant", StatementParse.categorize(exampleNarration, fields, isCredit = false))
    }

    @Test
    fun `neft narration without slashes still yields mode and reference`() {
        val fields = StatementParse.splitNarration("NEFT HDFCN52026080112345678 RENT AUGUST")
        assertEquals("NEFT", fields.mode)
        assertEquals("HDFCN52026080112345678", fields.reference)
    }

    @Test
    fun `swiggy categorises as food`() {
        val narration = "UPI/P2M/519912345678/SWIGGY LIMITED/PAYMENT/AXIS BANK"
        val fields = StatementParse.splitNarration(narration)
        assertEquals("Food", StatementParse.categorize(narration, fields, isCredit = false))
    }

    // ── Dates ────────────────────────────────────────────────────────────────

    @Test
    fun `every bank date shape parses`() {
        val expected = LocalDate.of(2026, 8, 1)
        val shapes = listOf(
            "01/08/2026", "01-08-2026", "01.08.2026", "2026-08-01",
            "01 Aug 2026", "01-Aug-26", "1 Aug 2026", "Aug 1, 2026",
            "01 Aug, 2026", "01/08/2026 14:22:31", "01/08/26"
        )
        for (shape in shapes) {
            assertEquals(expected, StatementParse.parseDate(shape, dayFirst = true), "failed on: $shape")
        }
    }

    @Test
    fun `unambiguous days override the dayFirst flag`() {
        assertEquals(LocalDate.of(2026, 8, 13), StatementParse.parseDate("13/08/2026", dayFirst = false))
        assertEquals(LocalDate.of(2026, 8, 13), StatementParse.parseDate("08/13/2026", dayFirst = true))
    }

    @Test
    fun `garbage is not a date`() {
        assertNull(StatementParse.parseDate("TOTAL", dayFirst = true))
        assertNull(StatementParse.parseDate("", dayFirst = true))
        assertNull(StatementParse.parseDate("99/99/9999", dayFirst = true))
    }

    // ── Amounts ──────────────────────────────────────────────────────────────

    @Test
    fun `indian amount shapes parse`() {
        assertEquals(123456.78, StatementParse.parseAmount("1,23,456.78")!!.value)
        assertEquals(2500.0, StatementParse.parseAmount("₹ 2,500.00 Cr")!!.value)
        assertEquals('C', StatementParse.parseAmount("₹ 2,500.00 Cr")!!.hint)
        assertEquals('D', StatementParse.parseAmount("300 Dr")!!.hint)
        assertEquals(-500.0, StatementParse.parseAmount("(500)")!!.value)
        assertEquals(-500.0, StatementParse.parseAmount("500.00-")!!.value)
        assertEquals(1000.0, StatementParse.parseAmount("Rs. 1,000")!!.value)
    }

    @Test
    fun `placeholders are not amounts`() {
        assertNull(StatementParse.parseAmount("-"))
        assertNull(StatementParse.parseAmount("NA"))
        assertNull(StatementParse.parseAmount(""))
        assertNull(StatementParse.parseAmount("Narration text"))
    }

    // ── CSV end to end ───────────────────────────────────────────────────────

    private fun csvFixture(): String = """
        Account Statement,,,,
        Account No: XXXXXX4321,,,,
        ,,,,
        Date,Narration,Debit,Credit,Balance
        01/08/2026,"$exampleNarration",500.00,,"9,500.00"
        02/08/2026,"SALARY, AUGUST",,"25,000.00","34,500.00"
        03/08/2026,ATM WDL 123456,2000.00,,"32,500.00"
        ,,,,
        TOTAL,,2500.00,25000.00,
    """.trimIndent()

    @Test
    fun `csv statement parses end to end`() {
        val grid = StatementReader.read(csvFixture().toByteArray(), "statement.csv")
        assertEquals("CSV", grid.format)

        val mapping = StatementParse.detectMapping(grid)
        assertEquals(3, mapping.headerRow)
        assertEquals(StatementParse.Col.DATE, mapping.columns[0])
        assertEquals(StatementParse.Col.NARRATION, mapping.columns[1])
        assertEquals(StatementParse.Col.DEBIT, mapping.columns[2])
        assertEquals(StatementParse.Col.CREDIT, mapping.columns[3])
        assertEquals(StatementParse.Col.BALANCE, mapping.columns[4])

        val outcome = StatementParse.parse(grid, mapping, StatementParse.Extraction())
        assertEquals(3, outcome.rows.size)
        assertEquals("A/c ••4321", outcome.suggestedBook)

        val first = outcome.rows[0]
        assertEquals("2026-08-01", first.dateIso)
        assertEquals(500.0, first.amount)
        assertEquals(false, first.isCredit)
        assertEquals(9500.0, first.balance)
        assertEquals("RATHOD LAXMAN BHOJU", first.party)
        assertEquals("UPI", first.mode)

        val salary = outcome.rows[1]
        assertTrue(salary.isCredit)
        assertEquals(25000.0, salary.amount)
        assertEquals("Salary", salary.category)
        // The quoted comma survived the CSV parse.
        assertEquals("SALARY, AUGUST", salary.narration)

        // Balance chain: 9500 + 25000 = 34500 ✓, 34500 − 2000 = 32500 ✓.
        assertTrue(outcome.rows.drop(1).all { it.balanceAgrees == true })

        val cash = outcome.rows[2]
        assertEquals("Cash", cash.category)
    }

    @Test
    fun `balance column resolves direction when nothing else says`() {
        val csv = """
            Date,Particulars,Amount,Balance
            01/08/2026,OPENING BALANCE B/F,,1000.00
            01/08/2026,FIRST,200.00,800.00
            02/08/2026,SECOND,300.00,1100.00
        """.trimIndent()
        val grid = StatementReader.read(csv.toByteArray(), "s.csv")
        val outcome = StatementParse.parse(grid, StatementParse.detectMapping(grid), StatementParse.Extraction())
        assertEquals(1000.0, outcome.openingBalance)
        assertEquals(2, outcome.rows.size)
        assertEquals(false, outcome.rows[0].isCredit)  // 1000 → 800
        assertEquals(true, outcome.rows[1].isCredit)   // 800 → 1100
        assertTrue(outcome.rows.all { it.balanceAgrees == true })
    }

    @Test
    fun `identical rows without balance get deterministic suffixes`() {
        val csv = """
            Date,Narration,Debit,Credit
            01/08/2026,SAME PAYMENT,100.00,
            01/08/2026,SAME PAYMENT,100.00,
        """.trimIndent()
        val grid = StatementReader.read(csv.toByteArray(), "s.csv")
        val outcome = StatementParse.parse(grid, StatementParse.detectMapping(grid), StatementParse.Extraction())
        assertEquals(2, outcome.rows.size)
        val (a, b) = outcome.rows
        assertNotEquals(a.fingerprint, b.fingerprint)
        assertTrue(b.fingerprint.endsWith("#2"))
        assertEquals(a.fingerprint, b.fingerprint.removeSuffix("#2"))
    }

    @Test
    fun `continuation lines fold into the previous narration`() {
        val csv = """
            Date,Narration,Debit,Credit,Balance
            01/08/2026,UPI/P2M/083743276468/RATHOD,500.00,,9500.00
            ,LAXMAN BHOJU/NO REM,,,
        """.trimIndent()
        val grid = StatementReader.read(csv.toByteArray(), "s.csv")
        val outcome = StatementParse.parse(grid, StatementParse.detectMapping(grid), StatementParse.Extraction())
        assertEquals(1, outcome.rows.size)
        assertTrue(outcome.rows[0].narration.contains("LAXMAN BHOJU"))
    }

    // ── XLSX ─────────────────────────────────────────────────────────────────

    private fun xlsxFixture(): ByteArray {
        val serial = LocalDate.of(2026, 8, 1).toEpochDay() - LocalDate.of(1899, 12, 30).toEpochDay()
        fun zipOf(vararg entries: Pair<String, String>): ByteArray {
            val out = ByteArrayOutputStream()
            ZipOutputStream(out).use { zip ->
                for ((name, content) in entries) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(content.toByteArray())
                    zip.closeEntry()
                }
            }
            return out.toByteArray()
        }
        return zipOf(
            "xl/workbook.xml" to """<workbook><sheets><sheet name="S" sheetId="1"/></sheets></workbook>""",
            "xl/styles.xml" to """
                <styleSheet>
                  <numFmts count="1"><numFmt numFmtId="164" formatCode="dd/mm/yyyy"/></numFmts>
                  <cellXfs count="2">
                    <xf numFmtId="0" applyNumberFormat="0"/>
                    <xf numFmtId="164" applyNumberFormat="1"/>
                  </cellXfs>
                </styleSheet>
            """.trimIndent(),
            "xl/sharedStrings.xml" to """
                <sst count="6" uniqueCount="6">
                  <si><t>Date</t></si>
                  <si><t>Narration</t></si>
                  <si><t>Debit</t></si>
                  <si><t>Credit</t></si>
                  <si><t>Balance</t></si>
                  <si><r><t>UPI/P2M/083743276468/RATHOD LAXMAN BHOJU /NO REM/</t></r><r><t>YES BANK LIMITED YBS</t></r></si>
                </sst>
            """.trimIndent(),
            "xl/worksheets/sheet1.xml" to """
                <worksheet><sheetData>
                  <row r="1">
                    <c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c>
                    <c r="C1" t="s"><v>2</v></c><c r="D1" t="s"><v>3</v></c><c r="E1" t="s"><v>4</v></c>
                  </row>
                  <row r="2">
                    <c r="A2" s="1"><v>$serial</v></c>
                    <c r="B2" t="s"><v>5</v></c>
                    <c r="C2"><v>500.5</v></c>
                    <c r="E2"><v>9499.5</v></c>
                  </row>
                </sheetData></worksheet>
            """.trimIndent()
        )
    }

    @Test
    fun `xlsx reads dates through styles and joins rich-text shared strings`() {
        val grid = StatementReader.read(xlsxFixture(), "statement.xlsx")
        assertEquals("XLSX", grid.format)
        assertEquals("2026-08-01", grid.rows[1][0])
        assertEquals(exampleNarration, grid.rows[1][1])
        assertEquals("500.5", grid.rows[1][2])

        val outcome = StatementParse.parse(grid, StatementParse.detectMapping(grid), StatementParse.Extraction())
        assertEquals(1, outcome.rows.size)
        assertEquals(500.5, outcome.rows[0].amount)
        assertEquals("RATHOD LAXMAN BHOJU", outcome.rows[0].party)
    }

    // ── ODS ──────────────────────────────────────────────────────────────────

    @Test
    fun `ods reads typed values`() {
        val content = """
            <document-content xmlns:table="t" xmlns:office="o" xmlns:text="x">
              <office:body><office:spreadsheet>
                <table:table table:name="S">
                  <table:table-row>
                    <table:table-cell><text:p>Date</text:p></table:table-cell>
                    <table:table-cell><text:p>Narration</text:p></table:table-cell>
                    <table:table-cell><text:p>Debit</text:p></table:table-cell>
                    <table:table-cell><text:p>Credit</text:p></table:table-cell>
                  </table:table-row>
                  <table:table-row>
                    <table:table-cell office:date-value="2026-08-01"><text:p>01/08/26</text:p></table:table-cell>
                    <table:table-cell><text:p>ATM WDL</text:p></table:table-cell>
                    <table:table-cell office:value="750"><text:p>750.00</text:p></table:table-cell>
                    <table:table-cell/>
                  </table:table-row>
                </table:table>
              </office:spreadsheet></office:body>
            </document-content>
        """.trimIndent()
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("mimetype"))
            zip.write("application/vnd.oasis.opendocument.spreadsheet".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("content.xml"))
            zip.write(content.toByteArray())
            zip.closeEntry()
        }
        val grid = StatementReader.read(out.toByteArray(), "statement.ods")
        assertEquals("ODS", grid.format)
        assertEquals("2026-08-01", grid.rows[1][0])
        assertEquals("750", grid.rows[1][2])

        val outcome = StatementParse.parse(grid, StatementParse.detectMapping(grid), StatementParse.Extraction())
        assertEquals(1, outcome.rows.size)
        assertEquals(750.0, outcome.rows[0].amount)
    }

    // ── HTML posing as XLS ───────────────────────────────────────────────────

    @Test
    fun `fake xls that is really html routes to the html reader`() {
        val html = """
            <html><body>
            <p>Your statement</p>
            <table>
              <tr><th>Date</th><th>Narration</th><th>Withdrawal Amt</th><th>Deposit Amt</th><th>Closing Balance</th></tr>
              <tr><td>01/08/2026</td><td>$exampleNarration</td><td>500.00</td><td>&nbsp;</td><td>9,500.00</td></tr>
            </table>
            </body></html>
        """.trimIndent()
        val grid = StatementReader.read(html.toByteArray(), "statement.xls")
        assertEquals("HTML", grid.format)

        val mapping = StatementParse.detectMapping(grid)
        assertEquals(StatementParse.Col.DEBIT, mapping.columns[2])
        assertEquals(StatementParse.Col.CREDIT, mapping.columns[3])
        assertEquals(StatementParse.Col.BALANCE, mapping.columns[4])

        val outcome = StatementParse.parse(grid, mapping, StatementParse.Extraction())
        assertEquals(1, outcome.rows.size)
        assertEquals("RATHOD LAXMAN BHOJU", outcome.rows[0].party)
    }

    // ── Real XLS (OLE2 + BIFF8) ──────────────────────────────────────────────

    private class BiffWriter {
        val out = ByteArrayOutputStream()
        fun record(id: Int, payload: ByteArray) {
            out.write(id and 0xFF); out.write(id shr 8)
            out.write(payload.size and 0xFF); out.write(payload.size shr 8)
            out.write(payload)
        }
        fun bytes(): ByteArray = out.toByteArray()
    }

    private fun u16(v: Int) = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
    private fun u32(v: Int) = byteArrayOf(
        (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte()
    )
    private fun f64(v: Double): ByteArray {
        val bits = v.toRawBits()
        return ByteArray(8) { ((bits shr (it * 8)) and 0xFF).toByte() }
    }

    private fun biff8String(text: String): ByteArray =
        u16(text.length) + byteArrayOf(0) + text.toByteArray(Charsets.ISO_8859_1)

    private fun xlsFixture(): ByteArray {
        val serial = (LocalDate.of(2026, 8, 1).toEpochDay() - LocalDate.of(1899, 12, 30).toEpochDay()).toDouble()

        // Sheet substream, built first so the globals can point at it.
        val sheet = BiffWriter()
        sheet.record(0x0809, u16(0x0600) + u16(0x0010) + ByteArray(12))
        // Header row via LABELSST (strings 0..3).
        for (c in 0..3) sheet.record(0x00FD, u16(0) + u16(c) + u16(0) + u32(c))
        // Data: date NUMBER with date XF(1), narration LABELSST — string index 5,
        // because index 4 is the "HELLO"+"WORLD" CONTINUE-split probe. RK ÷100.
        sheet.record(0x0203, u16(1) + u16(0) + u16(1) + f64(serial))
        sheet.record(0x00FD, u16(1) + u16(1) + u16(0) + u32(5))
        val rk = (50025 shl 2) or 0x03  // 500.25 as int÷100 RK
        sheet.record(0x027E, u16(1) + u16(2) + u16(0) + u32(rk))
        sheet.record(0x000A, ByteArray(0))
        val sheetBytes = sheet.bytes()

        // Globals — the BOUNDSHEET needs the sheet's absolute offset, so build
        // the fixed-size prefix once, measure, then emit for real.
        fun globals(sheetOffset: Int): ByteArray {
            val g = BiffWriter()
            g.record(0x0809, u16(0x0600) + u16(0x0005) + ByteArray(12))
            g.record(0x0022, u16(0))
            g.record(0x041E, u16(164) + biff8String("dd/mm/yyyy"))
            g.record(0x00E0, u16(0) + u16(0) + ByteArray(16))    // XF 0 — general
            g.record(0x00E0, u16(0) + u16(164) + ByteArray(16))  // XF 1 — date
            // SST split across a CONTINUE at a character boundary: "HELLO"+"WORLD"
            // plus the four header strings and the narration.
            val sst = ByteArrayOutputStream()
            sst.write(u32(6)); sst.write(u32(6))
            for (s in listOf("Date", "Narration", "Debit", "Credit")) {
                sst.write(biff8String(s))
            }
            sst.write(u16(10)); sst.write(0)  // cch=10, grbit=0, then only "HELLO"
            sst.write("HELLO".toByteArray(Charsets.ISO_8859_1))
            g.record(0x00FC, sst.toByteArray())
            val cont = ByteArrayOutputStream()
            cont.write(0)  // re-stated grbit for the remaining characters
            cont.write("WORLD".toByteArray(Charsets.ISO_8859_1))
            cont.write(biff8String(exampleNarration))
            g.record(0x003C, cont.toByteArray())
            g.record(0x0085, u32(sheetOffset) + u16(0) + byteArrayOf(2, 0) + "S1".toByteArray(Charsets.ISO_8859_1))
            g.record(0x000A, ByteArray(0))
            return g.bytes()
        }
        val globalsSize = globals(0).size
        val stream = globals(globalsSize) + sheetBytes
        // Keep the stream out of the mini-stream: pad past the 4096 cutoff.
        val padded = stream + ByteArray((4096 - stream.size % 4096).coerceAtLeast(4096 - stream.size).coerceAtLeast(0))
        return ole2(padded.copyOf(maxOf(padded.size, 4096)))
    }

    /** Wraps [stream] as a minimal OLE2 compound file with one Workbook stream. */
    private fun ole2(stream: ByteArray): ByteArray {
        val sectorSize = 512
        val streamSectors = (stream.size + sectorSize - 1) / sectorSize
        val fatEntries = 2 + streamSectors  // FAT itself + directory + stream
        require(fatEntries <= sectorSize / 4) { "fixture too large for one FAT sector" }

        val out = ByteArrayOutputStream()
        // Header.
        out.write(byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte()))
        out.write(ByteArray(16))          // clsid
        out.write(u16(0x003E)); out.write(u16(0x0003))  // minor/major
        out.write(u16(0xFFFE))            // little endian
        out.write(u16(9)); out.write(u16(6))            // sector/mini shifts
        out.write(ByteArray(6))
        out.write(u32(0))                 // dir sector count (v3: 0)
        out.write(u32(1))                 // FAT sector count
        out.write(u32(1))                 // first directory sector
        out.write(u32(0))
        out.write(u32(4096))              // mini cutoff
        out.write(u32(-2))                // first miniFAT — end of chain
        out.write(u32(0))
        out.write(u32(-2))                // first DIFAT — end of chain
        out.write(u32(0))
        out.write(u32(0))                 // DIFAT[0] → FAT lives in sector 0
        repeat(108) { out.write(u32(-1)) }

        // Sector 0 — the FAT.
        val fat = ByteArrayOutputStream()
        fat.write(u32(-3))  // sector 0: FATSECT
        fat.write(u32(-2))  // sector 1: directory, single sector
        for (s in 0 until streamSectors) {
            fat.write(u32(if (s == streamSectors - 1) -2 else 2 + s + 1))
        }
        repeat(sectorSize / 4 - 2 - streamSectors) { fat.write(u32(-1)) }
        out.write(fat.toByteArray())

        // Sector 1 — the directory: Root + Workbook.
        fun dirEntry(name: String, type: Int, start: Int, size: Int): ByteArray {
            val e = ByteArrayOutputStream()
            val utf16 = name.toByteArray(Charsets.UTF_16LE)
            e.write(utf16); e.write(ByteArray(64 - utf16.size))
            e.write(u16(utf16.size + 2))
            e.write(type); e.write(1)     // colour: black
            e.write(u32(-1)); e.write(u32(-1)); e.write(u32(-1))  // siblings/child
            e.write(ByteArray(16))        // clsid
            e.write(u32(0))               // state
            e.write(ByteArray(16))        // times
            e.write(u32(start))
            e.write(u32(size)); e.write(u32(0))
            return e.toByteArray()
        }
        out.write(dirEntry("Root Entry", 5, -2, 0))
        out.write(dirEntry("Workbook", 2, 2, stream.size))
        out.write(ByteArray(sectorSize - 2 * 128))

        // Sectors 2… — the stream.
        out.write(stream)
        val tail = streamSectors * sectorSize - stream.size
        if (tail > 0) out.write(ByteArray(tail))
        return out.toByteArray()
    }

    @Test
    fun `real biff8 xls reads cells, dates, rk amounts and split sst`() {
        val grid = StatementReader.read(xlsFixture(), "statement.xls")
        assertEquals("XLS", grid.format)
        assertEquals("Date", grid.rows[0][0])
        assertEquals("2026-08-01", grid.rows[1][0])
        assertEquals(exampleNarration, grid.rows[1][1])
        assertEquals("500.25", grid.rows[1][2])

        val outcome = StatementParse.parse(grid, StatementParse.detectMapping(grid), StatementParse.Extraction())
        assertEquals(1, outcome.rows.size)
        assertEquals(500.25, outcome.rows[0].amount)
        assertEquals(false, outcome.rows[0].isCredit)
        assertEquals("RATHOD LAXMAN BHOJU", outcome.rows[0].party)
    }

    // ── PDF ──────────────────────────────────────────────────────────────────

    /**
     * A one-page PDF whose text is laid out in absolute columns: header line +
     * two data lines. Uncompressed streams and a classic xref, offsets exact.
     */
    private fun pdfFixture(encryptWith: String? = null): ByteArray {
        val content = buildString {
            fun cell(x: Int, y: Int, text: String) {
                append("BT /F1 10 Tf 1 0 0 1 $x $y Tm (${text.replace("(", "\\(").replace(")", "\\)")}) Tj ET\n")
            }
            cell(40, 700, "Date"); cell(120, 700, "Narration"); cell(360, 700, "Debit")
            cell(430, 700, "Credit"); cell(500, 700, "Balance")
            cell(40, 680, "01/08/2026"); cell(120, 680, "UPI/P2M/083743276468/RATHOD LAXMAN BHOJU /NO REM/YES BANK")
            cell(360, 680, "500.00"); cell(500, 680, "9,500.00")
            cell(40, 660, "02/08/2026"); cell(120, 660, "SALARY AUGUST")
            cell(430, 660, "25,000.00"); cell(500, 660, "34,500.00")
        }

        var contentBytes = content.toByteArray(Charsets.ISO_8859_1)
        var encryptDict = ""
        var idEntry = ""

        if (encryptWith != null) {
            val md5 = MessageDigest.getInstance("MD5")
            val pad = byteArrayOf(
                0x28, 0xBF.toByte(), 0x4E, 0x5E, 0x4E, 0x75, 0x8A.toByte(), 0x41,
                0x64, 0x00, 0x4E, 0x56, 0xFF.toByte(), 0xFA.toByte(), 0x01, 0x08,
                0x2E, 0x2E, 0x00, 0xB6.toByte(), 0xD0.toByte(), 0x68, 0x3E, 0x80.toByte(),
                0x2F, 0x0C, 0xA9.toByte(), 0xFE.toByte(), 0x64, 0x53, 0x69, 0x7A
            )
            fun padPw(pw: ByteArray): ByteArray {
                val out = ByteArray(32)
                val n = minOf(pw.size, 32)
                System.arraycopy(pw, 0, out, 0, n)
                System.arraycopy(pad, 0, out, n, 32 - n)
                return out
            }
            val user = encryptWith.toByteArray(Charsets.ISO_8859_1)
            val fileId = ByteArray(16) { (it * 7 + 3).toByte() }
            val p = -3904

            // O — the owner entry (owner password = user password here).
            var keyO = md5.digest(padPw(user))
            repeat(50) { keyO = MessageDigest.getInstance("MD5").digest(keyO) }
            keyO = keyO.copyOf(16)
            var o = padPw(user)
            for (i in 0..19) {
                val stepKey = ByteArray(16) { (keyO[it].toInt() xor i).toByte() }
                o = PdfCrypto.rc4(stepKey, o)
            }

            // File key (R3).
            val keyDigest = MessageDigest.getInstance("MD5")
            keyDigest.update(padPw(user)); keyDigest.update(o)
            keyDigest.update(
                byteArrayOf(p.toByte(), (p shr 8).toByte(), (p shr 16).toByte(), (p shr 24).toByte())
            )
            keyDigest.update(fileId)
            var key = keyDigest.digest()
            repeat(50) { key = MessageDigest.getInstance("MD5").digest(key.copyOf(16)) }
            key = key.copyOf(16)

            // U — algorithm 5.
            val uSeed = MessageDigest.getInstance("MD5").apply { update(pad); update(fileId) }.digest()
            var u = PdfCrypto.rc4(key, uSeed)
            for (i in 1..19) {
                val stepKey = ByteArray(16) { (key[it].toInt() xor i).toByte() }
                u = PdfCrypto.rc4(stepKey, u)
            }
            val uFull = u + ByteArray(16)

            // Encrypt the content stream with the per-object key of object 4.
            val objKey = MessageDigest.getInstance("MD5").apply {
                update(key)
                update(byteArrayOf(4, 0, 0))
                update(byteArrayOf(0, 0))
            }.digest()  // 16 bytes: min(16+5, 16)
            contentBytes = PdfCrypto.rc4(objKey, contentBytes)

            fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02X".format(it) }
            encryptDict = "6 0 obj << /Filter /Standard /V 2 /R 3 /Length 128 /P $p " +
                "/O <${hex(o)}> /U <${hex(uFull)}> >> endobj\n"
            idEntry = " /ID [<${hex(fileId)}> <${hex(fileId)}>] /Encrypt 6 0 R"
        }

        val objects = mutableListOf(
            "1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj\n",
            "2 0 obj << /Type /Pages /Kids [3 0 R] /Count 1 >> endobj\n",
            "3 0 obj << /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R " +
                "/Resources << /Font << /F1 5 0 R >> >> >> endobj\n",
            null,  // placeholder for the stream object, assembled from bytes
            "5 0 obj << /Type /Font /Subtype /Type1 /BaseFont /Helvetica >> endobj\n"
        )
        if (encryptDict.isNotEmpty()) objects.add(encryptDict)

        val out = ByteArrayOutputStream()
        out.write("%PDF-1.4\n".toByteArray())
        val offsets = ArrayList<Int>()
        objects.forEachIndexed { i, obj ->
            offsets.add(out.size())
            if (obj != null) {
                out.write(obj.toByteArray(Charsets.ISO_8859_1))
            } else {
                out.write("4 0 obj << /Length ${contentBytes.size} >> stream\n".toByteArray())
                out.write(contentBytes)
                out.write("\nendstream endobj\n".toByteArray())
            }
        }
        val xrefAt = out.size()
        val xref = StringBuilder("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { xref.append("%010d 00000 n \n".format(it)) }
        xref.append("trailer << /Size ${objects.size + 1} /Root 1 0 R$idEntry >>\nstartxref\n$xrefAt\n%%EOF")
        out.write(xref.toString().toByteArray(Charsets.ISO_8859_1))
        return out.toByteArray()
    }

    @Test
    fun `plain pdf reconstructs the table and parses`() {
        val grid = StatementReader.read(pdfFixture(), "statement.pdf")
        assertEquals("PDF", grid.format)

        val mapping = StatementParse.detectMapping(grid)
        assertTrue(mapping.headerRow >= 0, "header row not found in ${grid.rows}")

        val outcome = StatementParse.parse(grid, mapping, StatementParse.Extraction())
        assertEquals(2, outcome.rows.size, "rows: ${grid.rows}")
        assertEquals(500.0, outcome.rows[0].amount)
        assertEquals(false, outcome.rows[0].isCredit)
        assertEquals(9500.0, outcome.rows[0].balance)
        assertEquals(true, outcome.rows[1].isCredit)
        assertEquals(25000.0, outcome.rows[1].amount)
        assertTrue(outcome.rows[1].balanceAgrees == true)
    }

    @Test
    fun `encrypted pdf demands the password then decrypts`() {
        val bytes = pdfFixture(encryptWith = "sx2026")
        assertFailsWith<StatementPasswordRequired> {
            StatementReader.read(bytes, "locked.pdf")
        }
        assertFailsWith<StatementPasswordRequired> {
            StatementReader.read(bytes, "locked.pdf", password = "wrong")
        }
        val grid = StatementReader.read(bytes, "locked.pdf", password = "sx2026")
        val outcome = StatementParse.parse(grid, StatementParse.detectMapping(grid), StatementParse.Extraction())
        assertEquals(2, outcome.rows.size)
        assertEquals(500.0, outcome.rows[0].amount)
    }

    // ── Real-world bank shapes ───────────────────────────────────────────────
    // Synthetic fixtures modelled on genuine exports (verified against real
    // files via `./gradlew verifyStatement`): structure is theirs, data is not.

    @Test
    fun `axis shape - bare DR CR BAL headers map and constant SOL is ignored`() {
        // Axis .xls: SRL NO | Tran Date | CHQNO | PARTICULARS | DR | CR | BAL | SOL.
        // SOL is a constant branch code; it was once crowned the running balance.
        val csv = """
            Statement of Account,,,,,,,
            Account No : XXXXXX1234,,,,,,,
            SRL NO,Tran Date,CHQNO,PARTICULARS,DR,CR,BAL,SOL
            1,01-02-2026,,UPI/P2M/062615114601/SOME SHOP/NO REM/BANK,15.00,,12681.40,763
            2,01-02-2026,,UPI/P2A/062615114602/SOME ONE/UPI/BANK,,50.00,12731.40,763
            3,02-02-2026,,ATM WDL 1234,500.00,,12231.40,763
        """.trimIndent()
        val grid = StatementReader.read(csv.toByteArray(), "AcctStatement_XXX1234_01022026.csv")

        assertEquals("Axis Bank", StatementParse.detectBank(grid)?.label)

        val mapping = StatementParse.detectMapping(grid)
        assertEquals(StatementParse.Col.IGNORE, mapping.columns[0])   // SRL NO
        assertEquals(StatementParse.Col.DATE, mapping.columns[1])
        assertEquals(StatementParse.Col.DEBIT, mapping.columns[4])
        assertEquals(StatementParse.Col.CREDIT, mapping.columns[5])
        assertEquals(StatementParse.Col.BALANCE, mapping.columns[6])  // BAL, not SOL
        assertEquals(StatementParse.Col.IGNORE, mapping.columns[7])   // SOL

        val outcome = StatementParse.parse(grid, mapping, StatementParse.Extraction())
        assertEquals(3, outcome.rows.size)
        assertEquals(12681.40, outcome.rows[0].balance)
        assertEquals(false, outcome.rows[0].isCredit)
        assertEquals(true, outcome.rows[1].isCredit)
        assertTrue(outcome.rows.drop(1).all { it.balanceAgrees == true })
    }

    @Test
    fun `bank detection reads the letterhead not the narrations`() {
        // The counterparty's bank in a narration must not win over the issuer.
        val csv = """
            AXIS BANK LTD - Statement,,,,
            Date,Narration,Debit,Credit,Balance
            01/08/2026,UPI/P2M/1/SHOP/NO REM/YES BANK LIMITED YBS,10.00,,90.00
        """.trimIndent()
        val grid = StatementReader.read(csv.toByteArray(), "statement.csv")
        assertEquals("Axis Bank", StatementParse.detectBank(grid)?.label)
    }

    @Test
    fun `idfc shape - filename detection and named-month dates are unambiguous`() {
        val csv = """
            Customer Statement,,,,,,
            Transaction Date,Value Date,Particulars,Cheque No.,Debit,Credit,Balance
            02-Apr-2026,02-Apr-2026,UPI/CR/610758747407/SOMEONE/HDFC/12345/Payment,,,"2,000.00","4,073.55"
            03-Apr-2026,03-Apr-2026,UPI/DR/210659628152/OTHER/YESB/paytmqr/Person,,80.00,,"3,993.55"
        """.trimIndent()
        val grid = StatementReader.read(
            csv.toByteArray(), "IDFCFIRSTBankstatement_1234567_890.csv"
        )
        assertEquals("IDFC FIRST Bank", StatementParse.detectBank(grid)?.label)

        val mapping = StatementParse.detectMapping(grid)
        assertEquals(false, mapping.ambiguousDateOrder)  // named months can't be misread
        assertEquals(StatementParse.Col.VALUE_DATE, mapping.columns[1])

        val outcome = StatementParse.parse(grid, mapping, StatementParse.Extraction())
        assertEquals(2, outcome.rows.size)
        assertEquals("2026-04-02", outcome.rows[0].dateIso)
        assertEquals(true, outcome.rows[0].isCredit)
        assertEquals(true, outcome.rows[1].balanceAgrees)
    }

    @Test
    fun `footer line does not glue onto the last narration`() {
        val csv = """
            Date,Narration,Debit,Credit,Balance
            01/08/2026,REAL TRANSACTION,10.00,,90.00
            ,End of the statement,,,
        """.trimIndent()
        val grid = StatementReader.read(csv.toByteArray(), "s.csv")
        val outcome = StatementParse.parse(grid, StatementParse.detectMapping(grid), StatementParse.Extraction())
        assertEquals(1, outcome.rows.size)
        assertEquals("REAL TRANSACTION", outcome.rows[0].narration)
    }

    @Test
    fun `headerless inference never crowns a constant column as balance`() {
        // No recognisable header: content inference must skip the constant
        // branch-code column when choosing the running balance.
        val rows = StringBuilder("Some preamble line,,,\n")
        var balance = 10000.0
        for (day in 10..18) {
            balance -= 100.0
            rows.append("$day/08/2026,PAYMENT NUMBER $day,100.00,${"%.2f".format(balance)},763\n")
        }
        val grid = StatementReader.read(rows.toString().toByteArray(), "noheader.csv")
        val mapping = StatementParse.detectMapping(grid)
        assertEquals(-1, mapping.headerRow)
        val outcome = StatementParse.parse(grid, mapping, StatementParse.Extraction())
        assertTrue(outcome.rows.isNotEmpty())
        // Balance came from the wandering column, not the constant 763.
        assertTrue(outcome.rows.all { it.balance != 763.0 })
        assertTrue(outcome.rows.count { it.balanceAgrees == true } >= outcome.rows.size - 1)
    }

    // ── Dispatcher ───────────────────────────────────────────────────────────

    @Test
    fun `dispatcher refuses an empty file with a clear message`() {
        assertFailsWith<StatementReadException> { StatementReader.read(ByteArray(0), "x.csv") }
    }

    @Test
    fun `utf16 exported tsv decodes and parses`() {
        val tsv = "Date\tNarration\tDebit\tCredit\n01/08/2026\tATM WDL\t900\t\n"
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + tsv.toByteArray(Charsets.UTF_16LE)
        val grid = StatementReader.read(bytes, "statement.xls")  // fake-xls TSV
        assertEquals("TSV", grid.format)
        val outcome = StatementParse.parse(grid, StatementParse.detectMapping(grid), StatementParse.Extraction())
        assertEquals(1, outcome.rows.size)
        assertEquals(900.0, outcome.rows[0].amount)
    }
}
