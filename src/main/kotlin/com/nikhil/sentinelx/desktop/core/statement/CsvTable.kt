package com.nikhil.sentinelx.desktop.core.statement

/*
 * ⚠️ MIRRORED FILE — see StatementGrid.kt. Byte-identical to the Android copy
 * apart from the package line.
 */

/**
 * CSV / TSV reader with delimiter sniffing.
 *
 * Banks disagree on everything: comma vs semicolon vs tab vs pipe, quoted vs
 * bare, preamble lines above the table ("Account Name: …"). The sniffing picks
 * the delimiter that yields the most *consistent* multi-column split across
 * lines, which survives all of that; the preamble stays in the grid — header
 * detection in [StatementParse] is what skips it.
 */
internal object CsvTable {

    private val CANDIDATES = charArrayOf(',', ';', '\t', '|')

    fun read(text: String): StatementGrid {
        val cleaned = text.removePrefix("﻿")
        val lines = cleaned.split("\r\n", "\n", "\r").filterNot { it.isEmpty() }
        if (lines.isEmpty()) return StatementGrid(emptyList(), "CSV", listOf("The file is empty."))

        val delimiter = sniffDelimiter(lines)
        val rows = lines.map { parseLine(it, delimiter) }
        return StatementGrid(rows, if (delimiter == '\t') "TSV" else "CSV")
    }

    /**
     * Counts *unquoted* occurrences of each candidate per line, then scores by
     * how many lines agree on a non-zero count. Quoted commas inside a
     * narration must not vote, or "PAYMENT, RENT" elects a phantom column.
     */
    private fun sniffDelimiter(lines: List<String>): Char {
        val sample = lines.take(50)
        var best = ','
        var bestScore = -1
        for (candidate in CANDIDATES) {
            val counts = sample.map { unquotedCount(it, candidate) }.filter { it > 0 }
            if (counts.isEmpty()) continue
            // Most common count and how many lines share it: consistency beats volume.
            val modal = counts.groupingBy { it }.eachCount().maxByOrNull { it.value }!!
            val score = modal.value * 100 + modal.key
            if (score > bestScore) {
                bestScore = score
                best = candidate
            }
        }
        return best
    }

    private fun unquotedCount(line: String, delimiter: Char): Int {
        var count = 0
        var inQuotes = false
        for (ch in line) {
            when {
                ch == '"' -> inQuotes = !inQuotes
                ch == delimiter && !inQuotes -> count++
            }
        }
        return count
    }

    /** RFC-4180: quoted fields, doubled quotes inside them. */
    private fun parseLine(line: String, delimiter: Char): List<String> {
        val fields = ArrayList<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                inQuotes -> when {
                    ch == '"' && i + 1 < line.length && line[i + 1] == '"' -> {
                        current.append('"'); i++
                    }
                    ch == '"' -> inQuotes = false
                    else -> current.append(ch)
                }
                ch == '"' -> inQuotes = true
                ch == delimiter -> {
                    fields.add(current.toString().trim()); current.setLength(0)
                }
                else -> current.append(ch)
            }
            i++
        }
        fields.add(current.toString().trim())
        return fields
    }
}
