package com.nikhil.sentinelx.desktop.core.statement

import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneOffset

/*
 * ⚠️ MIRRORED FILE — see StatementGrid.kt. Byte-identical to the Android copy
 * apart from the package line.
 */

/**
 * Wire vocabulary for bank transactions. These strings are persisted and cross
 * the `.sxv` boundary — constants, not enums, for the usual reason: an
 * unrecognised string from a newer build falls through a comparison instead of
 * throwing.
 */
object BankVocab {
    const val DEBIT = "DEBIT"
    const val CREDIT = "CREDIT"
    const val CAT_OTHER = "Other"
}

/**
 * The intelligence over a [StatementGrid]: find the header, map the columns,
 * read dates and amounts the way Indian banks write them, split the narration
 * into labelled fields, tag a category, fingerprint each row for
 * deduplication, and verify the running balance actually runs.
 *
 * Everything here is pure — same grid in, same rows out — which is what makes
 * the fingerprints stable across devices and the whole thing testable.
 */
object StatementParse {

    // ── Column model ─────────────────────────────────────────────────────────

    enum class Col(val label: String) {
        DATE("Date"),
        VALUE_DATE("Value date"),
        NARRATION("Narration"),
        REFERENCE("Ref / Cheque no"),
        DEBIT("Debit"),
        CREDIT("Credit"),
        AMOUNT("Amount"),
        DRCR("Dr / Cr"),
        BALANCE("Balance"),
        IGNORE("Ignore")
    }

    data class Mapping(
        /** Index of the header row in the grid, or -1 when none was found. */
        val headerRow: Int,
        val columns: Map<Int, Col>,
        /** 13/08 is unambiguous; 05/08 is not. This decides the ambiguous ones. */
        val dayFirst: Boolean,
        /** True when the data never disambiguates — the wizard shows a toggle. */
        val ambiguousDateOrder: Boolean
    )

    /** Which narration fields the user wants extracted — the wizard's toggles. */
    data class Extraction(
        val mode: Boolean = true,
        val channel: Boolean = true,
        val reference: Boolean = true,
        val party: Boolean = true,
        val remark: Boolean = true,
        val bank: Boolean = true,
        val autoCategory: Boolean = true
    )

    data class NarrationFields(
        val mode: String? = null,
        val channel: String? = null,
        val reference: String? = null,
        val party: String? = null,
        val remark: String? = null,
        val bankName: String? = null
    )

    data class ParsedRow(
        val rowIndex: Int,
        val dateIso: String,
        /** UTC midnight — the cash book's business-date convention. */
        val dateMillis: Long,
        val narration: String,
        /** Positive magnitude; [isCredit] carries the direction. */
        val amount: Double,
        val isCredit: Boolean,
        val balance: Double?,
        val reference: String?,
        val mode: String?,
        val channel: String?,
        val party: String?,
        val remark: String?,
        val bankName: String?,
        val category: String,
        /** Stable identity for dedup — see [fingerprintOf]. */
        val fingerprint: String,
        /** Null when the statement gave nothing to check against. */
        val balanceAgrees: Boolean?
    ) {
        val direction: String get() = if (isCredit) BankVocab.CREDIT else BankVocab.DEBIT
    }

    data class ParseOutcome(
        val rows: List<ParsedRow>,
        /** Footer, totals and unparseable rows that were dropped. */
        val skipped: Int,
        val warnings: List<String>,
        val openingBalance: Double?,
        /** "A/c ••1234", when the preamble above the header names the account. */
        val suggestedBook: String?
    )

    // ── Header detection ─────────────────────────────────────────────────────

    private fun normalise(cell: String): String =
        cell.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim().replace(Regex("\\s+"), " ")

    /** What a header cell means, or null when it reads like data. */
    fun classifyHeader(cell: String): Col? {
        val h = normalise(cell)
        if (h.isEmpty() || h.length > 40) return null
        val tokens = h.split(' ').toSet()
        return when {
            ("dr" in tokens && "cr" in tokens) || ("debit" in tokens && "credit" in tokens) ||
                h == "type" || h == "txn type" || h == "transaction type" -> Col.DRCR
            "balance" in tokens || "bal" in tokens -> Col.BALANCE
            "debit" in tokens || "withdrawal" in tokens || "withdrawals" in tokens ||
                "wdl" in tokens || ("paid" in tokens && "out" in tokens) -> Col.DEBIT
            "credit" in tokens || "deposit" in tokens || "deposits" in tokens ||
                ("paid" in tokens && "in" in tokens) -> Col.CREDIT
            "amount" in tokens || "amt" in tokens -> Col.AMOUNT
            "value" in tokens && "date" in tokens -> Col.VALUE_DATE
            "date" in tokens -> Col.DATE
            "narration" in tokens || "naration" in tokens || "description" in tokens ||
                "particulars" in tokens || "details" in tokens || "remarks" in tokens ||
                "remark" in tokens -> Col.NARRATION
            "ref" in tokens || "reference" in tokens || "cheque" in tokens || "chq" in tokens ||
                "utr" in tokens || "rrn" in tokens || "instrument" in tokens -> Col.REFERENCE
            else -> null
        }
    }

