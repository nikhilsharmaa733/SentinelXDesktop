package com.nikhil.sentinelx.desktop.core.format

import java.io.File
import java.time.LocalDate
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CashBookExportTest {

    private fun entry(
        id: Long,
        day: Int,
        direction: String,
        amount: Double,
        slot: String = CashBook.SLOT_OTHER,
        denominations: Map<Int, Int> = emptyMap(),
        particulars: String = "",
        notes: String? = null
    ) = CashEntryEntity(
        id = id,
        entryDate = LocalDate.of(2026, 8, day).toBusinessDate(),
        direction = direction,
        slot = slot,
        amount = amount,
        denominations = denominations.encodeDenominations(),
        particulars = particulars,
        countedBy = "Papa",
        verifiedBy = "Nikhil",
        status = CashBook.STATUS_VERIFIED,
        notes = notes,
        timestamp = 1_000L * id
    )

    private val sample = listOf(
        entry(1, 5, CashBook.IN, 56_000.0, CashBook.SLOT_EVENING, mapOf(500 to 100, 200 to 30), "Day's takings"),
        entry(2, 6, CashBook.OUT, 56_000.0, CashBook.SLOT_MORNING, mapOf(500 to 100, 200 to 30), "Back to office"),
        entry(3, 6, CashBook.IN, 4_000.0, CashBook.SLOT_OTHER, emptyMap(), "Mid-day pickup")
    )

    private fun csvOf(entries: List<CashEntryEntity>): List<String> {
        val dir = createTempDirectory("cashcsv").toFile()
        return try {
            val file = File(dir, "book.csv")
            CashBookExport.csv(file, entries)
            file.readText().trim().lines()
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * Splits one CSV record honouring RFC 4180 quoting.
     *
     * A naive `split(',')` counts a comma *inside* a quoted field as a separator, which
     * is exactly the corruption the escaping exists to prevent — so asserting column
     * counts with one would pass on broken output and fail on correct output.
     */
    private fun cells(line: String): List<String> {
        val out = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                quoted && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> {
                    cell.append('"'); i++
                }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> { out.add(cell.toString()); cell.clear() }
                else -> cell.append(c)
            }
            i++
        }
        out.add(cell.toString())
        return out
    }

    private fun htmlOf(
        entries: List<CashEntryEntity>,
        images: Map<String, ByteArray> = emptyMap()
    ): String {
        val dir = createTempDirectory("cashhtml").toFile()
        return try {
            val file = File(dir, "book.html")
            CashBookExport.html(file, entries, "Cash Book — August 2026", images)
            file.readText()
        } finally {
            dir.deleteRecursively()
        }
    }

    // ── CSV shape ────────────────────────────────────────────────────────────

    @Test
    fun `every row has exactly as many columns as the header`() {
        // The totals row is assembled separately from the data rows, and its width
        // depends on how many denominations exist. One column out of step there shifts
        // the totals under the wrong headings in a file that still opens fine.
        val lines = csvOf(sample)
        val width = cells(lines.first()).size
        lines.forEachIndexed { index, line ->
            assertEquals(width, cells(line).size, "line $index has the wrong column count:\n$line")
        }
    }

    @Test
    fun `totals land under the debit, credit and balance headings`() {
        val lines = csvOf(sample)
        val header = cells(lines.first())
        val totals = cells(lines.last())

        assertEquals("TOTALS", totals[0])
        assertEquals("60000.00", totals[header.indexOf("Debit")])
        assertEquals("56000.00", totals[header.indexOf("Credit")])
        assertEquals("4000.00", totals[header.indexOf("Balance")])
    }

    @Test
    fun `entries are chronological so the running balance means something`() {
        val lines = csvOf(sample)
        val header = cells(lines.first())
        val balances = lines.drop(1).dropLast(1).map { cells(it)[header.indexOf("Balance")] }
        assertEquals(listOf("56000.00", "0.00", "4000.00"), balances)
    }

    @Test
    fun `evening precedes morning within a day`() {
        // Cash comes home before it goes back — a business date's pair is "came home
        // tonight, goes back tomorrow", the order the day cards draw. This test used
        // to assert the opposite of its own name and pinned the bug it now guards
        // against: with morning first, the running balance showed the money leaving
        // before it had arrived and dipped negative through every pair.
        val sameDay = listOf(
            entry(1, 6, CashBook.OUT, 500.0, CashBook.SLOT_MORNING),
            entry(2, 6, CashBook.IN, 900.0, CashBook.SLOT_EVENING)
        )
        val lines = csvOf(sameDay)
        assertTrue(lines[1].contains("EVENING"), "evening should come first:\n${lines[1]}")
        assertTrue(lines[2].contains("MORNING"))
    }

    @Test
    fun `debit and credit are separate columns so a spreadsheet can total each`() {
        val header = cells(csvOf(sample).first())
        val incoming = cells(csvOf(listOf(sample[0]))[1])
        assertEquals("56000.00", incoming[header.indexOf("Debit")])
        assertEquals("", incoming[header.indexOf("Credit")])
    }

    @Test
    fun `an entry with no tally leaves the count columns blank rather than zero`() {
        // "Counted nothing" and "counted zero notes" are different claims, and a
        // spreadsheet AVERAGE over the column must not treat the first as the second.
        val header = cells(csvOf(sample).first())
        val row = cells(csvOf(listOf(sample[2]))[1])
        assertEquals("", row[header.indexOf("Counted Total")])
        assertEquals("", row[header.indexOf("x500")])
        assertEquals("", row[header.indexOf("Total Notes")])
    }

    @Test
    fun `denomination counts land under their own headings`() {
        val header = cells(csvOf(sample).first())
        val row = cells(csvOf(listOf(sample[0]))[1])
        assertEquals("100", row[header.indexOf("x500")])
        assertEquals("30", row[header.indexOf("x200")])
        assertEquals("", row[header.indexOf("x100")])
        assertEquals("130", row[header.indexOf("Total Notes")])
    }

    @Test
    fun `commas and quotes in particulars are escaped and survive a round trip`() {
        val awkward = """Takings, incl. "extra" float"""
        val lines = csvOf(listOf(entry(1, 5, CashBook.IN, 10.0, particulars = awkward)))
        val header = cells(lines.first())

        assertTrue("\"Takings, incl. \"\"extra\"\" float\"" in lines[1], lines[1])
        // The embedded comma must not have become a column boundary…
        assertEquals(header.size, cells(lines[1]).size)
        // …and the text must come back out exactly as it went in.
        assertEquals(awkward, cells(lines[1])[header.indexOf("Particulars")])
    }

    @Test
    fun `amounts are written unformatted so a spreadsheet reads them as numbers`() {
        // A grouped "56,000.00" would both break the column and import as text.
        val lines = csvOf(listOf(sample[0]))
        assertFalse("56,000" in lines[1], "amount should not be group-separated:\n${lines[1]}")
        assertTrue("56000.00" in lines[1])
    }

    @Test
    fun `an empty book still produces a header and a totals row`() {
        val lines = csvOf(emptyList())
        assertEquals(2, lines.size)
        assertTrue(lines[1].startsWith("TOTALS"))
    }

    // ── HTML statement ───────────────────────────────────────────────────────

    @Test
    fun `the statement carries the totals the book is kept for`() {
        val html = htmlOf(sample)
        assertTrue("Total debit" in html)
        assertTrue("Total credit" in html)
        assertTrue("Closing balance" in html)
        assertTrue("₹60,000.00" in html, "total debit missing")
        assertTrue("₹56,000.00" in html, "total credit missing")
    }

    @Test
    fun `a mismatch is called out in the statement rather than quietly balanced`() {
        val short = entry(9, 7, CashBook.IN, 56_000.0, CashBook.SLOT_EVENING, mapOf(500 to 111))
        val html = htmlOf(listOf(short))
        assertTrue("short by" in html, "the discrepancy should be stated on the page")
    }

    @Test
    fun `notes still held are listed when cash was left behind`() {
        // Only the evening half: 100 × ₹500 came home and nothing went back.
        val html = htmlOf(listOf(sample[0]))
        assertTrue("Notes still held" in html)
        assertTrue("₹500</b> × 100" in html, "the held denominations should be itemised")
    }

    @Test
    fun `a balanced range omits the inventory section rather than printing nothing held`() {
        // sample nets to 100 × ₹500 in and the same back out. "Notes still held: none"
        // is noise on a statement where the day closed clean.
        val html = htmlOf(listOf(sample[0], sample[1]))
        assertFalse("Notes still held" in html)
    }

    @Test
    fun `slip photos embed as data URIs so the file survives being emailed alone`() {
        val withSlip = entry(1, 5, CashBook.IN, 100.0).copy(slipImageUris = "slip.png")
        val html = htmlOf(listOf(withSlip), mapOf("slip.png" to byteArrayOf(1, 2, 3, 4)))
        assertTrue("data:image/png;base64," in html)
        assertFalse("src=\"slip.png\"" in html, "must not reference the file by path")
    }

    @Test
    fun `markup in user text is escaped, not rendered`() {
        val nasty = entry(1, 5, CashBook.IN, 10.0, particulars = "<script>alert(1)</script>")
        val html = htmlOf(listOf(nasty))
        assertFalse("<script>" in html, "user text was injected into the document as markup")
        assertTrue("&lt;script&gt;" in html)
    }

    @Test
    fun `dates render as the calendar day they were recorded on`() {
        // Business dates are stored at UTC midnight. Formatting them in the local zone
        // would print the previous day for anyone west of Greenwich.
        val html = htmlOf(listOf(entry(1, 5, CashBook.IN, 10.0)))
        assertTrue("5 Aug 2026" in html, "expected the recorded date to survive formatting")
    }
}
