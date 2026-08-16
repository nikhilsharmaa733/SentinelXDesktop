package com.nikhil.sentinelx.desktop.core.statement

import java.security.MessageDigest
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/*
 * ⚠️ MIRRORED FILE — see StatementGrid.kt. Byte-identical to the Android copy
 * apart from the package line.
 */

/**
 * Minimal PDF reader: cross-reference tables and streams, object streams,
 * Flate/ASCIIHex/ASCII85 filters, the page tree, font ToUnicode CMaps — and
 * the standard security handler (RC4-40/128, AES-128, AES-256), because Indian
 * banks mail statements with a password on them and "remove the password
 * first" is not an answer this app should give.
 *
 * It reads digitally-generated statements. It does not OCR scans, render
 * graphics, or validate signatures. Anything structurally unexpected throws
 * [StatementReadException]; a wrong/missing password throws
 * [StatementPasswordRequired] so the UI can ask and retry.
 */
internal class PdfDocument private constructor(
    private val bytes: ByteArray,
    private val xref: Map<Int, XrefEntry>,
    private val trailer: MutableMap<String, Any?>,
    private val crypto: PdfCrypto?
) {

    // ── Public surface ───────────────────────────────────────────────────────

    class Page(val content: ByteArray, val fonts: Map<String, Font>)
    class Font(val toUnicode: Map<Int, String>?, val twoByte: Boolean)

    fun pages(): List<Page> {
        val root = dict(resolve(trailer["Root"])) ?: throw StatementReadException("PDF has no document catalog.")
        val pagesRoot = dict(resolve(root["Pages"])) ?: throw StatementReadException("PDF has no page tree.")
        val pageDicts = ArrayList<Map<String, Any?>>()
        collectPages(pagesRoot, pageDicts, HashSet(), inheritedResources = null, depth = 0)

        return pageDicts.mapNotNull { page ->
            val content = contentOf(page) ?: return@mapNotNull null
            Page(content, fontsOf(page))
        }
    }

    private fun collectPages(
        node: Map<String, Any?>,
        out: MutableList<Map<String, Any?>>,
        seen: MutableSet<Int>,
        inheritedResources: Any?,
        depth: Int
    ) {
        if (depth > 64 || out.size > 2000) return
        val resources = node["Resources"] ?: inheritedResources
        when (name(node["Type"])) {
            "Page" -> out.add(if (node["Resources"] == null && resources != null) node + ("Resources" to resources) else node)
            else -> {
                val kids = list(resolve(node["Kids"])) ?: return
                for (kid in kids) {
                    val ref = kid as? Ref
                    if (ref != null && !seen.add(ref.num)) continue
                    val child = dict(resolve(kid)) ?: continue
                    collectPages(child, out, seen, resources, depth + 1)
                }
            }
        }
    }

    private fun contentOf(page: Map<String, Any?>): ByteArray? {
        val contents = resolve(page["Contents"]) ?: return null
        val streams: List<Stream> = when (contents) {
            is Stream -> listOf(contents)
            is List<*> -> contents.mapNotNull { resolve(it) as? Stream }
            else -> return null
        }
        if (streams.isEmpty()) return null
        val out = java.io.ByteArrayOutputStream()
        for (s in streams) {
            out.write(decodeStream(s))
            out.write('\n'.code)
        }
        return out.toByteArray()
    }

    private fun fontsOf(page: Map<String, Any?>): Map<String, Font> {
        val resources = dict(resolve(page["Resources"])) ?: return emptyMap()
        val fontDicts = dict(resolve(resources["Font"])) ?: return emptyMap()
        val fonts = HashMap<String, Font>()
        for ((key, value) in fontDicts) {
            val font = dict(resolve(value)) ?: continue
            val subtype = name(font["Subtype"])
            val twoByte = subtype == "Type0"
            val cmap = (resolve(font["ToUnicode"]) as? Stream)
                ?.let { runCatching { parseCMap(decodeStream(it).toString(Charsets.ISO_8859_1)) }.getOrNull() }
            fonts[key] = Font(cmap, twoByte)
        }
        return fonts
    }

    /** `beginbfchar`/`beginbfrange` → code → UTF-16BE text. */
    private fun parseCMap(text: String): Map<Int, String> {
        val map = HashMap<Int, String>()
        fun hexToInt(h: String): Int? = h.toIntOrNull(16)
        fun hexToString(h: String): String {
            val sb = StringBuilder()
            var i = 0
            while (i + 4 <= h.length) {
                sb.append(h.substring(i, i + 4).toInt(16).toChar())
                i += 4
            }
            if (i + 2 <= h.length && sb.isEmpty()) sb.append(h.substring(i, i + 2).toInt(16).toChar())
            return sb.toString()
        }

        val hexToken = Regex("<([0-9A-Fa-f]+)>")
        var pos = 0
        while (true) {
            val charBlock = text.indexOf("beginbfchar", pos)
            val rangeBlock = text.indexOf("beginbfrange", pos)
            if (charBlock < 0 && rangeBlock < 0) break
            if (charBlock >= 0 && (rangeBlock < 0 || charBlock < rangeBlock)) {
                val end = text.indexOf("endbfchar", charBlock).takeIf { it > 0 } ?: break
                val tokens = hexToken.findAll(text.substring(charBlock, end)).map { it.groupValues[1] }.toList()
                var i = 0
                while (i + 1 < tokens.size) {
                    hexToInt(tokens[i])?.let { code -> map[code] = hexToString(tokens[i + 1]) }
                    i += 2
                }
                pos = end + 9
            } else {
                val end = text.indexOf("endbfrange", rangeBlock).takeIf { it > 0 } ?: break
                val body = text.substring(rangeBlock, end)
                // Two shapes: <lo> <hi> <dst>  |  <lo> <hi> [<d1> <d2> …]
                var i = 0
                val entry = Regex("<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f]+)>\\s*(\\[[^\\]]*]|<[0-9A-Fa-f]+>)")
                for (m in entry.findAll(body)) {
                    val lo = hexToInt(m.groupValues[1]) ?: continue
                    val hi = hexToInt(m.groupValues[2]) ?: continue
                    if (hi < lo || hi - lo > 65535) continue
                    val dst = m.groupValues[3]
                    if (dst.startsWith("[")) {
                        val items = hexToken.findAll(dst).map { it.groupValues[1] }.toList()
                        items.forEachIndexed { offset, h ->
                            if (lo + offset <= hi) map[lo + offset] = hexToString(h)
                        }
                    } else {
                        val base = dst.trim('<', '>')
                        val baseVal = hexToInt(base) ?: continue
                        val width = base.length
                        for (code in lo..hi) {
                            val v = (baseVal + (code - lo)).toString(16).padStart(width, '0')
                            map[code] = hexToString(v)
                        }
                    }
                    i = m.range.last
                }
                pos = end + 10
            }
        }
        return map
    }

    // ── Objects ──────────────────────────────────────────────────────────────

    data class Ref(val num: Int, val gen: Int)
    class Name(val value: String)
    class Stream(val dict: Map<String, Any?>, val rawStart: Int, val rawLength: Int, val objNum: Int, val objGen: Int, val fromObjStm: Boolean) {
        var cachedRaw: ByteArray? = null
    }

    sealed class XrefEntry {
        class Direct(val offset: Int) : XrefEntry()
        class InStream(val streamObj: Int, val index: Int) : XrefEntry()
    }

    private val cache = HashMap<Int, Any?>()
    private val loading = HashSet<Int>()

    fun resolve(value: Any?): Any? = when (value) {
        is Ref -> getObject(value.num)
        else -> value
    }

    private fun dict(value: Any?): Map<String, Any?>? = @Suppress("UNCHECKED_CAST") when (value) {
        is Map<*, *> -> value as Map<String, Any?>
        is Stream -> value.dict
        else -> null
    }

    private fun list(value: Any?): List<Any?>? = value as? List<Any?>
    private fun name(value: Any?): String? = (resolve(value) as? Name)?.value
    private fun int(value: Any?): Int? = when (val v = resolve(value)) {
        is Long -> v.toInt(); is Double -> v.toInt(); else -> null
    }

    private fun getObject(num: Int): Any? {
        cache[num]?.let { return it }
        if (num in loading) return null
        val entry = xref[num] ?: return null
        loading.add(num)
        val value = try {
            when (entry) {
                is XrefEntry.Direct -> parseObjectAt(entry.offset, num)
                is XrefEntry.InStream -> parseFromObjStm(entry.streamObj, entry.index)
            }
        } catch (e: Exception) {
            null
        } finally {
            loading.remove(num)
        }
        cache[num] = value
        return value
    }

    private fun parseObjectAt(offset: Int, expectedNum: Int): Any? {
        if (offset < 0 || offset >= bytes.size) return null
        val lexer = PdfLexer(bytes, offset)
        val num = (lexer.next() as? Long)?.toInt() ?: return null
        val gen = (lexer.next() as? Long)?.toInt() ?: return null
        if (lexer.next() != PdfLexer.KW_OBJ) return null
        val value = lexer.readValue() ?: return null
        // A stream keyword after the dict makes this a stream object.
        val save = lexer.pos
        if (lexer.next() == PdfLexer.KW_STREAM && value is Map<*, *>) {
            var dataStart = lexer.pos
            if (dataStart < bytes.size && bytes[dataStart] == '\r'.code.toByte()) dataStart++
            if (dataStart < bytes.size && bytes[dataStart] == '\n'.code.toByte()) dataStart++
            @Suppress("UNCHECKED_CAST") val d = value as Map<String, Any?>
            var length = int(d["Length"]) ?: -1
            if (length < 0 || dataStart + length > bytes.size || !endstreamNear(dataStart + length)) {
                length = scanForEndstream(dataStart) ?: return null
            }
            return Stream(d, dataStart, length, num, gen, fromObjStm = false)
        }
        lexer.pos = save
        // Per-object decryption keys use the object's own header numbers, which a
        // stale xref can put out of step with the number we looked up.
        return decryptStrings(value, num, gen)
    }

    private fun endstreamNear(at: Int): Boolean {
        var i = at
        var slack = 0
        while (i < bytes.size && slack < 4) {
            val b = bytes[i]
            if (b == '\r'.code.toByte() || b == '\n'.code.toByte() || b == ' '.code.toByte()) { i++; slack++; continue }
            break
        }
        return matchesAt(i, "endstream")
    }

    private fun scanForEndstream(from: Int): Int? {
        var i = from
        while (i < bytes.size - 9) {
            if (matchesAt(i, "endstream")) {
                var end = i
                while (end > from && (bytes[end - 1] == '\n'.code.toByte() || bytes[end - 1] == '\r'.code.toByte())) end--
                return end - from
            }
            i++
        }
        return null
    }

    private fun matchesAt(at: Int, keyword: String): Boolean {
        if (at + keyword.length > bytes.size) return false
        for (k in keyword.indices) if (bytes[at + k] != keyword[k].code.toByte()) return false
        return true
    }

    /** Strings inside normal objects are encrypted; inside object streams they are not. */
    private fun decryptStrings(value: Any?, num: Int, gen: Int): Any? {
        val crypto = crypto ?: return value
        return when (value) {
            is PdfString -> PdfString(crypto.decrypt(value.bytes, num, gen, isString = true))
            is List<*> -> value.map { decryptStrings(it, num, gen) }
            is Map<*, *> -> value.entries.associate { (k, v) -> k as String to decryptStrings(v, num, gen) }
            else -> value
        }
    }

    private fun parseFromObjStm(streamObjNum: Int, index: Int): Any? {
        val stream = getObject(streamObjNum) as? Stream ?: return null
        val data = decodeStream(stream)
        val n = int(stream.dict["N"]) ?: return null
        val first = int(stream.dict["First"]) ?: return null
        val header = PdfLexer(data, 0)
        var offset = -1
        repeat(minOf(n, index + 1)) { i ->
            val objNum = header.next() as? Long ?: return null
            val objOff = header.next() as? Long ?: return null
            if (i == index) offset = objOff.toInt()
        }
        if (offset < 0 || first + offset >= data.size) return null
        return PdfLexer(data, first + offset).readValue()
    }

    // ── Stream decoding ──────────────────────────────────────────────────────

    fun decodeStream(stream: Stream): ByteArray {
        var data = stream.cachedRaw ?: run {
            var raw = bytes.copyOfRange(stream.rawStart, (stream.rawStart + stream.rawLength).coerceAtMost(bytes.size))
            // XRef streams are never encrypted; everything else is (V≥1).
            val isXref = name(stream.dict["Type"]) == "XRef"
            if (crypto != null && !isXref && !stream.fromObjStm) {
                raw = crypto.decrypt(raw, stream.objNum, stream.objGen, isString = false)
            }
            stream.cachedRaw = raw
            raw
        }

        val filters: List<String> = when (val f = resolve(stream.dict["Filter"])) {
            is Name -> listOf(f.value)
            is List<*> -> f.mapNotNull { (resolve(it) as? Name)?.value }
            else -> emptyList()
        }
        val parmsList: List<Map<String, Any?>?> = when (val p = resolve(stream.dict["DecodeParms"] ?: stream.dict["DP"])) {
            is Map<*, *> -> listOf(dict(p))
            is List<*> -> p.map { dict(resolve(it)) }
            else -> emptyList()
        }

        filters.forEachIndexed { i, filter ->
            val parms = parmsList.getOrNull(i)
            data = when (filter) {
                "FlateDecode", "Fl" -> predict(inflate(data), parms)
                "ASCIIHexDecode", "AHx" -> asciiHex(data)
                "ASCII85Decode", "A85" -> ascii85(data)
                "Crypt" -> data  // Identity crypt filter — already handled
                else -> throw StatementReadException("Unsupported PDF filter: $filter")
            }
        }
        return data
    }

    private fun inflate(data: ByteArray): ByteArray {
        val inflater = Inflater()
        inflater.setInput(data)
        val out = java.io.ByteArrayOutputStream(data.size * 4)
        val buffer = ByteArray(16384)
        try {
            while (!inflater.finished()) {
                val n = inflater.inflate(buffer)
                if (n == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break
                } else out.write(buffer, 0, n)
                if (out.size() > 256 * 1024 * 1024) throw StatementReadException("PDF stream too large.")
            }
        } catch (e: java.util.zip.DataFormatException) {
            // Truncated deflate data — keep what decoded; statements often survive.
        } finally {
            inflater.end()
        }
        return out.toByteArray()
    }

    /** PNG predictors (Up/Sub/Average/Paeth) — xref streams love Predictor 12. */
    private fun predict(data: ByteArray, parms: Map<String, Any?>?): ByteArray {
        val predictor = int(parms?.get("Predictor")) ?: 1
        if (predictor < 10) return data
        val columns = int(parms?.get("Columns")) ?: 1
        val colors = int(parms?.get("Colors")) ?: 1
        val bpc = int(parms?.get("BitsPerComponent")) ?: 8
        val bpp = maxOf(1, colors * bpc / 8)
        val rowLen = (columns * colors * bpc + 7) / 8
        val rows = data.size / (rowLen + 1)
        val out = ByteArray(rows * rowLen)
        val prev = ByteArray(rowLen)
        for (r in 0 until rows) {
            val filter = data[r * (rowLen + 1)].toInt() and 0xFF
            val src = r * (rowLen + 1) + 1
            val dst = r * rowLen
            for (i in 0 until rowLen) {
                val raw = data[src + i].toInt() and 0xFF
                val left = if (i >= bpp) out[dst + i - bpp].toInt() and 0xFF else 0
                val up = prev[i].toInt() and 0xFF
                val upLeft = if (i >= bpp) prev[i - bpp].toInt() and 0xFF else 0
                val value = when (filter) {
                    0 -> raw
                    1 -> raw + left
                    2 -> raw + up
                    3 -> raw + (left + up) / 2
                    4 -> {
                        val p = left + up - upLeft
                        val pa = kotlin.math.abs(p - left)
                        val pb = kotlin.math.abs(p - up)
                        val pc = kotlin.math.abs(p - upLeft)
                        raw + if (pa <= pb && pa <= pc) left else if (pb <= pc) up else upLeft
                    }
                    else -> raw
                }
                out[dst + i] = (value and 0xFF).toByte()
            }
            System.arraycopy(out, dst, prev, 0, rowLen)
        }
        return out
    }

    private fun asciiHex(data: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var hi = -1
        for (b in data) {
            val c = b.toInt().toChar()
            if (c == '>') break
            val digit = Character.digit(c, 16)
            if (digit < 0) continue
            if (hi < 0) hi = digit else {
                out.write(hi shl 4 or digit); hi = -1
            }
        }
        if (hi >= 0) out.write(hi shl 4)
        return out.toByteArray()
    }

    private fun ascii85(data: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var tuple = 0L
        var count = 0
        var i = 0
        while (i < data.size) {
            val c = data[i].toInt().toChar()
            i++
            when {
                c == '~' -> break
                c == 'z' && count == 0 -> repeat(4) { out.write(0) }
                c in '!'..'u' -> {
                    tuple = tuple * 85 + (c - '!')
                    count++
                    if (count == 5) {
                        for (shift in 24 downTo 0 step 8) out.write(((tuple shr shift) and 0xFF).toInt())
                        tuple = 0; count = 0
                    }
                }
            }
        }
        if (count > 1) {
            repeat(5 - count) { tuple = tuple * 85 + 84 }
            for (shift in 24 downTo (8 * (5 - count))) {
                if (shift % 8 == 0) out.write(((tuple shr shift) and 0xFF).toInt())
            }
        }
        return out.toByteArray()
    }

    // ── Construction ─────────────────────────────────────────────────────────

    companion object {

        fun open(bytes: ByteArray, password: String?): PdfDocument {
            val (xref, trailer) = runCatching { parseXref(bytes) }.getOrElse { parseByScan(bytes) }
            val encryptRef = trailer["Encrypt"]
            var crypto: PdfCrypto? = null
            if (encryptRef != null) {
                // The /Encrypt dict itself is never encrypted; resolve it with a
                // crypto-less document.
                val plain = PdfDocument(bytes, xref, trailer, null)
                val enc = plain.dict(plain.resolve(encryptRef))
                    ?: throw StatementReadException("Unreadable PDF encryption dictionary.")
                val filter = plain.name(enc["Filter"])
                if (filter != null && filter != "Standard")
                    throw StatementReadException("PDF uses unsupported security handler \"$filter\".")
                val idBytes = ((trailer["ID"] as? List<*>)?.firstOrNull() as? PdfString)?.bytes ?: ByteArray(0)
                crypto = PdfCrypto.authenticate(enc, idBytes, password ?: "", plain)
                    ?: throw StatementPasswordRequired(
                        if (password.isNullOrEmpty()) "This PDF is password-protected."
                        else "That password did not open the PDF."
                    )
            }
            val doc = PdfDocument(bytes, xref, trailer, crypto)
            return doc
        }

        // ── Xref parsing ────────────────────────────────────────────────────

        private fun parseXref(bytes: ByteArray): Pair<Map<Int, XrefEntry>, MutableMap<String, Any?>> {
            val tail = String(bytes, maxOf(0, bytes.size - 2048), minOf(2048, bytes.size), Charsets.ISO_8859_1)
            val marker = tail.lastIndexOf("startxref")
            if (marker < 0) throw StatementReadException("No startxref.")
            val offset = Regex("startxref\\s+(\\d+)").find(tail, marker)?.groupValues?.get(1)?.toIntOrNull()
                ?: throw StatementReadException("Bad startxref.")

            val xref = HashMap<Int, XrefEntry>()
            val trailer = HashMap<String, Any?>()
            var next = offset
            val visited = HashSet<Int>()
            while (next >= 0 && next < bytes.size && visited.add(next)) {
                next = parseXrefSection(bytes, next, xref, trailer)
            }
            if (xref.isEmpty()) throw StatementReadException("Empty xref.")
            return xref to trailer
        }

        /** One xref section (classic or stream). Returns /Prev or -1. */
        private fun parseXrefSection(
            bytes: ByteArray,
            offset: Int,
            xref: HashMap<Int, XrefEntry>,
            trailer: HashMap<String, Any?>
        ): Int {
            val lexer = PdfLexer(bytes, offset)
            val first = lexer.next()
            if (first == PdfLexer.KW_XREF) {
                // Classic table.
                while (true) {
                    val a = lexer.next()
                    if (a == PdfLexer.KW_TRAILER) break
                    val start = (a as? Long)?.toInt() ?: return -1
                    val count = (lexer.next() as? Long)?.toInt() ?: return -1
                    repeat(count.coerceAtMost(1 shl 22)) { i ->
                        val off = (lexer.next() as? Long)?.toInt() ?: return -1
                        @Suppress("UNUSED_VARIABLE") val gen = (lexer.next() as? Long)?.toInt() ?: return -1
                        val kind = lexer.next()
                        val objNum = start + i
                        if (kind == PdfLexer.KW_N && objNum !in xref) {
                            xref[objNum] = XrefEntry.Direct(off)
                        }
                    }
                }
                val t = lexer.readValue() as? Map<*, *> ?: return -1
                @Suppress("UNCHECKED_CAST") val td = t as Map<String, Any?>
                for ((k, v) in td) if (k !in trailer) trailer[k] = v
                // Hybrid files: /XRefStm points at an xref stream with the real entries.
                (td["XRefStm"] as? Long)?.let { parseXrefSection(bytes, it.toInt(), xref, HashMap()) }
                return (td["Prev"] as? Long)?.toInt() ?: -1
            }

            // Xref stream: "N G obj << … >> stream".
            lexer.pos = offset
            val num = (lexer.next() as? Long)?.toInt() ?: return -1
            (lexer.next() as? Long) ?: return -1
            if (lexer.next() != PdfLexer.KW_OBJ) return -1
            @Suppress("UNCHECKED_CAST")
            val dict = lexer.readValue() as? Map<String, Any?> ?: return -1
            if (lexer.next() != PdfLexer.KW_STREAM) return -1
            var dataStart = lexer.pos
            if (dataStart < bytes.size && bytes[dataStart] == '\r'.code.toByte()) dataStart++
            if (dataStart < bytes.size && bytes[dataStart] == '\n'.code.toByte()) dataStart++
            val length = (dict["Length"] as? Long)?.toInt() ?: return -1

            val holder = PdfDocument(bytes, emptyMap(), HashMap(), null)
            val stream = Stream(dict, dataStart, length, num, 0, fromObjStm = false)
            val data = holder.decodeStream(stream)

            val w = (dict["W"] as? List<*>)?.mapNotNull { (it as? Long)?.toInt() } ?: return -1
            if (w.size < 3) return -1
            val size = (dict["Size"] as? Long)?.toInt() ?: 0
            val index = (dict["Index"] as? List<*>)?.mapNotNull { (it as? Long)?.toInt() }
                ?: listOf(0, size)
            val rowLen = w.sum()
            var pos = 0
            var i = 0
            while (i + 1 < index.size) {
                val start = index[i]
                val count = index[i + 1]
                for (j in 0 until count) {
                    if (pos + rowLen > data.size) break
                    fun field(width: Int): Long {
                        var v = 0L
                        repeat(width) { v = (v shl 8) or (data[pos++].toLong() and 0xFF) }
                        return v
                    }
                    val type = if (w[0] == 0) 1L else field(w[0])
                    val f2 = field(w[1])
                    val f3 = field(w[2])
                    val objNum = start + j
                    if (objNum !in xref) {
                        when (type) {
                            1L -> xref[objNum] = XrefEntry.Direct(f2.toInt())
                            2L -> xref[objNum] = XrefEntry.InStream(f2.toInt(), f3.toInt())
                        }
                    }
                }
                i += 2
            }
            for ((k, v) in dict) if (k !in trailer) trailer[k] = v
            return (dict["Prev"] as? Long)?.toInt() ?: -1
        }

        /** Last resort: scan the file for "N G obj" definitions. */
        private fun parseByScan(bytes: ByteArray): Pair<Map<Int, XrefEntry>, MutableMap<String, Any?>> {
            val xref = HashMap<Int, XrefEntry>()
            val trailer = HashMap<String, Any?>()
            val pattern = Regex("(\\d{1,8})\\s+(\\d{1,5})\\s+obj\\b")
            val text = String(bytes, Charsets.ISO_8859_1)
            for (m in pattern.findAll(text)) {
                val num = m.groupValues[1].toIntOrNull() ?: continue
                xref[num] = XrefEntry.Direct(m.range.first)  // later definitions win
            }
            val t = text.lastIndexOf("trailer")
            if (t >= 0) {
                val lexer = PdfLexer(bytes, t + 7)
                @Suppress("UNCHECKED_CAST")
                (lexer.readValue() as? Map<String, Any?>)?.let { trailer.putAll(it) }
            }
            if (trailer["Root"] == null) {
                // Find any object whose dict is /Type /Catalog.
                val catalog = Regex("/Type\\s*/Catalog").find(text)
                if (catalog != null) {
                    val objStart = text.lastIndexOf(" obj", catalog.range.first)
                    if (objStart > 0) {
                        val head = text.substring(maxOf(0, objStart - 20), objStart)
                        Regex("(\\d+)\\s+(\\d+)\\s*$").find(head)?.let {
                            trailer["Root"] = Ref(it.groupValues[1].toInt(), it.groupValues[2].toInt())
                        }
                    }
                }
            }
            if (xref.isEmpty()) throw StatementReadException("Could not read the PDF structure.")
            return xref to trailer
        }
    }
}

