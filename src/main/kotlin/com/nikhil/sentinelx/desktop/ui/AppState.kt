package com.nikhil.sentinelx.desktop.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nikhil.sentinelx.desktop.core.format.MasterBackup
import com.nikhil.sentinelx.desktop.core.format.SxvArchive
import com.nikhil.sentinelx.desktop.core.store.LocalCrypto
import com.nikhil.sentinelx.desktop.core.store.VaultStore
import java.io.File

/** Which top-level section the sidebar has selected. */
enum class Section(val label: String, val glyph: String) {
    OVERVIEW("Overview", "ᚦ"),
    LOGINS("Logins", "ᛗ"),
    CARDS("Cards", "ᚠ"),
    NOTES("Notes", "ᚱ"),
    CHRONICLES("Chronicles", "ᛀ"),
    LEDGER("Ledger", "ᚢ")
}

/**
 * Everything the UI needs, in one place.
 *
 * Held as plain `mutableStateOf` rather than a reactive database, because the whole
 * vault is small enough to live in memory — the real one decrypts to ~54 KB of
 * metadata. Images stay on disk as sealed blobs and are read on demand.
 */
class AppState(private val store: VaultStore = VaultStore(VaultStore.defaultDir())) {

    var locked by mutableStateOf(true)
        private set

    var backup by mutableStateOf(MasterBackup())
        private set

    var section by mutableStateOf(Section.OVERVIEW)

    var busy by mutableStateOf<String?>(null)
        private set

    var error by mutableStateOf<String?>(null)

    /** Null until unlocked. Zeroed on lock. */
    private var session: VaultStore.Session? = null

    val vaultExists: Boolean get() = store.exists
    val vaultLocation: String get() = VaultStore.defaultDir().path

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Argon2id at 64 MB takes about a second by design, which would freeze the UI
     * thread. Callers run this off the main thread and mirror progress via [busy].
     */
    fun unlock(password: CharArray): Boolean = guard("Unlocking vault…") {
        session = store.unlock(password).also { backup = it.load() }
        locked = false
        true
    } ?: false

    fun create(password: CharArray, seed: MasterBackup = MasterBackup()): Boolean =
        guard("Creating vault…") {
            session = store.create(password, seed).also { backup = it.load() }
            locked = false
            true
        } ?: false

    fun lock() {
        session?.lock()
        session = null
        backup = MasterBackup()
        locked = true
        section = Section.OVERVIEW
    }

    // ── Import ────────────────────────────────────────────────────────────────

    /**
     * Reads a Migration Seal and returns its contents WITHOUT committing anything.
     * The caller shows the counts first — import replaces the vault wholesale, so
     * the user should see what they are about to get before it happens.
     */
    fun previewArchive(file: File, password: CharArray): SxvArchive.Payload =
        SxvArchive.read(file, password)

    /** Commits a previously previewed archive, replacing everything. */
    fun adoptArchive(payload: SxvArchive.Payload): Boolean = guard("Importing archive…") {
        val active = session ?: error("Vault is locked")
        payload.images.forEach { (name, bytes) -> active.putImage(name, bytes) }
        active.save(payload.backup)
        backup = payload.backup
        true
    } ?: false

    // ── Mutation ──────────────────────────────────────────────────────────────

    /** Applies a change and persists it. Every edit goes through here so nothing skips the save. */
    fun mutate(transform: (MasterBackup) -> MasterBackup) {
        val active = session ?: return
        val next = transform(backup)
        active.save(next)
        backup = next
    }

    fun readImage(name: String): ByteArray? = session?.readImage(name)

    private fun <T> guard(label: String, block: () -> T): T? {
        busy = label
        error = null
        return try {
            block()
        } catch (e: LocalCrypto.WrongPasswordException) {
            error = "Incorrect master password."
            null
        } catch (e: Exception) {
            error = e.message ?: e::class.simpleName
            null
        } finally {
            busy = null
        }
    }
}
