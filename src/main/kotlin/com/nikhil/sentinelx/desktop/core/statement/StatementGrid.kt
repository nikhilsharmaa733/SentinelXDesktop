package com.nikhil.sentinelx.desktop.core.statement

/*
 * ⚠️ MIRRORED PACKAGE. Every file in `core/statement` is byte-identical to
 * `SentinelX/app/src/main/java/com/nikhil/sentinelx/statement/` apart from the
 * package line and this note — the same statement file must parse to the same
 * transactions on the phone and on the desktop, or a re-import on the other
 * device duplicates rows instead of deduplicating them (the fingerprints would
 * differ). A `diff` of the two directories is the check. Change both in the
 * same pair of commits, as with VaultMerge and the autofill matcher.
 *
 * The package is pure Kotlin over java.base (zip, security, crypto) — no
 * javax.xml, no Android classes, no third-party parser — precisely so that it
 * CAN be identical on both platforms and adds nothing to the phone's merged
 * manifest. Do not add a dependency here; that is the entire design.
 */

/**
 * A bank statement reduced to a rectangular grid of strings — the common shape
 * every reader (CSV, XLSX, XLS, HTML, ODS, PDF) produces and the one
 * [StatementParse] consumes.
 *
 * Cells hold the *display-normalised* value: spreadsheet date cells arrive as
 * ISO `yyyy-MM-dd` (the reader resolves Excel serials so the parser never has
 * to guess what 45142 means), numbers as plain decimal strings, everything
 * else as trimmed text.
 */
data class StatementGrid(
    val rows: List<List<String>>,
    /** Human-readable source format, e.g. "XLSX", "PDF (decrypted)". */
    val format: String,
    /** Non-fatal oddities worth surfacing in the import wizard. */
    val warnings: List<String> = emptyList(),
    /**
     * The file's own name, stamped by the dispatcher. Banks name their exports
     * distinctively ("AcctStatement_…", "IDFCFIRSTBankstatement_…"), which is
     * often a stronger issuer signal than the sheet text — a letterhead can be
     * a logo image the readers never see.
     */
    val fileName: String = ""
)

/** The file could not be read at all — wrong format, truncated, unsupported. */
class StatementReadException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The PDF is encrypted and [password] was absent or wrong. Ask and retry. */
class StatementPasswordRequired(message: String = "This PDF is password-protected.") : Exception(message)

// ── Minimal XML scanning ─────────────────────────────────────────────────────
//
// XLSX and ODS are zip archives of machine-generated XML. A full XML parser is
// javax.xml — a JDK *module* the desktop build strips and a different API on
// Android — so the two readers share this deliberately small scanner instead.
// It understands exactly what spreadsheet writers emit: tags, attributes,
// entities, CDATA, comments. It is not a general XML parser and must not grow
// into one.

internal object MiniXml {

    /** One tag as scanned: `<name attr="v">`, `</name>` or `<name/>`. */
    data class Tag(
        val name: String,
        val attrs: Map<String, String>,
        val closing: Boolean,
        val selfClosing: Boolean,
        /** Index of `<`. */
        val start: Int,
        /** Index just past `>`. */
        val end: Int
    )

    /** Local name — `x:row` and `row` are the same element to us. */
    private fun local(name: String): String = name.substringAfterLast(':')

    /**
     * Scans for the next element tag at or after [from]. Skips comments,
     * CDATA, processing instructions and doctypes. Null at end of input.
     */
    fun nextTag(text: String, from: Int): Tag? {
        var i = from
        while (true) {
            i = text.indexOf('<', i)
            if (i < 0 || i + 1 >= text.length) return null
            when {
                text.startsWith("<!--", i) -> {
                    val close = text.indexOf("-->", i + 4)
                    if (close < 0) return null
                    i = close + 3
                }
                text.startsWith("<![CDATA[", i) -> {
                    val close = text.indexOf("]]>", i + 9)
                    if (close < 0) return null
                    i = close + 3
                }
                text.startsWith("<?", i) || text.startsWith("<!", i) -> {
                    val close = text.indexOf('>', i)
                    if (close < 0) return null
                    i = close + 1
                }
                else -> return parseTag(text, i)
            }
        }
    }

    private fun parseTag(text: String, start: Int): Tag? {
        val gt = text.indexOf('>', start)
        if (gt < 0) return null
        var i = start + 1
        val closing = text[i] == '/'
        if (closing) i++
        val nameStart = i
        while (i < gt && !text[i].isWhitespace() && text[i] != '/' && text[i] != '>') i++
        val name = local(text.substring(nameStart, i))
        val selfClosing = text[gt - 1] == '/'
        val attrEnd = if (selfClosing) gt - 1 else gt

        var attrs: MutableMap<String, String>? = null
        while (i < attrEnd) {
            while (i < attrEnd && text[i].isWhitespace()) i++
            if (i >= attrEnd) break
            val eq = text.indexOf('=', i)
            if (eq < 0 || eq > attrEnd) break
            val attrName = local(text.substring(i, eq).trim())
            var v = eq + 1
            while (v < attrEnd && text[v].isWhitespace()) v++
            if (v >= attrEnd) break
            val quote = text[v]
            if (quote != '"' && quote != '\'') break
            val close = text.indexOf(quote, v + 1)
            if (close < 0 || close > gt) break
            if (attrs == null) attrs = LinkedHashMap()
            attrs[attrName] = decode(text.substring(v + 1, close))
            i = close + 1
        }
        return Tag(name, attrs ?: emptyMap(), closing, selfClosing, start, gt + 1)
    }