/** A PDF string literal — kept as raw bytes because encryption applies to them. */
internal class PdfString(val bytes: ByteArray) {
    override fun toString(): String = String(bytes, Charsets.ISO_8859_1)
}

// ── Lexer ────────────────────────────────────────────────────────────────────

/**
 * Tokenizer for PDF syntax. [next] returns: Long, Double, [PdfString],
 * [PdfDocument.Name], [PdfDocument.Ref] (folded by [readValue]), List, Map,
 * Boolean, null, or one of the KW_* markers.
 */
internal class PdfLexer(private val data: ByteArray, var pos: Int) {

    companion object {
        val KW_OBJ = Any()
        val KW_ENDOBJ = Any()
        val KW_STREAM = Any()
        val KW_XREF = Any()
        val KW_TRAILER = Any()
        val KW_N = Any()
        val KW_F = Any()
        val KW_R = Any()
        val NULL_SENTINEL = Any()

        /** Any other bare word — a content-stream operator, read via [lastWord]. */
        val OP = Any()

        private val KEYWORDS = mapOf(
            "obj" to KW_OBJ, "endobj" to KW_ENDOBJ, "stream" to KW_STREAM,
            "xref" to KW_XREF, "trailer" to KW_TRAILER, "n" to KW_N, "f" to KW_F, "R" to KW_R
        )
    }

