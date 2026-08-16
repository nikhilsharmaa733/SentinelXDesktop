package com.nikhil.sentinelx.desktop.tools

import com.nikhil.sentinelx.desktop.core.statement.StatementParse
import com.nikhil.sentinelx.desktop.core.statement.StatementReader
import java.io.File

/**
 * Statement-engine probe: parses a real bank file and reports what the engine
 * saw — format, header, column mapping, bank detection, row outcome — without
 * importing anything anywhere. The counterpart of `verifySxv` for the bank
 * book:
 *
 *   ./gradlew verifyStatement --args="/path/to/statement.xls" -q
 *
 * Prints structure and a handful of sample rows to the local terminal only.
 */
fun main(args: Array<String>) {
    val path = args.firstOrNull() ?: run {
        System.err.println("usage: verifyStatement <file> [pdf-password]")
        return
    }
    val password = args.getOrNull(1)
    val bytes = File(path).readBytes()

    val grid = StatementReader.read(bytes, File(path).name, password)
    println("format        : ${grid.format}")
    println("rows          : ${grid.rows.size}")
    grid.warnings.forEach { println("grid warning  : $it") }

    val bank = StatementParse.detectBank(grid)
    println("bank detected : ${bank?.label ?: "(none — generic rules)"}")

    val mapping = StatementParse.detectMapping(grid)
    println("header row    : ${mapping.headerRow}")
    if (mapping.headerRow >= 0) {
        println("header cells  : " + grid.rows[mapping.headerRow].joinToString(" | "))
    }
    println("columns       : " + mapping.columns.entries.sortedBy { it.key }
        .joinToString("  ") { "${it.key}=${it.value}" })
    println("dayFirst      : ${mapping.dayFirst} (ambiguous=${mapping.ambiguousDateOrder})")

    val outcome = StatementParse.parse(grid, mapping, StatementParse.Extraction())
    println("parsed        : ${outcome.rows.size} rows, ${outcome.skipped} skipped")
    println("suggested book: ${outcome.suggestedBook}")
    outcome.warnings.forEach { println("warning       : $it") }
    println("opening bal   : ${outcome.openingBalance}")

    val credits = outcome.rows.count { it.isCredit }
    val debits = outcome.rows.size - credits
    val agree = outcome.rows.count { it.balanceAgrees == true }
    val disagree = outcome.rows.count { it.balanceAgrees == false }
    println("direction     : $debits debit / $credits credit")
    println("balance check : $agree agree, $disagree disagree, ${outcome.rows.size - agree - disagree} unverifiable")

    println("---- first rows ----")
    outcome.rows.take(6).forEach { r ->
        println(
            "${r.dateIso}  ${r.direction.padEnd(6)} amt=${"%12.2f".format(r.amount)}  " +
                "bal=${r.balance?.let { "%12.2f".format(it) } ?: "        —   "}  " +
                "party=${(r.party ?: "").take(24).padEnd(24)}  ${r.narration.take(48)}"
        )
    }
    println("---- last rows ----")
    outcome.rows.takeLast(3).forEach { r ->
        println(
            "${r.dateIso}  ${r.direction.padEnd(6)} amt=${"%12.2f".format(r.amount)}  " +
                "bal=${r.balance?.let { "%12.2f".format(it) } ?: "        —   "}  ${r.narration.take(48)}"
        )
    }
}