    /**
     * The character content between a tag's `>` (at [from]) and its matching
     * close, honouring nesting of the same element name. Returns the raw inner
     * text (tags included) and the index just past the closing tag.
     */
    fun innerOf(text: String, name: String, from: Int): Pair<String, Int>? {
        var depth = 1
        var i = from
        while (true) {
            val tag = nextTag(text, i) ?: return null
            if (tag.name == name) {
                if (tag.closing) {
                    depth--
                    if (depth == 0) return text.substring(from, tag.start) to tag.end
                } else if (!tag.selfClosing) depth++
            }
            i = tag.end
        }
    }

    /** All text content of the element body [inner], tags stripped, entities decoded. */
    fun textOf(inner: String): String {
        if ('<' !in inner) return decode(inner)
        val sb = StringBuilder()
        var i = 0
        while (i < inner.length) {
            val lt = inner.indexOf('<', i)
            if (lt < 0) {
                sb.append(decode(inner.substring(i)))
                break
            }
            sb.append(decode(inner.substring(i, lt)))
            val tag = nextTag(inner, lt) ?: break
            i = tag.end
        }
        return sb.toString()
    }

    /** XML entity decoding: the five named entities plus numeric references. */
    fun decode(text: String): String {
        if ('&' !in text) return text
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val amp = text.indexOf('&', i)
            if (amp < 0) {
                sb.append(text, i, text.length)
                break
            }
            sb.append(text, i, amp)
            val semi = text.indexOf(';', amp + 1)
            if (semi < 0 || semi - amp > 10) {
                sb.append('&')
                i = amp + 1
                continue
            }
            val entity = text.substring(amp + 1, semi)
            val decoded: String? = when {
                entity == "amp" -> "&"
                entity == "lt" -> "<"
                entity == "gt" -> ">"
                entity == "quot" -> "\""
                entity == "apos" -> "'"
                entity.startsWith("#x") || entity.startsWith("#X") ->
                    entity.drop(2).toIntOrNull(16)?.let { cp -> runCatching { String(Character.toChars(cp)) }.getOrNull() }
                entity.startsWith("#") ->
                    entity.drop(1).toIntOrNull()?.let { cp -> runCatching { String(Character.toChars(cp)) }.getOrNull() }
                else -> null
            }
            if (decoded != null) {
                sb.append(decoded)
                i = semi + 1
            } else {
                sb.append('&')
                i = amp + 1
            }
        }
        return sb.toString()
    }
}

// ── Little-endian byte reading ───────────────────────────────────────────────
//
// XLS (OLE2 + BIFF) is a little-endian binary format; this cursor keeps the
// arithmetic in one place. Bounds failures throw IndexOutOfBounds, which the
// dispatcher turns into a clean StatementReadException.

internal class ByteCursor(val data: ByteArray, var pos: Int = 0) {
    fun u8(): Int = data[pos++].toInt() and 0xFF
    fun u16(): Int = u8() or (u8() shl 8)
    fun i32(): Int = u16() or (u16() shl 16)
    fun u32(): Long = i32().toLong() and 0xFFFFFFFFL
    fun f64(): Double {
        var bits = 0L
        for (shift in 0 until 64 step 8) bits = bits or ((data[pos++].toLong() and 0xFF) shl shift)
        return Double.fromBits(bits)
    }
    fun bytes(n: Int): ByteArray = data.copyOfRange(pos, pos + n).also { pos += n }
    fun skip(n: Int) { pos += n }
    val remaining: Int get() = data.size - pos
}

// ── Excel serial dates ───────────────────────────────────────────────────────

internal object ExcelDates {

    /**
     * Serial → ISO date string. Excel's epoch is 1899-12-30 (which absorbs the
     * fictitious 1900-02-29 for every serial ≥ 61 — all modern dates); the 1904
     * system (old Mac exports, flagged in the workbook) starts at 1904-01-01.
     * Time-of-day fractions are dropped: a statement's date column is a day.
     */
    fun serialToIso(serial: Double, date1904: Boolean): String? {
        if (serial < 1 || serial > 300000) return null
        val days = serial.toLong()
        val epoch = if (date1904) java.time.LocalDate.of(1904, 1, 1)
        else java.time.LocalDate.of(1899, 12, 30)
        return runCatching { epoch.plusDays(days).toString() }.getOrNull()
    }

    /** Builtin numFmtIds Excel treats as dates (not time-only). */
    private val builtinDateIds = setOf(
        14, 15, 16, 17, 22,          // classic date / datetime
        27, 28, 29, 30, 31, 36, 50, 57, 58,  // locale date variants
        45, 46, 47                    // mm:ss style — kept out below by isTimeOnly
    )

    private val timeOnlyIds = setOf(18, 19, 20, 21, 32, 33, 34, 35, 45, 46, 47)

    fun isDateFormat(numFmtId: Int, formatCode: String?): Boolean {
        if (numFmtId in timeOnlyIds) return false
        if (numFmtId in builtinDateIds) return true
        val code = formatCode ?: return false
        // Strip quoted literals and [] sections (colours, locale prefixes), then
        // look for day/month/year tokens. h/m/s alone is a clock, not a date.
        val bare = code.replace(Regex("\\[[^]]*]"), "").replace(Regex("\"[^\"]*\""), "")
        if (bare.equals("general", ignoreCase = true)) return false
        val hasDate = bare.any { it == 'd' || it == 'D' || it == 'y' || it == 'Y' } ||
            Regex("(?i)m{3,}").containsMatchIn(bare)
        val hasMonth = bare.any { it == 'm' || it == 'M' }
        return hasDate || (hasMonth && !bare.any { it == 'h' || it == 'H' || it == 's' || it == 'S' })
    }

    /** Renders a numeric cell the way the parser wants to see it. */
    fun renderNumber(value: Double): String =
        if (value == value.toLong().toDouble() && kotlin.math.abs(value) < 1e15) value.toLong().toString()
        else value.toString()
}
