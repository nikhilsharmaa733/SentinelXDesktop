package com.nikhil.sentinelx.desktop.ui

import com.google.gson.Gson
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nikhil.sentinelx.desktop.core.format.AccountEntity
import com.nikhil.sentinelx.desktop.core.format.ArtifactEntity
import com.nikhil.sentinelx.desktop.core.format.CashEntryEntity
import com.nikhil.sentinelx.desktop.core.format.ChronicleEntity
import com.nikhil.sentinelx.desktop.core.format.LoginEntity
import com.nikhil.sentinelx.desktop.core.format.MasterBackup
import com.nikhil.sentinelx.desktop.core.format.ProphecyEntity
import com.nikhil.sentinelx.desktop.core.format.SxvArchive
import com.nikhil.sentinelx.desktop.core.format.TransactionEntity
import com.nikhil.sentinelx.desktop.core.format.VaultMerge
import com.nikhil.sentinelx.desktop.core.format.VaultSection
import com.nikhil.sentinelx.desktop.core.format.referencedImages
import com.nikhil.sentinelx.desktop.core.format.scopedTo
import com.nikhil.sentinelx.desktop.core.store.LocalCrypto
import com.nikhil.sentinelx.desktop.core.store.VaultStore
import java.io.File
import java.util.UUID

/**
 * Which top-level section the sidebar has selected.
 *
 * [wire] is the matching [VaultSection] constant, or null for Overview, which is a
 * view over everything rather than a store of its own. It is what lets a pane export
 * or import just its own records.
 */
