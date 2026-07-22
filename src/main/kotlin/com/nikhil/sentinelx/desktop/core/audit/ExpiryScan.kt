package com.nikhil.sentinelx.desktop.core.audit

import com.nikhil.sentinelx.desktop.core.format.ArtifactEntity
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Finds upcoming expiry dates across cards and identity documents.
 *
 * The phone stores these as **free text** in whichever label slot the document type
 * happens to use — `label4` for a bank card's expiry, `label5` for a passport's, and
 * so on. Nothing validates the format at entry, so real vaults contain a mixture of
 * `20/08/2031`, `08/2031`, `2031-08-20` and worse.
 *
 * Rather than demanding one format, this tries several and gives up quietly on
 * anything unrecognised. A missed reminder is a small loss; a crash or a wrong date
 * presented confidently is a much worse one.
 */
object ExpiryScan {

    /** Which label slot holds the expiry, per document type — mirrors the phone's field captions. */
    private fun expiryFieldOf(artifact: ArtifactEntity): String? =
        when (artifact.type.uppercase()) {
            "BANK" -> artifact.label4              // EXPIRY
            "PASSPORT" -> artifact.label5          // EXPIRY DATE
            "DRIVING LICENCE" -> artifact.label3   // VALIDITY
            else -> null                           // Aadhaar, PAN, Voter ID do not expire
        }?.takeIf { it.isNotBlank() }

    private val patterns = listOf(
        "dd/MM/yyyy", "d/M/yyyy", "dd-MM-yyyy", "d-M-yyyy",
        "yyyy-MM-dd", "dd.MM.yyyy",
        "MM/yyyy", "M/yyyy", "MM-yyyy"
    ).map { DateTimeFormatter.ofPattern(it) }

    /**
     * Parses a free-text date, returning the LAST day of the month when only a month
     * is given — a card marked 08/2031 is valid through the end of August.
     */
    fun parse(text: String): LocalDate? {
        val cleaned = text.trim()

        for (format in patterns) {
            runCatching { return LocalDate.parse(cleaned, format) }
            // Month-only patterns fail LocalDate.parse; retry via YearMonth.
            runCatching {
                val ym = java.time.YearMonth.parse(cleaned, format)
                return ym.atEndOfMonth()
            }
        }

        // Two-digit years, e.g. an "08/31" printed on a card.
        Regex("""^(\d{1,2})[/\-.](\d{2})$""").find(cleaned)?.let { match ->
            val (m, y) = match.destructured
            runCatching {
                return java.time.YearMonth.of(2000 + y.toInt(), m.toInt()).atEndOfMonth()
            }
        }
        return null
    }

    data class Expiry(
        val artifact: ArtifactEntity,
        val date: LocalDate,
        val daysRemaining: Long
    ) {
        val expired: Boolean get() = daysRemaining < 0
        val urgent: Boolean get() = daysRemaining in 0..30
        val soon: Boolean get() = daysRemaining in 31..90
    }

    /**
     * Everything with a parseable expiry, soonest first.
     *
     * [withinDays] filters to a horizon; already-expired documents are always
     * included regardless, since those are the ones that actually need action.
     */
    fun scan(
        artifacts: List<ArtifactEntity>,
        today: LocalDate = LocalDate.now(),
        withinDays: Long = 180
    ): List<Expiry> = artifacts.mapNotNull { artifact ->
        val raw = expiryFieldOf(artifact) ?: return@mapNotNull null
        val date = parse(raw) ?: return@mapNotNull null
        val days = ChronoUnit.DAYS.between(today, date)
        if (days <= withinDays) Expiry(artifact, date, days) else null
    }.sortedBy { it.daysRemaining }
}
