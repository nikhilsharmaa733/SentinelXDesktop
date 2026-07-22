package com.nikhil.sentinelx.desktop.core.format

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Exports the ledger as CSV, for spreadsheets and tax season.
 *
 * ⚠️ CSV is **plaintext**. Everything else this app writes is encrypted; this
 * deliberately is not, because the point is to open it elsewhere. The UI has to say
 * so — a user who exports their finances to the Downloads folder without realising
 * it is readable has been failed by the software, not by themselves.
 *
 * Only the ledger is exportable. Passwords and card secrets are not, on purpose:
 * there is no legitimate workflow that needs those as plaintext CSV, and offering it
 * would make the most damaging possible export a single click away.
 */
object CsvExport {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    fun ledger(
        file: File,
        transactions: List<TransactionEntity>,
        accounts: List<AccountEntity>
    ) {
        val accountNames = accounts.associate { it.id to it.name }

        val rows = buildString {
            appendLine("Date,Account,Description,Category,Direction,Amount,Settled")
            transactions.sortedByDescending { it.timestamp }.forEach { tx ->
                append(escape(dateFormat.format(Date(tx.timestamp)))).append(',')
                append(escape(accountNames[tx.accountId] ?: "Unknown")).append(',')
                append(escape(tx.title)).append(',')
                append(escape(tx.category)).append(',')
                append(if (tx.isIncoming) "IN" else "OUT").append(',')
                // Signed, so a spreadsheet SUM over the column gives the net balance
                // without needing a formula that reads the direction column.
                append(if (tx.isIncoming) tx.amount else -tx.amount).append(',')
                appendLine(if (tx.isSettled) "yes" else "no")
            }
        }

        file.writeText(rows, Charsets.UTF_8)
    }

    /**
     * RFC 4180 quoting.
     *
     * Transaction titles routinely contain commas — "Chai, samosa" is a real row in
     * this vault — and an unquoted comma silently shifts every later column, which
     * corrupts the file in a way that still opens and looks plausible.
     */
    private fun escape(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }
}