    /** The raw text of the last keyword/operator [next] returned. */
    var lastWord: String? = null
        private set

    private fun isDelimiter(c: Int): Boolean =
        c == '('.code || c == ')'.code || c == '<'.code || c == '>'.code ||
            c == '['.code || c == ']'.code || c == '{'.code || c == '}'.code ||
            c == '/'.code || c == '%'.code

    private fun isWhitespace(c: Int): Boolean =
        c == 0 || c == 9 || c == 10 || c == 12 || c == 13 || c == 32

    private fun skipWhitespace() {
        while (pos < data.size) {
            val c = data[pos].toInt() and 0xFF
            if (isWhitespace(c)) { pos++; continue }
            if (c == '%'.code) {
                while (pos < data.size && data[pos] != '\n'.code.toByte() && data[pos] != '\r'.code.toByte()) pos++
                continue
            }
            break
        }
    }

    /** Raw token. Structural values (dict/array/string) come pre-assembled. */
    fun next(): Any? {
        skipWhitespace()
        if (pos >= data.size) return null
        val c = data[pos].toInt() and 0xFF
        return when {
            c == '/'.code -> readName()
            c == '('.code -> readLiteralString()
            c == '<'.code && pos + 1 < data.size && data[pos + 1] == '<'.code.toByte() -> readDict()
            c == '<'.code -> readHexString()
            c == '['.code -> readArray()
            c == ']'.code || c == '>'.code || c == ')'.code || c == '}'.code -> { pos++; null }
            c == '+'.code || c == '-'.code || c == '.'.code || c in '0'.code..'9'.code -> readNumber()
            else -> readKeyword()
        }
    }

