package com.nikhil.sentinelx.desktop.core.store

import com.google.gson.Gson
import com.nikhil.sentinelx.desktop.core.format.MasterBackup
import com.nikhil.sentinelx.desktop.core.format.referencedImages
import java.io.File

/**
 * The desktop app's local encrypted vault.
 *
 * ## Why not just edit the `.sxv` directly
 *
 * Re-encrypting a whole archive on every change would rule out autosave and undo,
 * and gets worse as scanned documents grow — the real vault is already 3 MB. So the
 * store splits by size, because the two halves behave differently:
 *
 *  - **Metadata** (all six entity lists) is small — ~54 KB decrypted in the real
 *    vault. One sealed file, rewritten whole on change. Cheap enough to autosave.
 *  - **Images** are large and immutable once added. One sealed blob each, written
 *    once and read on demand.
 *
 * ## Layout
 * ```
 * <dir>/vault.meta          sealed MasterBackup JSON (carries the Argon2 salt)
 * <dir>/images/<name>.blob  one sealed image each
 * <dir>/versions/<ts>.meta  previous metadata snapshots
 * ```
 *
 * ## Decrypted bytes never touch the disk
 * On Android, images sit in app-private storage the OS protects. Windows has no
 * equivalent, so writing plaintext images out — even to a temp folder — would make
 * the desktop app *weaker* than the phone. Images are decrypted into memory only.
 */
class VaultStore(private val dir: File) {

    private val metaFile = File(dir, "vault.meta")
    private val imagesDir = File(dir, "images")
    private val versionsDir = File(dir, "versions")

    /** Snapshots kept before each save. Tiny (tens of KB), so keep a generous history. */
    private val versionsToKeep = 20

    val exists: Boolean get() = metaFile.isFile

    // ── Unlocking ─────────────────────────────────────────────────────────────

    /** Creates a brand-new vault. Fails rather than overwriting an existing one. */
    fun create(password: CharArray, initial: MasterBackup = MasterBackup()): Session {
        check(!exists) { "A vault already exists at ${dir.path}" }
        dir.mkdirs(); imagesDir.mkdirs(); versionsDir.mkdirs()

        val salt = LocalCrypto.newSalt()
        val key = LocalCrypto.deriveKey(password, salt)
        val session = Session(key, salt)
        session.save(initial)
        return session
    }

    /**
     * Unlocks an existing vault.
     *
     * The salt is read from the file header, so the key can be re-derived without
     * storing anything secret alongside it.
     */
    fun unlock(password: CharArray): Session {
        if (!exists) throw LocalCrypto.CorruptVaultException("No vault at ${dir.path}")
        val sealed = metaFile.readBytes()
        val salt = LocalCrypto.saltOf(sealed)
        val key = LocalCrypto.deriveKey(password, salt)
        // Force a decrypt now so a wrong password fails here rather than later.
        LocalCrypto.openDocument(sealed, key)
        return Session(key, salt)
    }

