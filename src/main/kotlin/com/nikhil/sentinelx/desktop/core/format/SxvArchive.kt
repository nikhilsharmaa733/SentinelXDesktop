package com.nikhil.sentinelx.desktop.core.format

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Reads and writes Migration Seal (`.sxv`) archives, matching the Android app's
 * `util/BackupManager.kt` layout exactly:
 *
 *   vault_data.json   — the encrypted payload (a base64 STRING, not JSON at this layer)
 *   images/<name>     — one entry per image, flat, no subdirectories
 *
 * Everything is held in memory. The whole point of the desktop design is that
 * decrypted vault contents never reach the disk: Windows has no equivalent to the
 * app-private storage Android relies on, so extracting to a temp folder would be a
 * real regression in protection versus the phone.
 */
object SxvArchive {

    private const val JSON_ENTRY = "vault_data.json"
    private const val IMAGE_PREFIX = "images/"

    /** Refuse absurd archives rather than exhausting the heap on a hostile file. */
    private const val MAX_TOTAL_BYTES = 2L * 1024 * 1024 * 1024   // 2 GB
    private const val MAX_ENTRIES = 100_000

    data class Payload(
        val backup: MasterBackup,
        /** filename → raw bytes. Filenames are as referenced by the entities. */
        val images: Map<String, ByteArray>
    ) {
        /** Images the entities point at but the archive does not contain. */
        fun missingImages(): Set<String> = backup.referencedImages() - images.keys

        /** Images present in the archive that nothing references. */
        fun orphanImages(): Set<String> = images.keys - backup.referencedImages()
    }

    class MalformedArchiveException(message: String) : Exception(message)

    fun read(file: File, password: CharArray): Payload {
        if (!file.exists()) throw MalformedArchiveException("No such file: ${file.path}")

        var encodedPayload: String? = null
        val images = LinkedHashMap<String, ByteArray>()
        var totalBytes = 0L
        var entryCount = 0

        ZipInputStream(BufferedInputStream(file.inputStream())).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                if (++entryCount > MAX_ENTRIES) {
                    throw MalformedArchiveException("Archive has too many entries.")
                }
                when {
                    entry.name == JSON_ENTRY -> {
                        encodedPayload = zis.readBytes().toString(Charsets.UTF_8)
                    }

                    entry.name.startsWith(IMAGE_PREFIX) && !entry.isDirectory -> {
                        val name = safeImageName(entry.name)
                        if (name == null) {
                            // 🛡️ Zip Slip. An .sxv is an arbitrary user-supplied file, so
                            // an entry like "images/../../evil" must never be honoured.
                            // Nothing is written to disk here, but a traversing name
                            // could still poison the filename→bytes map and end up
                            // written later on export.
                            System.err.println("Rejected unsafe archive entry: ${entry.name}")
                        } else {
                            val bytes = zis.readBytes()
                            totalBytes += bytes.size
                            if (totalBytes > MAX_TOTAL_BYTES) {
                                throw MalformedArchiveException("Archive is unreasonably large.")
                            }
                            images[name] = bytes
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        val payload = encodedPayload
            ?: throw MalformedArchiveException("Not a SentinelX archive: $JSON_ENTRY is missing.")

        val json = SxvCrypto.decrypt(payload, password)

        val backup = try {
            Gson().fromJson(json, MasterBackup::class.java)
        } catch (e: JsonSyntaxException) {
            throw MalformedArchiveException("Archive decrypted but its contents are not valid JSON.")
        } ?: throw MalformedArchiveException("Archive decrypted to nothing.")

        return Payload(backup, images)
    }

    fun write(file: File, backup: MasterBackup, images: Map<String, ByteArray>, password: CharArray) {
        val json = Gson().toJson(backup)
        val encrypted = SxvCrypto.encrypt(json, password)

        // Write to a sibling temp file and rename, so an interrupted export cannot
        // leave a half-written archive where a good one used to be.
        val temp = File(file.parentFile, "${file.name}.tmp")

        ZipOutputStream(BufferedOutputStream(temp.outputStream())).use { zos ->
            zos.setMethod(ZipOutputStream.DEFLATED)

            zos.putNextEntry(ZipEntry(JSON_ENTRY))
            zos.write(encrypted.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            // Only write images something actually points at. Orphans accumulate on
            // the phone when a card is deleted, and there is no reason to carry them.
            val referenced = backup.referencedImages()
            images.filterKeys { it in referenced }.forEach { (name, bytes) ->
                zos.putNextEntry(ZipEntry("$IMAGE_PREFIX$name"))
                zos.write(bytes)
                zos.closeEntry()
            }
            zos.finish()
        }

        if (!temp.renameTo(file)) {
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
    }

    /**
     * Returns a bare filename, or null if the entry name tries to escape `images/`.
     *
     * Mirrors `BackupManager.safeVaultFile()` on Android: reject anything containing
     * a path separator, a parent reference, or an absolute path.
     */
    private fun safeImageName(entryName: String): String? {
        val name = entryName.removePrefix(IMAGE_PREFIX)
        if (name.isBlank()) return null
        if (name.contains('/') || name.contains('\\')) return null
        if (name == "." || name == "..") return null
        return name
    }
}