    /** [next] plus `N G R` reference folding. */
    fun readValue(): Any? {
        val first = next()
        if (first is Long) {
            val save = pos
            val second = next()
            if (second is Long) {
                val third = next()
                if (third === KW_R) return PdfDocument.Ref(first.toInt(), second.toInt())
                // Two bare numbers in a row: rewind to just after the first so the
                // caller's next readValue() sees the second again.
                pos = save
                return first
            }
            pos = save
            return first
        }
        return first
    }

    private fun readName(): PdfDocument.Name {
        pos++
        val sb = StringBuilder()
        while (pos < data.size) {
            val c = data[pos].toInt() and 0xFF
            if (isWhitespace(c) || isDelimiter(c)) break
            if (c == '#'.code && pos + 2 < data.size) {
                val hex = Character.digit(data[pos + 1].toInt().toChar(), 16) * 16 +
                    Character.digit(data[pos + 2].toInt().toChar(), 16)
                if (hex >= 0) {
                    sb.append(hex.toChar()); pos += 3; continue
                }
            }
            sb.append(c.toChar()); pos++
        }
        return PdfDocument.Name(sb.toString())
    }

    private fun readNumber(): Any {
        val start = pos
        var isDouble = false
        while (pos < data.size) {
            val c = data[pos].toInt() and 0xFF
            if (c == '.'.code) { isDouble = true; pos++; continue }
            if (c == '+'.code || c == '-'.code || c in '0'.code..'9'.code) { pos++; continue }
            break
        }
        val text = String(data, start, pos - start, Charsets.ISO_8859_1)
        return if (isDouble) (text.toDoubleOrNull() ?: 0.0) else (text.toLongOrNull() ?: 0L)
    }