    /** How header-like a row is. PdfTable uses this to anchor its columns too. */
    internal fun headerRowScore(cells: List<String>): Int =
        cells.count { classifyHeader(it) != null }

    fun detectMapping(grid: StatementGrid): Mapping {
        var headerRow = -1
        var best = 0
        val limit = minOf(grid.rows.size, 45)
        for (i in 0 until limit) {
            val cells = grid.rows[i]
            val mapped = cells.map { classifyHeader(it) }
            val score = mapped.count { it != null }
            val hasDate = mapped.any { it == Col.DATE || it == Col.VALUE_DATE }
            val hasMoney = mapped.any { it == Col.DEBIT || it == Col.CREDIT || it == Col.AMOUNT }
            if (score >= 3 && hasDate && hasMoney && score > best) {
                best = score
                headerRow = i
            }
        }

        val columns = LinkedHashMap<Int, Col>()
        if (headerRow >= 0) {
            var dateSeen = false
            var debitSeen = false
            var creditSeen = false
            var balanceSeen = false
            var amountSeen = false
            grid.rows[headerRow].forEachIndexed { index, cell ->
                var col = classifyHeader(cell) ?: return@forEachIndexed
                // Duplicates: the first of a kind wins; a second date column is
                // the value date, a second money column is noise.
                when (col) {
                    Col.DATE -> if (dateSeen) col = Col.VALUE_DATE else dateSeen = true
                    Col.VALUE_DATE -> if (!dateSeen && index == grid.rows[headerRow].indexOfFirst { classifyHeader(it) == Col.VALUE_DATE }) {
                        // A lone "Value Date" with no plain date column IS the date.
                    }
                    Col.DEBIT -> if (debitSeen) col = Col.IGNORE else debitSeen = true
                    Col.CREDIT -> if (creditSeen) col = Col.IGNORE else creditSeen = true
                    Col.BALANCE -> if (balanceSeen) col = Col.IGNORE else balanceSeen = true
                    Col.AMOUNT -> if (amountSeen) col = Col.IGNORE else amountSeen = true
                    else -> {}
                }
                columns[index] = col
            }
            // No plain DATE but a VALUE_DATE: promote it.
            if (columns.none { it.value == Col.DATE }) {
                columns.entries.firstOrNull { it.value == Col.VALUE_DATE }?.setValue(Col.DATE)
            }
        } else {
            inferColumnsByContent(grid, columns)
        }

        val (dayFirst, ambiguous) = detectDateOrder(grid, headerRow, columns)
        return Mapping(headerRow, columns, dayFirst, ambiguous)
    }

    /** Headerless grids (some PDFs): classify columns by what their cells contain. */
    private fun inferColumnsByContent(grid: StatementGrid, columns: LinkedHashMap<Int, Col>) {
        val width = grid.rows.maxOfOrNull { it.size } ?: return
        val sample = grid.rows.take(200)
        var bestDate = -1
        var bestDateHits = 0
        val numericHits = IntArray(width)
        val textLen = LongArray(width)
        val nonEmpty = IntArray(width)
        for (row in sample) {
            for (c in 0 until width) {
                val cell = row.getOrNull(c)?.trim().orEmpty()
                if (cell.isEmpty()) continue
                nonEmpty[c]++
                if (parseAmount(cell) != null) numericHits[c]++
                textLen[c] += cell.length
            }
        }
        for (c in 0 until width) {
            val hits = sample.count { parseDate(it.getOrNull(c).orEmpty(), dayFirst = true) != null }
            if (hits > bestDateHits) {
                bestDateHits = hits
                bestDate = c
            }
        }
        if (bestDate < 0 || bestDateHits < 3) return
        columns[bestDate] = Col.DATE
        // Rightmost strongly-numeric column is the running balance; other numeric
        // columns are amounts; the longest text column is the narration.
        val numericCols = (0 until width).filter {
            it != bestDate && nonEmpty[it] > 0 && numericHits[it] * 10 >= nonEmpty[it] * 6
        }
        numericCols.lastOrNull()?.let { columns[it] = Col.BALANCE }
        numericCols.dropLast(1).forEach { columns[it] = Col.AMOUNT }
        if (numericCols.size >= 3) {
            // Three or more numeric columns: the classic debit/credit/balance trio.
            columns[numericCols[numericCols.size - 3]] = Col.DEBIT
            columns[numericCols[numericCols.size - 2]] = Col.CREDIT
        }
        (0 until width)
            .filter { it !in columns.keys }
            .maxByOrNull { textLen[it] }
            ?.let { columns[it] = Col.NARRATION }
    }