enum class Section(val label: String, val glyph: String, val wire: String?) {
    OVERVIEW("Overview", "ᚦ", null),
    LOGINS("Logins", "ᛗ", VaultSection.LOGINS),
    CARDS("Cards", "ᚠ", VaultSection.CARDS),
    NOTES("Notes", "ᚱ", VaultSection.NOTES),
    CHRONICLES("Chronicles", "ᛀ", VaultSection.CHRONICLES),
    LEDGER("Ledger", "ᚢ", VaultSection.LEDGER),
    CASHBOOK("Cash Book", "ᛃ", VaultSection.CASHBOOK)
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
        loadFavourites()
        locked = false
        true
    } ?: false

    fun create(password: CharArray, seed: MasterBackup = MasterBackup()): Boolean =
        guard("Creating vault…") {
            session = store.create(password, seed).also { backup = it.load() }
            loadFavourites()
            locked = false
            true
        } ?: false

    fun lock() {
        session?.lock()
        session = null
        backup = MasterBackup()
        favourites = emptySet()
        locked = true
        section = Section.OVERVIEW
    }

    // ── Import ────────────────────────────────────────────────────────────────

    /**
     * Reads a Migration Seal and returns its contents WITHOUT committing anything.
     * The caller shows the counts first — the user should see what they are about to
     * get, and what it will cost them, before it happens.
     */
    fun previewArchive(file: File, password: CharArray): SxvArchive.Payload =
        SxvArchive.read(file, password)

    /** What folding [payload] into the current vault would add, skip and collide with. */
    fun planImport(payload: SxvArchive.Payload): VaultMerge.Plan =
        VaultMerge.preview(backup, payload.backup)

    /**
     * Commits a previously previewed archive under [mode] and [policy].
     *
     * Only images the resulting vault actually references are stored. Copying the
     * archive's whole image set in would leave blobs behind for records the merge
     * skipped, and on a Replace they would be blobs for records that no longer exist.
     */
    fun adoptArchive(
        payload: SxvArchive.Payload,
        mode: VaultMerge.Mode = VaultMerge.Mode.REPLACE,
        policy: VaultMerge.DuplicatePolicy = VaultMerge.DuplicatePolicy.SKIP
    ): Boolean = guard("Importing archive…") {
        val active = session ?: error("Vault is locked")
        val result = VaultMerge.apply(backup, payload.backup, mode, policy)
        val needed = result.vault.referencedImages()
        payload.images.forEach { (name, bytes) -> if (name in needed) active.putImage(name, bytes) }
        active.save(result.vault)
        backup = result.vault
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

    /**
     * Stores an image and returns the filename to reference it by.
     *
     * Names match the phone's convention (`ImageUtils.saveToInternalVault`) so an
     * archive written here is indistinguishable from one the phone produced.
     */
    fun addImage(bytes: ByteArray, extension: String = "webp"): String {
        val name = "IMG_${UUID.randomUUID()}.$extension"
        session?.putImage(name, bytes)
        return name
    }

    // ── CRUD ──────────────────────────────────────────────────────────────────
    //
    // IDs are allocated as max+1 per collection. They only need to be unique within
    // this vault: restoring on the phone maps every row through `copy(id = 0)` and
    // lets Room reassign, so they never have to agree across devices.

    private fun <T> nextId(items: List<T>, idOf: (T) -> Int): Int =
        (items.maxOfOrNull(idOf) ?: 0) + 1

    private fun <T> nextLongId(items: List<T>, idOf: (T) -> Long): Long =
        (items.maxOfOrNull(idOf) ?: 0L) + 1L

    fun upsertLogin(login: LoginEntity) = mutate { b ->
        val id = if (login.id == 0) nextId(b.logins) { it.id } else login.id
        val entry = login.copy(id = id)
        b.copy(logins = b.logins.replacingOrAdding(entry) { it.id == id })
    }

    fun deleteLogin(id: Int) = mutate { b -> b.copy(logins = b.logins.filterNot { it.id == id }) }

    fun upsertArtifact(artifact: ArtifactEntity) = mutate { b ->
        val id = if (artifact.id == 0) nextId(b.artifacts) { it.id } else artifact.id
        val entry = artifact.copy(id = id, timestamp = artifact.timestamp.orNow())
        b.copy(artifacts = b.artifacts.replacingOrAdding(entry) { it.id == id })
    }

    fun deleteArtifact(id: Int) = mutate { b -> b.copy(artifacts = b.artifacts.filterNot { it.id == id }) }

    fun upsertProphecy(note: ProphecyEntity) = mutate { b ->
        val id = if (note.id == 0) nextId(b.prophecies) { it.id } else note.id
        val entry = note.copy(id = id, timestamp = System.currentTimeMillis())
        b.copy(prophecies = b.prophecies.replacingOrAdding(entry) { it.id == id })
    }

    fun deleteProphecy(id: Int) = mutate { b -> b.copy(prophecies = b.prophecies.filterNot { it.id == id }) }

    fun upsertChronicle(doc: ChronicleEntity) = mutate { b ->
        val id = if (doc.id == 0) nextId(b.chronicles) { it.id } else doc.id
        val entry = doc.copy(id = id, timestamp = doc.timestamp.orNow())
        b.copy(chronicles = b.chronicles.replacingOrAdding(entry) { it.id == id })
    }

    fun deleteChronicle(id: Int) = mutate { b -> b.copy(chronicles = b.chronicles.filterNot { it.id == id }) }

    fun upsertAccount(account: AccountEntity) = mutate { b ->
        val id = if (account.id == 0L) nextLongId(b.accounts) { it.id } else account.id
        val entry = account.copy(id = id, timestamp = account.timestamp.orNow())
        b.copy(accounts = b.accounts.replacingOrAdding(entry) { it.id == id })
    }

    /** Deleting an account also removes its transactions, or they become unreachable ghosts. */
    fun deleteAccount(id: Long) = mutate { b ->
        b.copy(
            accounts = b.accounts.filterNot { it.id == id },
            ledger = b.ledger.filterNot { it.accountId == id }
        )
    }

    fun upsertTransaction(tx: TransactionEntity) = mutate { b ->
        val id = if (tx.id == 0L) nextLongId(b.ledger) { it.id } else tx.id
        val entry = tx.copy(id = id, timestamp = tx.timestamp.orNow())
        b.copy(ledger = b.ledger.replacingOrAdding(entry) { it.id == id })
    }

    fun deleteTransaction(id: Long) = mutate { b -> b.copy(ledger = b.ledger.filterNot { it.id == id }) }

    fun upsertCashEntry(entry: CashEntryEntity) = mutate { b ->
        val id = if (entry.id == 0L) nextLongId(b.cashBook) { it.id } else entry.id
        val row = entry.copy(id = id, timestamp = System.currentTimeMillis())
        b.copy(cashBook = b.cashBook.replacingOrAdding(row) { it.id == id })
    }

    fun deleteCashEntry(id: Long) = mutate { b -> b.copy(cashBook = b.cashBook.filterNot { it.id == id }) }

    /**
     * Names already used as counter or verifier, most recent first.
     *
     * The same two or three people sign off every night, so the editor offers them
     * rather than making someone retype a name 365 times a year.
     */
    fun knownCashPeople(): List<String> =
        backup.cashBook
            .sortedByDescending { it.timestamp }
            .flatMap { listOf(it.countedBy, it.verifiedBy) }
            .filter { it.isNotBlank() }
            .distinct()

    // ── Favourites ────────────────────────────────────────────────────────────
    //
    // Stored in a local sidecar, never in MasterBackup. The archive schema is fixed
    // by the phone, and a favourites field would be silently dropped there anyway.

    var favourites by mutableStateOf<Set<String>>(emptySet())
        private set

    /** Stable key across restores, since row IDs are reassigned by Room on the phone. */
    fun favouriteKey(type: String, name: String) = "$type:${name.lowercase()}"

    fun isFavourite(key: String) = key in favourites

    fun toggleFavourite(key: String) {
        favourites = if (key in favourites) favourites - key else favourites + key
        runCatching {
            session?.writeSidecar("favourites", Gson().toJson(favourites).toByteArray(Charsets.UTF_8))
        }
    }

    private fun loadFavourites() {
        favourites = runCatching {
            session?.readSidecar("favourites")?.let { bytes ->
                Gson().fromJson(bytes.toString(Charsets.UTF_8), Array<String>::class.java).toSet()
            }
        }.getOrNull() ?: emptySet()
    }

    // ── History / undo ────────────────────────────────────────────────────────
    //
    // Built on the snapshots VaultStore already writes before every save, rather
    // than a parallel recycle bin. One mechanism covers accidental deletes, bad
    // edits, and a botched import alike — a bin would only cover the first.

    fun history(): List<Long> = session?.versions().orEmpty()

    fun previewVersion(timestamp: Long): MasterBackup? =
        runCatching { session?.loadVersion(timestamp) }.getOrNull()

    /**
     * Restores a snapshot. Saved as a new state rather than rewinding, so the
     * current version is itself snapshotted first and the undo is undoable.
     */
    fun restoreVersion(timestamp: Long): Boolean = guard("Restoring…") {
        val active = session ?: error("Vault is locked")
        val previous = active.loadVersion(timestamp)
        active.save(previous)
        backup = previous
        true
    } ?: false

    // ── Export ────────────────────────────────────────────────────────────────

    /**
     * Writes a Migration Seal the Android app can restore.
     *
     * Always v2 (SXV2, 600k iterations) — the same format the phone writes — so the
     * round trip is symmetric. Only referenced images are packed; orphans accumulate
     * on the phone when records are deleted and there is no reason to carry them.
     */
    /**
     * Writes a Migration Seal holding [sections].
     *
     * Scoping the payload before collecting images is the point: `referencedImages()`
     * is read off the *scoped* copy, so a Notes-only archive cannot quietly ship the
     * photographs of your ID cards along with it.
     */
    fun exportArchive(
        file: File,
        password: CharArray,
        sections: Collection<String> = VaultSection.ALL
    ): Boolean = guard("Exporting…") {
        val active = session ?: error("Vault is locked")
        val payload = backup.scopedTo(sections)
        val images = payload.referencedImages()
            .mapNotNull { name -> active.readImage(name)?.let { name to it } }
            .toMap()
        SxvArchive.write(file, payload, images, password)
        true
    } ?: false

    private fun Long.orNow(): Long = if (this == 0L) System.currentTimeMillis() else this

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

/** Replaces the matching element, or appends when there is none. */
private fun <T> List<T>.replacingOrAdding(item: T, match: (T) -> Boolean): List<T> =
    if (any(match)) map { if (match(it)) item else it } else this + item