    private fun readKeyword(): Any? {
        val start = pos
        while (pos < data.size) {
            val c = data[pos].toInt() and 0xFF
            if (isWhitespace(c) || isDelimiter(c)) break
            pos++
        }
        if (pos == start) { pos++; return null }
        val word = String(data, start, pos - start, Charsets.ISO_8859_1)
        lastWord = word
        return when (word) {
            "true" -> true
            "false" -> false
            "null" -> NULL_SENTINEL
            else -> KEYWORDS[word] ?: OP
        }
    }

    private fun readLiteralString(): PdfString {
        pos++
        val out = java.io.ByteArrayOutputStream()
        var depth = 1
        while (pos < data.size) {
            val c = data[pos].toInt() and 0xFF
            pos++
            when {
                c == '\\'.code && pos < data.size -> {
                    val e = data[pos].toInt() and 0xFF
                    pos++
                    when (e) {
                        'n'.code -> out.write(10)
                        'r'.code -> out.write(13)
                        't'.code -> out.write(9)
                        'b'.code -> out.write(8)
                        'f'.code -> out.write(12)
                        '('.code -> out.write('('.code)
                        ')'.code -> out.write(')'.code)
                        '\\'.code -> out.write('\\'.code)
                        13 -> if (pos < data.size && data[pos] == 10.toByte()) pos++  // line continuation
                        10 -> {}
                        in '0'.code..'7'.code -> {
                            var v = e - '0'.code
                            var digits = 1
                            while (digits < 3 && pos < data.size) {
                                val d = data[pos].toInt() and 0xFF
                                if (d in '0'.code..'7'.code) { v = v * 8 + (d - '0'.code); pos++; digits++ } else break
                            }
                            out.write(v and 0xFF)
                        }
                        else -> out.write(e)
                    }
                }
                c == '('.code -> { depth++; out.write(c) }
                c == ')'.code -> {
                    depth--
                    if (depth == 0) return PdfString(out.toByteArray())
                    out.write(c)
                }
                else -> out.write(c)
            }
        }
        return PdfString(out.toByteArray())
    }