    private fun detectDateOrder(
        grid: StatementGrid,
        headerRow: Int,
        columns: Map<Int, Col>
    ): Pair<Boolean, Boolean> {
        val dateCol = columns.entries.firstOrNull { it.value == Col.DATE }?.key
            ?: return true to true
        var sawDayFirst = false
        var sawMonthFirst = false
        for (i in (headerRow + 1).coerceAtLeast(0) until grid.rows.size) {
            val raw = grid.rows[i].getOrNull(dateCol)?.trim().orEmpty()
            val parts = raw.split('/', '-', '.').map { it.trim() }
            if (parts.size == 3) {
                val a = parts[0].toIntOrNull()
                val b = parts[1].toIntOrNull()
                if (a != null && b != null && a <= 31 && b <= 31) {
                    if (a > 12) sawDayFirst = true
                    if (b > 12) sawMonthFirst = true
                }
            }
        }
        return when {
            sawDayFirst && !sawMonthFirst -> true to false
            sawMonthFirst && !sawDayFirst -> false to false
            else -> true to true  // no evidence either way — default Indian, offer the toggle
        }
    }

    // ── Dates ────────────────────────────────────────────────────────────────

    private val MONTHS = mapOf(
        "jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "may" to 5, "jun" to 6,
        "jul" to 7, "aug" to 8, "sep" to 9, "sept" to 9, "oct" to 10, "nov" to 11, "dec" to 12
    )

