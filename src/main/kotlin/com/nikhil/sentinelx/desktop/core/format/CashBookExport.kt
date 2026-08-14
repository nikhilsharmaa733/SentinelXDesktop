package com.nikhil.sentinelx.desktop.core.format

import java.io.File
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale

/**
 * Takes the cash book out of the vault as something an accountant, a bank, or a family
 * member can actually read.
 *
 * ⚠️ Both formats are **plaintext**, like [CsvExport] and for the same reason: the whole
 * point is to open them somewhere else. The UI has to say so every time.
 *
 * Two formats because they answer different questions. CSV is for arithmetic — one row
 * per entry, a debit column and a credit column, so any spreadsheet sums them. HTML is
 * the statement you print or hand over: a real two-column cash book with the note
 * breakdown under each line and the totals struck at the foot.
 *
 * HTML rather than PDF deliberately. Every browser and every phone prints HTML to PDF
 * for free, whereas a PDF library would be a new dependency to audit for network calls
 * and another entry in the packaging module list — for output nobody would tell apart.
 */
object CashBookExport {

    private val isoDate = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val prettyDate = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
    private val dayName = DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH)

    // ── CSV ──────────────────────────────────────────────────────────────────

    /**
     * One row per entry, oldest first with a running balance — the order a cash book is
     * read in, and the only order in which a balance column means anything.
     *
     * Debit and credit are separate columns rather than one signed figure, so a plain
     * `SUM` over each gives the two totals without a formula that has to interpret a
     * direction column.
     */
    fun csv(file: File, entries: List<CashEntryEntity>) {
        val ordered = entries.sortedWith(compareBy({ it.entryDate }, { it.slotOrder() }, { it.timestamp }))
        var running = 0.0

        // Rows are assembled as lists and joined, never by appending commas by hand.
        // The totals row has to line up with a header whose width depends on how many
        // denominations exist; counting separators by eye is how a column silently
        // slips one place to the left.
        val header = buildList {
            addAll(listOf("Date", "Day", "When", "Direction", "Particulars", "Debit", "Credit", "Balance"))
            addAll(listOf("Counted Total", "Difference"))
            CashBook.DENOMINATIONS.forEach { add("x$it") }
            addAll(listOf("Total Notes", "Counted By", "Verified By", "Status", "Remarks"))
        }

        val body = ordered.map { entry ->
            running += entry.signedAmount()
            val counts = entry.denominationCounts()
            val date = businessDateOf(entry.entryDate)

            buildList {
                add(date.format(isoDate))
                add(date.format(dayName))
                add(entry.slot)
                add(entry.direction)
                add(entry.particulars)
                add(if (entry.isIn()) entry.amount.plain() else "")
                add(if (entry.isIn()) "" else entry.amount.plain())
                add(running.plain())
                // Blank rather than 0 when nothing was counted, so "no tally taken" and
                // "tallied to zero" stay distinguishable in the spreadsheet.
                add(if (counts.isEmpty()) "" else entry.countedTotal().plain())
                add(if (counts.isEmpty()) "" else entry.reconciliationDifference().plain())
                CashBook.DENOMINATIONS.forEach { add(counts[it]?.toString() ?: "") }
                add(counts.values.sum().takeIf { it > 0 }?.toString() ?: "")
                add(entry.countedBy)
                add(entry.verifiedBy)
                add(entry.status)
                add(entry.notes.orEmpty())
            }
        }

        // The totals a cash book is kept for, struck at the foot where a reader looks.
        val totals = MutableList(header.size) { "" }.apply {
            this[0] = "TOTALS"
            this[header.indexOf("Debit")] = ordered.totalDebit().plain()
            this[header.indexOf("Credit")] = ordered.totalCredit().plain()
            this[header.indexOf("Balance")] = ordered.netPosition().plain()
        }

        val text = (listOf(header) + body + listOf(totals))
            .joinToString("\n") { row -> row.joinToString(",") { escape(it) } }

        file.writeText(text + "\n", Charsets.UTF_8)
    }

    /**
     * RFC 4180 quoting. Particulars routinely contain commas, and an unquoted one
     * shifts every later column into a file that still opens and still looks plausible.
     */
    private fun escape(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }

    // ── HTML statement ───────────────────────────────────────────────────────

    /**
     * A self-contained printable statement. Images are embedded as data URIs so the
     * file survives being emailed on its own — a statement that loses its slip photos
     * the moment it leaves the folder is not evidence of anything.
     */
    fun html(
        file: File,
        entries: List<CashEntryEntity>,
        title: String,
        images: Map<String, ByteArray> = emptyMap()
    ) {
        val ordered = entries.sortedWith(compareBy({ it.entryDate }, { it.slotOrder() }, { it.timestamp }))
        val debit = ordered.totalDebit()
        val credit = ordered.totalCredit()
        val closing = ordered.netPosition()
        val inventory = ordered.noteInventory()
        val days = ordered.map { it.entryDate }.distinct().size
        val unverified = ordered.count { !it.isVerified() }
        val unreconciled = ordered.count { !it.isReconciled() }

        val range = when {
            ordered.isEmpty() -> "No entries"
            else -> "${businessDateOf(ordered.first().entryDate).format(prettyDate)} — " +
                businessDateOf(ordered.last().entryDate).format(prettyDate)
        }

        var running = 0.0
        val rows = buildString {
            ordered.forEach { entry ->
                running += entry.signedAmount()
                val date = businessDateOf(entry.entryDate)
                val counts = entry.denominationCounts()
                val breakdown = counts.keys.sortedDescending()
                    .joinToString(" · ") { "${it}×${counts.getValue(it)}" }

                append("<tr class=\"${if (entry.isIn()) "in" else "out"}\">")
                append("<td class=\"date\"><strong>${date.format(prettyDate)}</strong>")
                append("<span class=\"day\">${date.format(dayName)}</span></td>")
                append("<td class=\"slot\">${esc(slotLabel(entry.slot))}</td>")
                append("<td class=\"particulars\">")
                append("<div>${esc(entry.particulars.ifBlank { "—" })}</div>")
                if (breakdown.isNotEmpty()) append("<div class=\"notes-line\">$breakdown</div>")
                if (!entry.isReconciled()) {
                    val diff = entry.reconciliationDifference()
                    append(
                        "<div class=\"flag\">Tally ${if (diff < 0) "short" else "over"} by " +
                            "${money(kotlin.math.abs(diff))} — counted ${money(entry.countedTotal())}</div>"
                    )
                }
                if (!entry.notes.isNullOrBlank()) append("<div class=\"remark\">${esc(entry.notes)}</div>")
                append("</td>")
                append("<td class=\"who\">")
                if (entry.countedBy.isNotBlank()) append("<div>Counted: ${esc(entry.countedBy)}</div>")
                if (entry.verifiedBy.isNotBlank()) append("<div>Verified: ${esc(entry.verifiedBy)}</div>")
                append(
                    if (entry.isVerified()) "<span class=\"badge ok\">VERIFIED</span>"
                    else "<span class=\"badge pending\">PENDING</span>"
                )
                append("</td>")
                append("<td class=\"num debit\">${if (entry.isIn()) money(entry.amount) else ""}</td>")
                append("<td class=\"num credit\">${if (entry.isIn()) "" else money(entry.amount)}</td>")
                append("<td class=\"num balance\">${money(running)}</td>")
                append("</tr>")

                val slips = entry.slipFilenames().mapNotNull { name ->
                    images[name]?.let { name to it }
                }
                if (slips.isNotEmpty()) {
                    append("<tr class=\"slips\"><td></td><td></td><td colspan=\"4\">")
                    slips.forEach { (name, bytes) ->
                        val mime = mimeFor(name)
                        val encoded = Base64.getEncoder().encodeToString(bytes)
                        append("<img src=\"data:$mime;base64,$encoded\" alt=\"slip\"/>")
                    }
                    append("</td></tr>")
                }
            }
        }

        val inventoryHtml = if (inventory.isEmpty()) "" else buildString {
            append("<section><h2>Notes still held</h2><div class=\"chips\">")
            inventory.forEach { (denomination, count) ->
                append("<span class=\"chip\"><b>₹$denomination</b> × $count")
                append("<i>${money(denomination.toDouble() * count)}</i></span>")
            }
            append("</div></section>")
        }

        val warnings = buildString {
            if (unverified > 0 || unreconciled > 0) {
                append("<div class=\"warn\">")
                if (unverified > 0) append("$unverified entr${if (unverified == 1) "y" else "ies"} not yet verified. ")
                if (unreconciled > 0) append("$unreconciled entr${if (unreconciled == 1) "y" else "ies"} where the note count does not match the amount.")
                append("</div>")
            }
        }

        file.writeText(
            document(
                title = title,
                range = range,
                summary = summaryCards(debit, credit, closing, ordered.size, days),
                warnings = warnings,
                rows = rows,
                totals = Triple(debit, credit, closing),
                inventory = inventoryHtml
            ),
            Charsets.UTF_8
        )
    }

    private fun summaryCards(
        debit: Double,
        credit: Double,
        closing: Double,
        entries: Int,
        days: Int
    ) = buildString {
        append("<div class=\"cards\">")
        append(card("Total debit", money(debit), "received", "pos"))
        append(card("Total credit", money(credit), "paid out", "neg"))
        append(card("Closing balance", money(closing), "in hand", if (closing < 0) "neg" else "bal"))
        append(card("Entries", entries.toString(), "over $days day${if (days == 1) "" else "s"}", ""))
        append("</div>")
    }

    private fun card(label: String, value: String, hint: String, tone: String) =
        "<div class=\"card $tone\"><span class=\"label\">$label</span>" +
            "<span class=\"value\">$value</span><span class=\"hint\">$hint</span></div>"

    private fun document(
        title: String,
        range: String,
        summary: String,
        warnings: String,
        rows: String,
        totals: Triple<Double, Double, Double>,
        inventory: String
    ): String {
        val (debit, credit, closing) = totals
        return """
<!doctype html>
<html lang="en"><head><meta charset="utf-8"/>
<title>${esc(title)}</title>
<style>
  :root { --ink:#1a1a1f; --muted:#6b6b76; --line:#dcdce4; --gold:#8a6d1f; --pos:#1f7a44; --neg:#b0243a; --bg:#fff; }
  * { box-sizing:border-box; }
  body { font-family:"Georgia","Times New Roman",serif; color:var(--ink); background:var(--bg);
         margin:0; padding:36px 40px; font-size:13px; line-height:1.5; }
  header { border-bottom:2px solid var(--gold); padding-bottom:14px; margin-bottom:22px; }
  h1 { font-size:24px; margin:0 0 4px; letter-spacing:1px; }
  .sub { color:var(--muted); font-size:11px; letter-spacing:2px; text-transform:uppercase; }
  .cards { display:flex; gap:12px; margin:22px 0; flex-wrap:wrap; }
  .card { flex:1 1 150px; border:1px solid var(--line); border-radius:8px; padding:12px 14px;
          display:flex; flex-direction:column; gap:2px; }
  .card .label { font-size:9px; letter-spacing:1.5px; text-transform:uppercase; color:var(--muted); }
  .card .value { font-size:20px; font-weight:bold; }
  .card .hint { font-size:10px; color:var(--muted); }
  .card.pos .value { color:var(--pos); } .card.neg .value { color:var(--neg); }
  .card.bal .value { color:var(--gold); }
  .warn { border-left:3px solid var(--neg); background:#fdf3f4; padding:9px 13px;
          font-size:11px; margin-bottom:18px; }
  table { width:100%; border-collapse:collapse; font-size:12px; }
  th { text-align:left; font-size:9px; letter-spacing:1.5px; text-transform:uppercase;
       color:var(--muted); border-bottom:1.5px solid var(--ink); padding:7px 8px; font-weight:normal; }
  td { border-bottom:1px solid var(--line); padding:9px 8px; vertical-align:top; }
  th.num, td.num { text-align:right; white-space:nowrap; }
  td.date strong { display:block; } td.date .day { font-size:10px; color:var(--muted); }
  td.slot { font-size:10px; letter-spacing:1px; text-transform:uppercase; color:var(--muted); }
  .notes-line { font-size:10px; color:var(--muted); margin-top:3px; font-family:monospace; }
  .remark { font-size:10px; color:var(--muted); font-style:italic; margin-top:3px; }
  .flag { font-size:10px; color:var(--neg); margin-top:3px; font-weight:bold; }
  td.who { font-size:10px; color:var(--muted); }
  .badge { display:inline-block; margin-top:3px; font-size:8px; letter-spacing:1px; padding:1px 5px;
           border-radius:3px; border:1px solid currentColor; }
  .badge.ok { color:var(--pos); } .badge.pending { color:#9a7b12; }
  td.debit { color:var(--pos); } td.credit { color:var(--neg); }
  td.balance { font-weight:bold; }
  tr.slips td { border-bottom:1px solid var(--line); padding-top:0; }
  tr.slips img { height:110px; margin:0 8px 8px 0; border:1px solid var(--line); border-radius:5px; }
  tfoot td { border-top:2px solid var(--ink); border-bottom:none; font-weight:bold; font-size:13px;
             padding-top:11px; }
  tfoot .lbl { font-size:10px; letter-spacing:2px; text-transform:uppercase; }
  section { margin-top:26px; }
  h2 { font-size:12px; letter-spacing:2px; text-transform:uppercase; color:var(--muted);
       border-bottom:1px solid var(--line); padding-bottom:6px; }
  .chips { display:flex; flex-wrap:wrap; gap:8px; margin-top:12px; }
  .chip { border:1px solid var(--line); border-radius:6px; padding:6px 10px; font-size:11px; }
  .chip i { display:block; font-size:10px; color:var(--muted); font-style:normal; }
  footer { margin-top:30px; padding-top:12px; border-top:1px solid var(--line);
           font-size:10px; color:var(--muted); display:flex; justify-content:space-between; }
  /* Print to PDF is the intended delivery route, so it gets real attention:
     repeat the header on every page and never split a row across a page break. */
  @media print {
    body { padding:0; font-size:11px; }
    thead { display:table-header-group; }
    tr { page-break-inside:avoid; }
    .card { break-inside:avoid; }
  }
</style></head>
<body>
<header>
  <h1>${esc(title)}</h1>
  <div class="sub">Cash Book · ${esc(range)}</div>
</header>
$summary
$warnings
<table>
  <thead><tr>
    <th>Date</th><th>When</th><th>Particulars</th><th>Counted / verified</th>
    <th class="num">Debit</th><th class="num">Credit</th><th class="num">Balance</th>
  </tr></thead>
  <tbody>
$rows
  </tbody>
  <tfoot><tr>
    <td colspan="4" class="lbl">Totals</td>
    <td class="num">${money(debit)}</td>
    <td class="num">${money(credit)}</td>
    <td class="num">${money(closing)}</td>
  </tr></tfoot>
</table>
$inventory
<footer>
  <span>Generated by SentinelX — offline personal vault</span>
  <span>This document is not encrypted</span>
</footer>
</body></html>
        """.trimIndent()
    }

    // ── Formatting ───────────────────────────────────────────────────────────

    /** Grouped for reading. Only ever used in the HTML statement, never in the CSV. */
    private fun money(amount: Double): String =
        "₹" + String.format(Locale.forLanguageTag("en-IN"), "%,.2f", amount)

    /** Ungrouped and unsymbolled, so a spreadsheet reads it as a number. */
    private fun Double.plain(): String = String.format(Locale.ROOT, "%.2f", this)

    private fun esc(value: String): String = value
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun slotLabel(slot: String) = when (slot) {
        CashBook.SLOT_EVENING -> "Evening"
        CashBook.SLOT_MORNING -> "Morning"
        else -> "Other"
    }

    /**
     * Evening precedes morning within a business date: the pair a date carries is
     * "came home tonight, goes back tomorrow", which is also the order the day cards
     * draw. The old ordering put MORNING first, so the running balance showed the
     * money leaving before it had arrived and dipped negative through every pair.
     */
    private fun CashEntryEntity.slotOrder(): Int = when (slot) {
        CashBook.SLOT_EVENING -> 0
        CashBook.SLOT_MORNING -> 1
        else -> 2
    }

    private fun mimeFor(name: String) = when (name.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "gif" -> "image/gif"
        "bmp" -> "image/bmp"
        "webp" -> "image/webp"
        else -> "image/jpeg"
    }
}