    private fun readHexString(): PdfString {
        pos++
        val out = java.io.ByteArrayOutputStream()
        var hi = -1
        while (pos < data.size) {
            val c = data[pos].toInt() and 0xFF
            pos++
            if (c == '>'.code) break
            val digit = Character.digit(c.toChar(), 16)
            if (digit < 0) continue
            if (hi < 0) hi = digit else { out.write(hi shl 4 or digit); hi = -1 }
        }
        if (hi >= 0) out.write(hi shl 4)
        return PdfString(out.toByteArray())
    }

    private fun readArray(): List<Any?> {
        pos++
        val items = ArrayList<Any?>()
        while (pos < data.size) {
            skipWhitespace()
            if (pos < data.size && data[pos] == ']'.code.toByte()) { pos++; break }
            val save = pos
            val value = readValue()
            if (pos == save) { pos++; continue }
            if (value === NULL_SENTINEL) items.add(null) else items.add(value)
            if (items.size > 500_000) break
        }
        return items
    }

    private fun readDict(): Map<String, Any?> {
        pos += 2
        val map = LinkedHashMap<String, Any?>()
        while (pos < data.size) {
            skipWhitespace()
            if (pos + 1 < data.size && data[pos] == '>'.code.toByte() && data[pos + 1] == '>'.code.toByte()) {
                pos += 2; break
            }
            val key = next() as? PdfDocument.Name ?: continue
            val value = readValue()
            map[key.value] = if (value === NULL_SENTINEL) null else value
            if (map.size > 100_000) break
        }
        return map
    }
}

// ── Standard security handler ────────────────────────────────────────────────