    /** Every way a bank writes a date → LocalDate, or null. */
    fun parseDate(raw: String, dayFirst: Boolean): LocalDate? {
        var s = raw.trim()
        if (s.isEmpty() || s.length > 30) return null
        // Strip a time-of-day tail: "01/08/2026 14:32:11" / "2026-08-01T09:00".
        s = s.substringBefore('T').trim()
        if (' ' in s && s.substringAfter(' ').firstOrNull()?.isDigit() == true &&
            ':' in s.substringAfter(' ')
        ) s = s.substringBefore(' ')
        s = s.removeSuffix(",").trim()

        // ISO — what the spreadsheet readers emit for real date cells.
        Regex("^(\\d{4})-(\\d{2})-(\\d{2})$").find(s)?.let { m ->
            return dateOf(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
        }

        // Month-name forms: 01 Aug 2026 · 01-Aug-26 · Aug 01, 2026 · 1 August 2026.
        run {
            val m = Regex("^(\\d{1,2})[\\s\\-/.]*([A-Za-z]{3,9})\\.?,?[\\s\\-/.]*(\\d{2,4})$").find(s)
            if (m != null) {
                val month = MONTHS[m.groupValues[2].lowercase().take(3)]
                    ?: MONTHS[m.groupValues[2].lowercase().take(4)]
                if (month != null) {
                    return dateOf(expandYear(m.groupValues[3]), month, m.groupValues[1].toInt())
                }
            }
            val m2 = Regex("^([A-Za-z]{3,9})\\.?[\\s\\-/.]*(\\d{1,2}),?[\\s\\-/.]*(\\d{2,4})$").find(s)
            if (m2 != null) {
                val month = MONTHS[m2.groupValues[1].lowercase().take(3)]
                if (month != null) {
                    return dateOf(expandYear(m2.groupValues[3]), month, m2.groupValues[2].toInt())
                }
            }
        }

        // Numeric triples with any of the three separators.
        val parts = s.split('/', '-', '.').map { it.trim() }
        if (parts.size == 3 && parts.all { it.isNotEmpty() && it.all(Char::isDigit) }) {
            val a = parts[0].toInt()
            val b = parts[1].toInt()
            val c = parts[2].toInt()
            return when {
                parts[0].length == 4 -> dateOf(a, b, c)                       // y/m/d
                a > 12 && a <= 31 -> dateOf(expandYear(parts[2]), b, a)       // definitely d/m/y
                b > 12 && b <= 31 -> dateOf(expandYear(parts[2]), a, b)      // definitely m/d/y
                dayFirst -> dateOf(expandYear(parts[2]), b, a)
                else -> dateOf(expandYear(parts[2]), a, b)
            }
        }
        return null
    }

    private fun expandYear(raw: String): Int {
        val y = raw.toIntOrNull() ?: return 0
        return when {
            raw.length == 4 -> y
            y < 70 -> 2000 + y
            else -> 1900 + y
        }
    }

    private fun dateOf(year: Int, month: Int, day: Int): LocalDate? =
        runCatching { LocalDate.of(year, month, day) }.getOrNull()
            ?.takeIf { it.year in 1970..2100 }

    fun toMillis(date: LocalDate): Long = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    // ── Amounts ──────────────────────────────────────────────────────────────

    data class Money(val value: Double, /** 'D', 'C' or null — a Dr/Cr suffix on the cell. */ val hint: Char?)

    private val EMPTY_AMOUNTS = setOf("", "-", "--", "—", "–", "na", "n a", "n.a.", "nil", "null")

    /** "₹1,23,456.78 Cr" → 123456.78 with hint 'C'. Null when the cell is not money. */
    fun parseAmount(raw: String): Money? {
        var s = raw.trim().replace(' ', ' ')
        if (s.lowercase() in EMPTY_AMOUNTS) return null
        if (s.length > 26) return null

        var hint: Char? = null
        val lower = s.lowercase()
        when {
            lower.endsWith("dr") || lower.endsWith("dr.") -> { hint = 'D'; s = s.dropLast(if (lower.endsWith(".")) 3 else 2).trim() }
            lower.endsWith("cr") || lower.endsWith("cr.") -> { hint = 'C'; s = s.dropLast(if (lower.endsWith(".")) 3 else 2).trim() }
        }

        var negative = false
        if (s.startsWith("(") && s.endsWith(")")) { negative = true; s = s.substring(1, s.length - 1) }
        if (s.endsWith("-")) { negative = true; s = s.dropLast(1) }
        if (s.startsWith("-")) { negative = true; s = s.drop(1) }
        if (s.startsWith("+")) s = s.drop(1)

        // Currency markers. "Rs." needs its own alternative: a trailing \b cannot
        // sit between the dot and a space, so \brs\.? silently leaves the dot
        // behind — and ".1000" parses as 0.1.
        s = s.replace(Regex("(?i)(₹|\\brs\\.|\\brs\\b|\\binr\\b)"), "")
            .replace(",", "").replace(" ", "").trim()
        if (s.isEmpty() || !s.first().isDigit() && s.first() != '.') return null
        if (s.count { it == '.' } > 1) return null
        if (s.any { !it.isDigit() && it != '.' }) return null

        val value = s.toDoubleOrNull() ?: return null
        return Money(if (negative) -value else value, hint)
    }

    // ── Narration splitting ──────────────────────────────────────────────────

    private val MODE_WORDS = setOf(
        "UPI", "IMPS", "NEFT", "RTGS", "ACH", "NACH", "ECS", "ATM", "ATW", "POS",
        "CSH", "CASH", "CHQ", "CHEQUE", "MB", "IB", "INB", "TPT", "BIL", "BILLPAY",
        "MMT", "CARD", "VPS", "IPS", "NETBANKING", "FT", "EMI"
    )

    private val CHANNEL_WORDS = setOf("P2M", "P2A", "P2P", "S2S", "COLLECT", "PAY")

    private val SKIP_WORDS = setOf("DR", "CR", "OTH", "REV")

    private val REMARK_MARKERS = setOf(
        "NO REM", "NOREM", "NO REMARK", "NO REMARKS", "NA", "N A", "PAYMENT", "PAYMENT FROM PHONE"
    )

    private val BANK_WORDS = setOf(
        "SBI", "SBIN", "HDFC", "ICICI", "ICIC", "AXIS", "UTIB", "YESB", "KOTAK", "KKBK",
        "PNB", "PUNB", "BOB", "BARB", "BOI", "CANARA", "CNRB", "UNION", "UBIN", "IDBI",
        "IBKL", "IDFC", "IDFB", "INDUSIND", "INDB", "RBL", "RATN", "FEDERAL", "FDRL",
        "UCO", "IOB", "PAYTM", "PYTM", "AIRTEL", "AIRP", "JIO", "JIOP", "HSBC", "CITI",
        "DBS", "SCB", "AUBL", "AU", "YBL", "YBS", "OKICICI", "OKHDFCBANK", "OKAXIS", "OKSBI",
        "IBL", "AXL", "APL"
    )

    private fun isRefLike(part: String): Boolean {
        val p = part.trim()
        if (p.length !in 6..25) return false
        if (p.all { it.isDigit() }) return true
        // UTRs: "SBIN524012345678", "AXISP00332…", "CMS123456".
        if (Regex("^[A-Za-z]{1,8}\\d{6,}$").matches(p)) return true
        val digits = p.count { it.isDigit() }
        return digits >= 8 && p.none { it == ' ' } && digits * 10 >= p.length * 6
    }

    private fun isBankLike(upper: String): Boolean {
        if (upper.length > 60) return false
        val tokens = upper.split(' ', '.', '-').filter { it.isNotEmpty() }
        if (tokens.any { it == "BANK" || it == "LTD" || it == "LIMITED" || it == "BNK" }) return true
        return tokens.size <= 3 && tokens.any { it in BANK_WORDS }
    }

    private fun looksLikeName(part: String): Boolean {
        val p = part.trim()
        if (p.length < 3 || p.length > 60) return false
        val letters = p.count { it.isLetter() }
        if (letters * 10 < p.length * 7) return false
        if (p.none { it.isLetter() && it.lowercaseChar() in "aeiou" } && p.length < 5) return false
        return p.all { it.isLetter() || it == ' ' || it == '.' || it == '\'' || it == '&' }
    }

    /**
     * Splits one narration into labelled fields by classifying each separated
     * part on *content*, not position — banks order these fields differently,
     * and a P2A row has fields a P2M row lacks. The user's example:
     *
     * `UPI/P2M/083743276468/RATHOD LAXMAN BHOJU /NO REM/YES BANK LIMITED YBS`
     *  mode/channel/reference/party              /remark/bank
     */
    fun splitNarration(narration: String): NarrationFields {
        val n = narration.trim()
        if (n.isEmpty()) return NarrationFields()

        val separator = when {
            n.count { it == '/' } >= 2 -> '/'
            n.count { it == '|' } >= 2 -> '|'
            n.count { it == '\\' } >= 2 -> '\\'
            else -> null
        }

        if (separator == null) {
            // Unslashed narration: pull what can be pulled safely.
            val tokens = n.split(' ', '-', ':').filter { it.isNotBlank() }
            val mode = tokens.firstOrNull { it.uppercase() in MODE_WORDS }?.uppercase()
            val reference = tokens.firstOrNull { isRefLike(it) }
                ?: Regex("\\b\\d{8,20}\\b").find(n)?.value
            return NarrationFields(mode = mode, reference = reference)
        }

        var mode: String? = null
        var channel: String? = null
        var reference: String? = null
        var party: String? = null
        var remark: String? = null
        var bank: String? = null

        val parts = n.split(separator).map { it.trim() }.filter { it.isNotEmpty() }
        for (part in parts) {
            val upper = part.uppercase()
            when {
                mode == null && upper in MODE_WORDS -> mode = upper
                channel == null && upper in CHANNEL_WORDS -> channel = upper
                upper in SKIP_WORDS -> {}
                reference == null && isRefLike(part) -> reference = part
                '@' in part -> if (party == null) party = part
                bank == null && isBankLike(upper) -> bank = part
                remark == null && upper in REMARK_MARKERS -> remark = part
                party == null && looksLikeName(part) -> party = part.trim()
                remark == null && part.any { it.isLetter() } -> remark = part
                else -> {}
            }
        }
        return NarrationFields(mode, channel, reference, party, remark, bank)
    }

    // ── Categories ───────────────────────────────────────────────────────────

    private val CATEGORY_RULES: List<Pair<String, List<String>>> = listOf(
        "Salary" to listOf("SALARY"),
        "Interest" to listOf("INT.PD", "INT PAID", "INTEREST", "INT.CR", "INT CREDIT", "INT.COLL"),
        "Refund" to listOf("REFUND", "REVERSAL", "REV-", "RET-"),
        "Cash" to listOf("ATM", "ATW", "CASH WDL", "CASH DEP", "CSH", "CDM", "SELF CHEQUE"),
        "Charges" to listOf("CHRG", "CHARGE", " FEE", "GST", "SMS CHG", " AMC", "PENALTY", "MIN BAL", "DECLINE"),
        "Loan / EMI" to listOf(" EMI", "EMI ", "LOAN", "BAJAJ FIN"),
        "Investments" to listOf(
            "ZERODHA", "GROWW", "UPSTOX", "MUTUAL FUND", "INDIAN CLEARING", "ICCL",
            "CAMS", " SIP", "SIP ", "ANGEL ONE", "KFIN", "NSDL", "CDSL", "BSE", "NSE"
        ),
        "Food" to listOf("SWIGGY", "ZOMATO", "DOMINO", "MCDONALD", "KFC", "PIZZA", "RESTAURANT", "HOTEL"),
        "Groceries" to listOf(
            "BIGBASKET", "BLINKIT", "ZEPTO", "DMART", "D MART", "JIOMART", "INSTAMART",
            "GROFERS", "KIRANA", "SUPERMARKET", "GROCER"
        ),
        "Shopping" to listOf("AMAZON", "FLIPKART", "MYNTRA", "AJIO", "MEESHO", "NYKAA", "SNAPDEAL"),
        "Travel" to listOf(
            "IRCTC", "UBER", "OLA", "RAPIDO", "REDBUS", "MAKEMYTRIP", "GOIBIBO",
            "INDIGO", "AIR INDIA", "SPICEJET", "VISTARA", "YATRA", "EASEMYTRIP"
        ),
        "Fuel" to listOf(
            "PETROL", "FUEL", "HPCL", "IOCL", "BPCL", "INDIAN OIL", "BHARAT PETRO",
            "HP PAY", "SHELL", "FILLING STATION"
        ),
        "Bills / Recharge" to listOf(
            "RECHARGE", "AIRTEL", "VODAFONE", "VI MOBILE", "BSNL", "ELECTRICITY",
            "MSEDCL", "BESCOM", "TNEB", "TORRENT POW", "TATA POWER", "ADANI ELE",
            "BROADBAND", "DTH", "BILLDESK", "BBPS", "FASTAG"
        ),
        "Health" to listOf(
            "PHARMACY", "MEDICAL", "HOSPITAL", "APOLLO", "MEDPLUS", "NETMEDS",
            "PHARMEASY", "CLINIC", "DIAGNOST", "1MG"
        ),
        "Entertainment" to listOf(
            "NETFLIX", "SPOTIFY", "HOTSTAR", "PRIME VIDEO", "BOOKMYSHOW", "SONYLIV",
            "ZEE5", "PVR", "INOX"
        ),
        "Insurance" to listOf("INSURANCE", "POLICY", "PREMIUM", "HDFC LIFE", "SBI LIFE", "ICICI PRU", "LIC OF INDIA"),
        "Rent" to listOf("RENT")
    )

    fun categorize(narration: String, fields: NarrationFields, isCredit: Boolean): String {
        val haystack = " " + narration.uppercase() + " " + (fields.party?.uppercase() ?: "") + " "
        for ((category, needles) in CATEGORY_RULES) {
            for (needle in needles) {
                val hit = if (needle.length <= 4 && needle.all { it.isLetter() })
                    haystack.contains(Regex("\\b${Regex.escape(needle)}\\b"))
                else haystack.contains(needle)
                if (hit) return category
            }
        }
        return when {
            fields.channel == "P2M" -> "Merchant"
            fields.channel == "P2A" || fields.channel == "P2P" -> "Person"
            fields.mode in setOf("NEFT", "RTGS", "IMPS", "TPT", "FT") -> "Transfer"
            isCredit -> "Received"
            else -> BankVocab.CAT_OTHER
        }
    }

    // ── Fingerprints ─────────────────────────────────────────────────────────

    /**
     * The row's stable identity: date, amount, direction, reference, the
     * normalised narration and the running balance. Two genuinely different
     * transactions can only collide when the statement itself cannot tell them
     * apart either (no balance column and identical text) — [parse] suffixes
     * those `#2`, `#3` in statement order so both survive, and a re-import of
     * the same file reproduces the same suffixes and still deduplicates.
     *
     * The book is deliberately NOT part of the hash — merge identity is
     * `(book, fingerprint)`, so renaming a book never rewrites fingerprints.
     */
    fun fingerprintOf(
        dateMillis: Long,
        amount: Double,
        isCredit: Boolean,
        reference: String?,
        narration: String,
        balance: Double?
    ): String {
        val basis = listOf(
            dateMillis.toString(),
            String.format(java.util.Locale.ROOT, "%.2f", amount),
            if (isCredit) "C" else "D",
            reference?.trim()?.uppercase() ?: "",
            narration.trim().uppercase().replace(Regex("\\s+"), " "),
            balance?.let { String.format(java.util.Locale.ROOT, "%.2f", it) } ?: ""
        ).joinToString(" ")
        val digest = MessageDigest.getInstance("SHA-256").digest(basis.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(40)
    }

    // ── The parse ────────────────────────────────────────────────────────────

    private val OPENING_BALANCE = Regex("(?i)\\b(b\\s*/\\s*f|brought\\s+forward|opening\\s+balance|balance\\s+b\\s*/?\\s*f|balance\\s+forward)\\b")
    private val CLOSING_NOISE = Regex("(?i)\\b(closing\\s+balance|total|grand\\s+total|statement\\s+summary|c\\s*/\\s*f|carried\\s+forward|end\\s+of\\s+statement)\\b")

    fun parse(grid: StatementGrid, mapping: Mapping, extraction: Extraction): ParseOutcome {
        val warnings = ArrayList<String>()
        var skipped = 0

        val dateCol = mapping.columns.entries.firstOrNull { it.value == Col.DATE }?.key
        val narrationCols = mapping.columns.entries.filter { it.value == Col.NARRATION }.map { it.key }
        val refCol = mapping.columns.entries.firstOrNull { it.value == Col.REFERENCE }?.key
        val debitCol = mapping.columns.entries.firstOrNull { it.value == Col.DEBIT }?.key
        val creditCol = mapping.columns.entries.firstOrNull { it.value == Col.CREDIT }?.key
        val amountCol = mapping.columns.entries.firstOrNull { it.value == Col.AMOUNT }?.key
        val drcrCol = mapping.columns.entries.firstOrNull { it.value == Col.DRCR }?.key
        val balanceCol = mapping.columns.entries.firstOrNull { it.value == Col.BALANCE }?.key

        if (dateCol == null) {
            return ParseOutcome(
                emptyList(), grid.rows.size,
                listOf("No date column could be identified — map it by hand."),
                null, suggestBook(grid, mapping.headerRow)
            )
        }

        // Phase 1 — raw rows, merging wrapped-narration continuation lines.
        class Raw(
            val rowIndex: Int,
            val date: LocalDate,
            var narration: String,
            val reference: String?,
            var amount: Double?,
            var isCredit: Boolean?,
            val balance: Double?
        )

        val raw = ArrayList<Raw>()
        var openingBalance: Double? = null
        val start = (mapping.headerRow + 1).coerceAtLeast(0)

        for (i in start until grid.rows.size) {
            val row = grid.rows[i]
            if (row.all { it.isBlank() }) continue
            if (i != mapping.headerRow && headerRowScore(row) >= 3) continue  // repeated page header

            val narration = narrationCols.joinToString(" / ") { row.getOrNull(it).orEmpty().trim() }
                .trim().trim('/').trim()
            val date = parseDate(row.getOrNull(dateCol).orEmpty(), mapping.dayFirst)
            val debit = debitCol?.let { parseAmount(row.getOrNull(it).orEmpty()) }
            val credit = creditCol?.let { parseAmount(row.getOrNull(it).orEmpty()) }
            val single = amountCol?.let { parseAmount(row.getOrNull(it).orEmpty()) }
            val balance = balanceCol?.let { parseAmount(row.getOrNull(it).orEmpty())?.value }

            if (date == null) {
                val hasMoney = (debit?.value ?: 0.0) != 0.0 || (credit?.value ?: 0.0) != 0.0 ||
                    (single?.value ?: 0.0) != 0.0
                if (!hasMoney && narration.isNotBlank() && raw.isNotEmpty() &&
                    !CLOSING_NOISE.containsMatchIn(narration)
                ) {
                    // A wrapped narration: PDF layouts continue long text on the
                    // next visual line with every other column empty.
                    raw.last().narration = (raw.last().narration + " " + narration).trim()
                } else {
                    skipped++
                }
                continue
            }

            if (OPENING_BALANCE.containsMatchIn(narration)) {
                openingBalance = balance ?: single?.value
                continue
            }

            // Direction, in order of authority: an explicit Dr/Cr column, the
            // debit/credit pair, a signed or suffixed single amount.
            var amount: Double? = null
            var isCredit: Boolean? = null
            val drcr = drcrCol?.let { row.getOrNull(it)?.trim()?.uppercase() }
            val debitValue = debit?.value?.takeIf { kotlin.math.abs(it) > 0.004 }
            val creditValue = credit?.value?.takeIf { kotlin.math.abs(it) > 0.004 }
            when {
                debitValue != null && creditValue != null -> {
                    warnings.add("Row ${i + 1} has both a debit and a credit; the larger was used.")
                    if (kotlin.math.abs(debitValue) >= kotlin.math.abs(creditValue)) {
                        amount = kotlin.math.abs(debitValue); isCredit = false
                    } else {
                        amount = kotlin.math.abs(creditValue); isCredit = true
                    }
                }
                debitValue != null -> { amount = kotlin.math.abs(debitValue); isCredit = false }
                creditValue != null -> { amount = kotlin.math.abs(creditValue); isCredit = true }
                single != null -> {
                    amount = kotlin.math.abs(single.value)
                    isCredit = when {
                        drcr != null && drcr.startsWith("C") -> true
                        drcr != null && drcr.startsWith("D") -> false
                        single.hint == 'C' -> true
                        single.hint == 'D' -> false
                        single.value < 0 -> false
                        single.value > 0 && drcr == null && single.hint == null -> null  // balance decides
                        else -> null
                    }
                }
            }
            if (amount == null || amount < 0.004) {
                skipped++
                continue
            }

            raw.add(
                Raw(
                    rowIndex = i,
                    date = date,
                    narration = narration,
                    reference = refCol?.let { row.getOrNull(it)?.trim()?.takeIf { r -> r.isNotEmpty() && r != "-" } },
                    amount = amount,
                    isCredit = isCredit,
                    balance = balance
                )
            )
        }

        // Phase 2 — resolve unknown directions from the running balance, then
        // verify the balances that exist.
        val chronological = if (raw.size >= 2 && raw.first().date > raw.last().date) raw.reversed() else raw
        var previous: Double? = openingBalance
        val agrees = HashMap<Raw, Boolean?>()
        for (entry in chronological) {
            val balance = entry.balance
            if (balance != null && previous != null) {
                val delta = balance - previous
                if (entry.isCredit == null) {
                    entry.isCredit = delta >= 0
                }
                val signed = if (entry.isCredit == true) entry.amount!! else -entry.amount!!
                agrees[entry] = kotlin.math.abs(previous + signed - balance) < 0.05
            } else {
                agrees[entry] = null
            }
            previous = when {
                balance != null -> balance
                previous != null && entry.isCredit != null ->
                    previous + (if (entry.isCredit == true) entry.amount!! else -entry.amount!!)
                else -> null
            }
        }

        val unresolved = raw.count { it.isCredit == null }
        if (unresolved > 0) {
            warnings.add("$unresolved row(s) had no debit/credit indication and were skipped.")
        }

        // Phase 3 — fields, category, fingerprints (with deterministic suffixes).
        val fingerprintCounts = HashMap<String, Int>()
        val rows = raw.filter { it.isCredit != null }.map { entry ->
            val fields = splitNarration(entry.narration)
            val reference = when {
                !extraction.reference -> null
                entry.reference != null -> entry.reference
                else -> fields.reference
            }
            val isCredit = entry.isCredit!!
            val amount = entry.amount!!
            val base = fingerprintOf(
                toMillis(entry.date), amount, isCredit,
                entry.reference ?: fields.reference, entry.narration, entry.balance
            )
            val n = fingerprintCounts.merge(base, 1, Int::plus)!!
            val fingerprint = if (n == 1) base else "$base#$n"

            ParsedRow(
                rowIndex = entry.rowIndex,
                dateIso = entry.date.toString(),
                dateMillis = toMillis(entry.date),
                narration = entry.narration,
                amount = amount,
                isCredit = isCredit,
                balance = entry.balance,
                reference = reference,
                mode = if (extraction.mode) fields.mode else null,
                channel = if (extraction.channel) fields.channel else null,
                party = if (extraction.party) fields.party else null,
                remark = if (extraction.remark) fields.remark else null,
                bankName = if (extraction.bank) fields.bankName else null,
                category = if (extraction.autoCategory) categorize(entry.narration, fields, isCredit)
                else BankVocab.CAT_OTHER,
                fingerprint = fingerprint,
                balanceAgrees = agrees[entry]
            )
        }

        skipped += unresolved
        val disagreements = rows.count { it.balanceAgrees == false }
        if (disagreements > 0) {
            warnings.add(
                "$disagreements row(s) do not reconcile against the balance column — check them before importing."
            )
        }

        return ParseOutcome(rows, skipped, warnings, openingBalance, suggestBook(grid, mapping.headerRow))
    }

    /** "Account No: XXXXXX1234" in the preamble → "A/c ••1234". */
    private fun suggestBook(grid: StatementGrid, headerRow: Int): String? {
        val limit = if (headerRow > 0) headerRow else minOf(grid.rows.size, 15)
        val preamble = grid.rows.take(limit).joinToString("\n") { it.joinToString("  ") }
        val m = Regex("(?i)a/?c(?:count)?\\s*(?:no|number|#)?\\s*[.:\\-]?\\s*([Xx*•]*\\d{4,})")
            .find(preamble) ?: return null
        val digits = m.groupValues[1].filter { it.isDigit() }
        if (digits.length < 4) return null
        return "A/c ••" + digits.takeLast(4)
    }
}
