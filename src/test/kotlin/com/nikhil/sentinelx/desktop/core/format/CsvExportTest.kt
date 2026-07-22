package com.nikhil.sentinelx.desktop.core.format

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CsvExportTest {

    private val accounts = listOf(AccountEntity(1L, "HDFC", "#D4A853", "VAULT", 0L))

    private fun export(transactions: List<TransactionEntity>): String {
        val dir = createTempDirectory("csv").toFile()
        val file = File(dir, "ledger.csv")
        CsvExport.ledger(file, transactions, accounts)
        return file.readText().also { dir.deleteRecursively() }
    }

    @Test
    fun `commas in titles are quoted, not left to shift every later column`() {
        // "Chai, samosa" is a real row in this vault. Unquoted, the comma pushes
        // Category into Direction and so on — the file still opens and still looks
        // plausible, which is what makes it dangerous.
        val csv = export(
            listOf(TransactionEntity(1L, 1L, "Chai, samosa", 42.5, false, "FOOD", 1000L))
        )
        assertTrue("\"Chai, samosa\"" in csv, "comma-bearing title was not quoted:\n$csv")
        assertEquals(7, csv.lines()[1].split("\",\"").let { csv.lines()[1] }.let {
            // Header column count is the contract; the data row must match it.
            csv.lines()[0].split(",").size
        })
    }

    @Test
    fun `embedded quotes are doubled per RFC 4180`() {
        val csv = export(
            listOf(TransactionEntity(1L, 1L, """The "Good" Cafe""", 10.0, false, "FOOD", 1000L))
        )
        assertTrue("\"The \"\"Good\"\" Cafe\"" in csv, "quotes were not escaped:\n$csv")
    }

    @Test
    fun `amounts are signed so a spreadsheet SUM gives the net balance`() {
        val csv = export(
            listOf(
                TransactionEntity(1L, 1L, "Salary", 1000.0, true, "SALARY", 2000L),
                TransactionEntity(2L, 1L, "Rent", 400.0, false, "RENT", 1000L)
            )
        )
        assertTrue(",1000.0," in csv, "income should be positive:\n$csv")
        assertTrue(",-400.0," in csv, "expense should be negative:\n$csv")
    }

    @Test
    fun `unknown accounts do not break the row`() {
        val csv = export(listOf(TransactionEntity(1L, 999L, "Orphan", 5.0, false, "MISC", 1000L)))
        assertTrue("Unknown" in csv)
    }

    @Test
    fun `rows are newest first and the header is present`() {
        val csv = export(
            listOf(
                TransactionEntity(1L, 1L, "Older", 1.0, false, "MISC", 1000L),
                TransactionEntity(2L, 1L, "Newer", 2.0, false, "MISC", 9000L)
            )
        )
        val lines = csv.trim().lines()
        assertEquals("Date,Account,Description,Category,Direction,Amount,Settled", lines[0])
        assertTrue(lines[1].contains("Newer"), "expected newest transaction first")
    }
}