/**
 * PDF standard security: derives the file key from the user (or owner)
 * password and decrypts strings/streams. Returns null from [authenticate]
 * when the password is wrong — the caller turns that into a prompt.
 */
internal class PdfCrypto private constructor(
    private val fileKey: ByteArray,
    /** 1/2 = RC4 with per-object key, 4 = AES-128 per-object, 5 = AES-256 direct. */
    private val version: Int,
    private val aes: Boolean
) {

    fun decrypt(data: ByteArray, objNum: Int, gen: Int, isString: Boolean): ByteArray = runCatching {
        if (version == 0) return@runCatching data  // /Identity — nothing is encrypted
        if (version == 5) return@runCatching aesDecrypt(fileKey, data)
        val md = MessageDigest.getInstance("MD5")
        md.update(fileKey)
        md.update(byteArrayOf(objNum.toByte(), (objNum shr 8).toByte(), (objNum shr 16).toByte()))
        md.update(byteArrayOf(gen.toByte(), (gen shr 8).toByte()))
        if (aes) md.update(byteArrayOf(0x73, 0x41, 0x6C, 0x54))  // "sAlT"
        val objKey = md.digest().copyOf(minOf(fileKey.size + 5, 16))
        if (aes) aesDecrypt(objKey, data) else rc4(objKey, data)
    }.getOrDefault(data)

    private fun aesDecrypt(key: ByteArray, data: ByteArray): ByteArray {
        if (data.size < 16) return data
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            IvParameterSpec(data.copyOf(16))
        )
        val body = data.copyOfRange(16, data.size - (data.size - 16) % 16)
        if (body.isEmpty()) return ByteArray(0)
        val plain = cipher.doFinal(body)
        // Strip PKCS#5 padding leniently — some writers pad wrong.
        val pad = plain.lastOrNull()?.toInt()?.and(0xFF) ?: 0
        return if (pad in 1..16 && pad <= plain.size) plain.copyOf(plain.size - pad) else plain
    }

    companion object {

        private val PAD = byteArrayOf(
            0x28, 0xBF.toByte(), 0x4E, 0x5E, 0x4E, 0x75, 0x8A.toByte(), 0x41,
            0x64, 0x00, 0x4E, 0x56, 0xFF.toByte(), 0xFA.toByte(), 0x01, 0x08,
            0x2E, 0x2E, 0x00, 0xB6.toByte(), 0xD0.toByte(), 0x68, 0x3E, 0x80.toByte(),
            0x2F, 0x0C, 0xA9.toByte(), 0xFE.toByte(), 0x64, 0x53, 0x69, 0x7A
        )

        fun authenticate(
            enc: Map<String, Any?>,
            fileId: ByteArray,
            password: String,
            doc: PdfDocument
        ): PdfCrypto? {
            fun int(key: String): Int? = when (val v = doc.resolve(enc[key])) {
                is Long -> v.toInt(); is Double -> v.toInt(); else -> null
            }
            fun str(key: String): ByteArray? = (doc.resolve(enc[key]) as? PdfString)?.bytes

            val v = int("V") ?: 0
            val r = int("R") ?: if (v >= 2) 3 else 2
            val o = str("O") ?: return null
            val u = str("U") ?: return null
            val p = int("P") ?: -1
            val lengthBits = int("Length") ?: 40

            // V4: crypt filter map decides RC4 vs AES vs Identity.
            var aes = false
            if (v == 4) {
                @Suppress("UNCHECKED_CAST")
                val cf = doc.resolve(enc["CF"]) as? Map<String, Any?>
                val stmF = (doc.resolve(enc["StmF"]) as? PdfDocument.Name)?.value ?: "Identity"
                // /Identity stream filter: the file declares encryption but applies
                // none to streams — decrypt() must be a pure pass-through.
                if (stmF == "Identity") return PdfCrypto(ByteArray(0), IDENTITY_VERSION, false)
                @Suppress("UNCHECKED_CAST")
                val filter = doc.resolve(cf?.get(stmF)) as? Map<String, Any?>
                val cfm = (doc.resolve(filter?.get("CFM")) as? PdfDocument.Name)?.value
                aes = cfm == "AESV2"
            }
            if (v == 5) {
                return authenticateV5(enc, password, doc)
            }

            val encryptMetadata = (doc.resolve(enc["EncryptMetadata"]) as? Boolean) ?: true
            val keyLen = (lengthBits / 8).coerceIn(5, 16)

            fun deriveKey(pw: ByteArray): ByteArray {
                val md = MessageDigest.getInstance("MD5")
                md.update(pad(pw))
                md.update(o.copyOf(32))
                md.update(
                    byteArrayOf(p.toByte(), (p shr 8).toByte(), (p shr 16).toByte(), (p shr 24).toByte())
                )
                md.update(fileId)
                if (r >= 4 && !encryptMetadata) md.update(byteArrayOf(-1, -1, -1, -1))
                var key = md.digest()
                if (r >= 3) repeat(50) {
                    key = MessageDigest.getInstance("MD5").digest(key.copyOf(keyLen))
                }
                return key.copyOf(keyLen)
            }

            fun verifies(key: ByteArray): Boolean = when (r) {
                2 -> rc4(key, PAD).contentEquals(u.copyOf(32))
                else -> {
                    val md = MessageDigest.getInstance("MD5")
                    md.update(PAD)
                    md.update(fileId)
                    var x = rc4(key, md.digest())
                    for (i in 1..19) {
                        val stepKey = ByteArray(key.size) { (key[it].toInt() xor i).toByte() }
                        x = rc4(stepKey, x)
                    }
                    x.copyOf(16).contentEquals(u.copyOf(minOf(16, u.size)))
                }
            }

            val pwBytes = password.toByteArray(Charsets.ISO_8859_1)

            // Try as the user password (and the empty one, the print-lock case).
            for (candidate in listOf(pwBytes, ByteArray(0)).distinctBy { it.toList() }) {
                val key = deriveKey(candidate)
                if (verifies(key)) return PdfCrypto(key, v.coerceAtLeast(1), aes)
            }

            // Try as the owner password: unwrap O into the user password.
            run {
                val md = MessageDigest.getInstance("MD5")
                md.update(pad(pwBytes))
                var keyO = md.digest()
                if (r >= 3) repeat(50) { keyO = MessageDigest.getInstance("MD5").digest(keyO) }
                keyO = keyO.copyOf(keyLen)
                var userPw = o.copyOf(32)
                if (r == 2) {
                    userPw = rc4(keyO, userPw)
                } else {
                    for (i in 19 downTo 0) {
                        val stepKey = ByteArray(keyO.size) { (keyO[it].toInt() xor i).toByte() }
                        userPw = rc4(stepKey, userPw)
                    }
                }
                val key = deriveKey(userPw)
                if (verifies(key)) return PdfCrypto(key, v.coerceAtLeast(1), aes)
            }

            return null
        }

        /** Marker version for V4 /Identity — decrypt() becomes a no-op via version 0 path. */
        private const val IDENTITY_VERSION = 0

        private fun authenticateV5(enc: Map<String, Any?>, password: String, doc: PdfDocument): PdfCrypto? {
            fun str(key: String): ByteArray? = (doc.resolve(enc[key]) as? PdfString)?.bytes
            val o = str("O") ?: return null
            val u = str("U") ?: return null
            val oe = str("OE")
            val ue = str("UE")
            if (u.size < 48 || o.size < 48) return null
            val r = when (val v = doc.resolve(enc["R"])) { is Long -> v.toInt(); else -> 6 }
            val pw = password.toByteArray(Charsets.UTF_8).let { if (it.size > 127) it.copyOf(127) else it }

            fun hash(pwBytes: ByteArray, salt: ByteArray, udata: ByteArray): ByteArray {
                var k = MessageDigest.getInstance("SHA-256").digest(pwBytes + salt + udata)
                if (r == 5) return k  // R5: single SHA-256
                var round = 0
                while (true) {
                    val block = pwBytes + k + udata
                    val k1 = ByteArray(block.size * 64)
                    for (i in 0 until 64) System.arraycopy(block, 0, k1, i * block.size, block.size)
                    val cipher = Cipher.getInstance("AES/CBC/NoPadding")
                    cipher.init(
                        Cipher.ENCRYPT_MODE,
                        SecretKeySpec(k.copyOf(16), "AES"),
                        IvParameterSpec(k.copyOfRange(16, 32))
                    )
                    val e = cipher.doFinal(k1.copyOf(k1.size - k1.size % 16))
                    val mod = (e.copyOf(16).sumOf { it.toInt() and 0xFF }) % 3
                    k = when (mod) {
                        0 -> MessageDigest.getInstance("SHA-256").digest(e)
                        1 -> MessageDigest.getInstance("SHA-384").digest(e)
                        else -> MessageDigest.getInstance("SHA-512").digest(e)
                    }
                    round++
                    if (round >= 64 && (e.last().toInt() and 0xFF) <= round - 32) break
                }
                return k.copyOf(32)
            }

            fun unwrap(key: ByteArray, wrapped: ByteArray): ByteArray? = runCatching {
                val cipher = Cipher.getInstance("AES/CBC/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(ByteArray(16)))
                cipher.doFinal(wrapped.copyOf(32))
            }.getOrNull()

            // User password path.
            val uHash = u.copyOf(32)
            val uVSalt = u.copyOfRange(32, 40)
            val uKSalt = u.copyOfRange(40, 48)
            for (candidate in listOf(pw, ByteArray(0)).distinctBy { it.toList() }) {
                if (hash(candidate, uVSalt, ByteArray(0)).contentEquals(uHash)) {
                    val intermediate = hash(candidate, uKSalt, ByteArray(0))
                    val fileKey = ue?.let { unwrap(intermediate, it) } ?: return null
                    return PdfCrypto(fileKey, 5, true)
                }
            }
            // Owner password path.
            val oHash = o.copyOf(32)
            val oVSalt = o.copyOfRange(32, 40)
            val oKSalt = o.copyOfRange(40, 48)
            val udata = u.copyOf(48)
            for (candidate in listOf(pw, ByteArray(0)).distinctBy { it.toList() }) {
                if (hash(candidate, oVSalt, udata).contentEquals(oHash)) {
                    val intermediate = hash(candidate, oKSalt, udata)
                    val fileKey = oe?.let { unwrap(intermediate, it) } ?: return null
                    return PdfCrypto(fileKey, 5, true)
                }
            }
            return null
        }

        private fun pad(password: ByteArray): ByteArray {
            val out = ByteArray(32)
            val n = minOf(password.size, 32)
            System.arraycopy(password, 0, out, 0, n)
            System.arraycopy(PAD, 0, out, n, 32 - n)
            return out
        }

        internal fun rc4(key: ByteArray, data: ByteArray): ByteArray {
            val s = IntArray(256) { it }
            var j = 0
            for (i in 0 until 256) {
                j = (j + s[i] + (key[i % key.size].toInt() and 0xFF)) and 0xFF
                val t = s[i]; s[i] = s[j]; s[j] = t
            }
            val out = ByteArray(data.size)
            var i = 0
            j = 0
            for (k in data.indices) {
                i = (i + 1) and 0xFF
                j = (j + s[i]) and 0xFF
                val t = s[i]; s[i] = s[j]; s[j] = t
                out[k] = (data[k].toInt() xor s[(s[i] + s[j]) and 0xFF]).toByte()
            }
            return out
        }
    }
}
