package com.nikhil.sentinelx.desktop.core.statement

/*
 * ⚠️ MIRRORED FILE — see StatementGrid.kt. Byte-identical to the Android copy
 * apart from the package line.
 */

/**
 * The one entry point: bytes in, [StatementGrid] out.
 *
 * Dispatch is by **content, never extension** — several banks serve an HTML
 * page or a CSV with an `.xls` name, and Excel's own leniency taught them
 * nobody would notice. Sniffing magic bytes routes each file to the reader
 * that can actually parse it:
 *
 * | Magic                 | Reader        |
 * |-----------------------|---------------|
 * | `PK..` + worksheets   | [XlsxTable]   |
 * | `PK..` + content.xml  | [OdsTable]    |
 * | `ÐÏ.à` (OLE2)         | [XlsTable]    |
 * | `%PDF`                | [PdfTable]    |
 * | looks like markup     | [HtmlTable]   |
 * | anything else textual | [CsvTable]    |
 *
 * A password only means anything for PDFs; [StatementPasswordRequired]
 * signals the UI to ask for one and call again.
 */
object StatementReader {

    fun read(bytes: ByteArray, fileName: String, password: String? = null): StatementGrid {
        if (bytes.isEmpty()) throw StatementReadException("The file is empty.")
        if (bytes.size > 256 * 1024 * 1024) throw StatementReadException("The file is too large.")

        return try {
            when {
                isZip(bytes) -> readZipContainer(bytes, fileName)
                isOle2(bytes) -> XlsTable.read(bytes)
                isPdf(bytes) -> PdfTable.read(bytes, password)
                else -> {
                    val text = decodeText(bytes)
                    if (looksLikeHtml(text)) HtmlTable.read(text) else CsvTable.read(text)
                }
            }.copy(fileName = fileName)
        } catch (e: StatementPasswordRequired) {
            throw e
        } catch (e: StatementReadException) {
            throw e
        } catch (e: Exception) {
            throw StatementReadException(
                "Could not read \"$fileName\" — ${e.message ?: e::class.simpleName}", e
            )
        }
    }

    private fun isZip(bytes: ByteArray): Boolean =
        bytes.size > 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() &&
            (bytes[2] == 0x03.toByte() || bytes[2] == 0x05.toByte() || bytes[2] == 0x07.toByte())

    private fun isOle2(bytes: ByteArray): Boolean =
        bytes.size > 8 &&
            bytes[0] == 0xD0.toByte() && bytes[1] == 0xCF.toByte() &&
            bytes[2] == 0x11.toByte() && bytes[3] == 0xE0.toByte()

    private fun isPdf(bytes: ByteArray): Boolean {
        // The %PDF header may sit after a few junk bytes (some generators prepend a BOM).
        val limit = minOf(bytes.size - 4, 1024)
        for (i in 0..limit) {
            if (bytes[i] == '%'.code.toByte() && bytes[i + 1] == 'P'.code.toByte() &&
                bytes[i + 2] == 'D'.code.toByte() && bytes[i + 3] == 'F'.code.toByte()
            ) return true
        }
        return false
    }

    /** Both XLSX and ODS are zips; the entry names tell them apart. */
    private fun readZipContainer(bytes: ByteArray, fileName: String): StatementGrid {
        var hasWorksheets = false
        var hasOdsContent = false
        var mimetype: String? = null
        java.util.zip.ZipInputStream(java.io.ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                when {
                    entry.name.startsWith("xl/") -> hasWorksheets = true
                    entry.name == "content.xml" -> hasOdsContent = true
                    entry.name == "mimetype" -> mimetype = zip.readBytes().toString(Charsets.UTF_8).trim()
                }
                zip.closeEntry()
            }
        }
        return when {
            hasWorksheets -> XlsxTable.read(bytes)
            hasOdsContent && (mimetype == null || "spreadsheet" in mimetype!!) -> OdsTable.read(bytes)
            hasOdsContent -> throw StatementReadException(
                "\"$fileName\" is an OpenDocument file but not a spreadsheet."
            )
            else -> throw StatementReadException(
                "\"$fileName\" is a zip archive, not a spreadsheet. Export the statement itself, not a compressed copy."
            )
        }
    }

    private fun decodeText(bytes: ByteArray): String {
        // UTF-16 BOMs (some banks' "Excel" exports are UTF-16 TSV).
        if (bytes.size >= 2) {
            if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte())
                return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            if (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte())
                return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        val utf8 = String(bytes, Charsets.UTF_8)
        // A replacement-char flood means it was not UTF-8 after all.
        return if (utf8.count { it == '�' } > bytes.size / 100)
            String(bytes, Charsets.ISO_8859_1)
        else utf8
    }

    private fun looksLikeHtml(text: String): Boolean {
        val head = text.take(4096).lowercase()
        return "<table" in head || "<html" in head || "<!doctype html" in head ||
            ("<tr" in head && "<td" in head)
    }
}