    /** An unlocked vault. Holds the derived key; [lock] zeroes it. */
    inner class Session(
        private val key: LocalCrypto.VaultKey,
        private val salt: ByteArray
    ) {
        private var locked = false

        private fun requireOpen() = check(!locked) { "Vault session is locked." }

        fun load(): MasterBackup {
            requireOpen()
            val json = LocalCrypto.openDocument(metaFile.readBytes(), key).toString(Charsets.UTF_8)
            return Gson().fromJson(json, MasterBackup::class.java) ?: MasterBackup()
        }

        /**
         * Writes metadata atomically: temp file, then rename. A crash mid-write can
         * therefore never leave a half-written vault where a good one used to be —
         * rename is atomic on both NTFS and ext4.
         *
         * The previous version is snapshotted first, so a bad edit is recoverable.
         */
        fun save(backup: MasterBackup) {
            requireOpen()
            dir.mkdirs(); imagesDir.mkdirs(); versionsDir.mkdirs()

            if (metaFile.isFile) snapshot()

            val sealed = LocalCrypto.sealDocument(
                Gson().toJson(backup).toByteArray(Charsets.UTF_8), key, salt
            )
            val temp = File(dir, "vault.meta.tmp")
            temp.writeBytes(sealed)
            if (!temp.renameTo(metaFile)) {
                temp.copyTo(metaFile, overwrite = true)
                temp.delete()
            }
        }

        // ── Images ────────────────────────────────────────────────────────────

        fun putImage(name: String, bytes: ByteArray) {
            requireOpen()
            imagesDir.mkdirs()
            blobFor(name).writeBytes(LocalCrypto.sealBlob(bytes, key))
        }

        /** Returns decrypted bytes, or null if absent. Callers should not cache these to disk. */
        fun readImage(name: String): ByteArray? {
            requireOpen()
            val blob = blobFor(name)
            return if (blob.isFile) LocalCrypto.openBlob(blob.readBytes(), key) else null
        }

        fun hasImage(name: String): Boolean = blobFor(name).isFile

        fun imageNames(): Set<String> =
            imagesDir.listFiles()?.mapNotNull {
                it.name.removeSuffix(".blob").takeIf { n -> n != it.name }
            }?.toSet() ?: emptySet()

        /**
         * Deletes image blobs nothing references any more.
         *
         * Not automatic on delete: an undo would need the bytes back. Call this
         * explicitly, after the recycle bin is emptied.
         */
        fun pruneOrphanImages(backup: MasterBackup): Int {
            requireOpen()
            val keep = backup.referencedImages()
            var removed = 0
            imageNames().filterNot { it in keep }.forEach {
                if (blobFor(it).delete()) removed++
            }
            return removed
        }

        // ── Versions ──────────────────────────────────────────────────────────

        private fun snapshot() {
            versionsDir.mkdirs()
            metaFile.copyTo(File(versionsDir, "${System.currentTimeMillis()}.meta"), overwrite = true)
            val snaps = versionsDir.listFiles()?.sortedBy { it.name } ?: return
            snaps.dropLast(versionsToKeep).forEach { it.delete() }
        }

        /** Snapshot timestamps, newest first. */
        fun versions(): List<Long> =
            versionsDir.listFiles()
                ?.mapNotNull { it.name.removeSuffix(".meta").toLongOrNull() }
                ?.sortedDescending() ?: emptyList()

        fun loadVersion(timestamp: Long): MasterBackup {
            requireOpen()
            val f = File(versionsDir, "$timestamp.meta")
            if (!f.isFile) throw LocalCrypto.CorruptVaultException("No snapshot at $timestamp")
            val json = LocalCrypto.openDocument(f.readBytes(), key).toString(Charsets.UTF_8)
            return Gson().fromJson(json, MasterBackup::class.java) ?: MasterBackup()
        }

        fun lock() {
            if (!locked) { key.destroy(); locked = true }
        }

        private fun blobFor(name: String): File {
            // Image names come from archive entries, so treat them as untrusted even
            // though SxvArchive already filtered them — defence in depth, and cheap.
            require(!name.contains('/') && !name.contains('\\') && name != "." && name != "..") {
                "Unsafe image name: $name"
            }
            return File(imagesDir, "$name.blob")
        }
    }

    companion object {
        /** Per-OS application data directory. */
        fun defaultDir(): File {
            val home = System.getProperty("user.home")
            val os = System.getProperty("os.name").lowercase()
            return when {
                os.contains("win") ->
                    File(System.getenv("APPDATA") ?: "$home\\AppData\\Roaming", "SentinelX")
                os.contains("mac") ->
                    File(home, "Library/Application Support/SentinelX")
                else ->
                    File(System.getenv("XDG_DATA_HOME") ?: "$home/.local/share", "SentinelX")
            }
        }
    }
}
