package com.nikhil.sentinelx.desktop.ui

import com.google.gson.Gson
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.nikhil.sentinelx.desktop.core.format.AccountEntity
import com.nikhil.sentinelx.desktop.core.format.ArtifactEntity
import com.nikhil.sentinelx.desktop.core.format.BankTxnEntity
import com.nikhil.sentinelx.desktop.core.format.CashEntryEntity
import com.nikhil.sentinelx.desktop.core.format.CheckItem
import com.nikhil.sentinelx.desktop.core.format.ChronicleEntity
import com.nikhil.sentinelx.desktop.core.format.FolderEntity
import com.nikhil.sentinelx.desktop.core.format.LoginEntity
import com.nikhil.sentinelx.desktop.core.format.MasterBackup
import com.nikhil.sentinelx.desktop.core.format.ProphecyEntity
import com.nikhil.sentinelx.desktop.core.format.SxvArchive
import com.nikhil.sentinelx.desktop.core.format.TransactionEntity
import com.nikhil.sentinelx.desktop.core.format.VaultMerge
import com.nikhil.sentinelx.desktop.core.format.VaultSection
import com.nikhil.sentinelx.desktop.core.format.encodeCheckItems
import com.nikhil.sentinelx.desktop.core.format.folderKey
import com.nikhil.sentinelx.desktop.core.format.folderName
import com.nikhil.sentinelx.desktop.core.format.hashFolderPasscode
import com.nikhil.sentinelx.desktop.core.format.itemsToText
import com.nikhil.sentinelx.desktop.core.format.newFolderSalt
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
    CASHBOOK("Cash Book", "ᛃ", VaultSection.CASHBOOK),
    BANK("Bank Book", "ᛒ", VaultSection.BANK)
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

    /**
     * Open floating editors. Lives here rather than in a pane so a panel survives
     * switching section — the point of them is holding several records at once, and
     * they are frequently in different sections.
     */
    val panels = PanelHostState()

    /**
     * The browser bridge — the desktop counterpart of the phone's autofill
     * service. It reads logins live from [backup] and routes a capture back
     * through [upsertLogin], so it never holds its own copy of the vault. Its
     * on/off preference persists in a sidecar (like favourites); the socket
     * itself runs only between unlock and lock.
     */
    val bridge = BridgeController(
        loginsProvider = { backup.logins },
        // Returns whether the write actually happened — a vault that locked
        // between the capture arriving and the user confirming must not let
        // the browser be told "saved".
        onCaptureConfirmed = { login ->
            if (session == null) false
            else {
                upsertLogin(login)
                true
            }
        }
    )

    val vaultExists: Boolean get() = store.exists
    val vaultLocation: String get() = VaultStore.defaultDir().path

    /** Persist and apply the bridge toggle. */
    fun setBridgeEnabled(value: Boolean) {
        bridge.setEnabled(value, unlocked = !locked)
        runCatching {
            session?.writeSidecar("bridge", if (value) "on".toByteArray() else "off".toByteArray())
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Argon2id at 64 MB takes about a second by design, which would freeze the UI
     * thread. Callers run this off the main thread and mirror progress via [busy].
     */
    fun unlock(password: CharArray): Boolean = guard("Unlocking vault…") {
        session = store.unlock(password).also { backup = it.load() }
        loadFavourites()
        loadBridgePreference()
        locked = false
        bridge.onUnlocked()
        true
    } ?: false

    private fun loadBridgePreference() {
        val on = runCatching {
            session?.readSidecar("bridge")?.toString(Charsets.UTF_8)?.trim() == "on"
        }.getOrDefault(false)
        // Reflect the stored preference without touching the socket yet — unlock()
        // starts it right after via bridge.onUnlocked(), so avoid a double start.
        if (on != bridge.enabled) bridge.setEnabled(on, unlocked = false)
    }

    fun create(password: CharArray, seed: MasterBackup = MasterBackup()): Boolean =
        guard("Creating vault…") {
            session = store.create(password, seed).also { backup = it.load() }
            loadFavourites()
            locked = false
            true
        } ?: false

    fun lock() {
        bridge.onLocked()
        session?.lock()
        session = null
        backup = MasterBackup()
        favourites = emptySet()
        locked = true
        section = Section.OVERVIEW
        // A half-typed password sitting in an open editor would otherwise survive the
        // lock in plain sight — and so would an unsealed folder.
        unlockedFolders = emptySet()
        panels.closeAll()
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
        // An editor opened before the import holds a record the merge may have just
        // replaced, renamed or dropped. Saving it afterwards would quietly undo the
        // import for that one row.
        panels.closeAll()
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
        // Snap a hand-typed folder onto an existing spelling — "work" must not fork
        // a second folder beside "Work". Mirrors the phone's saveProphecy.
        val typed = note.folderName()
        val snapped = typed?.let { name ->
            val key = folderKey(name)
            b.noteFolders.firstOrNull { folderKey(it.name) == key }?.name
                ?: b.prophecies.firstOrNull { folderKey(it.folder) == key }?.folderName()
                ?: name
        }
        val entry = note.copy(id = id, folder = snapped, timestamp = System.currentTimeMillis())
        b.copy(prophecies = b.prophecies.replacingOrAdding(entry) { it.id == id })
    }

    fun deleteProphecy(id: Int) = mutate { b -> b.copy(prophecies = b.prophecies.filterNot { it.id == id }) }

    /**
     * In-place note change that is not an edit — pin, archive, a checkbox tick.
     * Unlike [upsertProphecy] the timestamp stays put, matching the phone's @Update
     * path, so pinning a note does not shove it to the top of "recent".
     */
    fun patchProphecy(note: ProphecyEntity) = mutate { b ->
        b.copy(prophecies = b.prophecies.map { if (it.id == note.id) note else it })
    }

    // ── Note folders ──────────────────────────────────────────────────────────
    //
    // The note→folder join is the folder *name* (case-insensitive), which is why
    // rename cascades over the member notes and why nothing here remaps ids.

    /** Locked folders opened this session, keyed by [folderKey]. Cleared on lock. */
    var unlockedFolders by mutableStateOf<Set<String>>(emptySet())
        private set

    fun markFolderUnlocked(name: String) {
        folderKey(name)?.let { unlockedFolders = unlockedFolders + it }
    }

    /** Creates or restyles a folder. Renames go through [renameFolder], never here. */
    fun saveFolder(folder: FolderEntity) = mutate { b ->
        val id = if (folder.id == 0) nextId(b.noteFolders) { it.id } else folder.id
        val entry = folder.copy(id = id, name = folder.name.trim(), timestamp = folder.timestamp.orNow())
        b.copy(noteFolders = b.noteFolders.replacingOrAdding(entry) { it.id == id })
    }

    /**
     * Renames the record and every member note in one mutation — a half-applied
     * rename would silently unfile (and unlock) the notes. An id of 0 materialises
     * an implicit folder that existed only as strings on its notes.
     */
    fun renameFolder(folder: FolderEntity, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return
        val oldKey = folderKey(folder.name) ?: return
        mutate { b ->
            val id = if (folder.id == 0) nextId(b.noteFolders) { it.id } else folder.id
            val entry = folder.copy(id = id, name = trimmed, timestamp = folder.timestamp.orNow())
            b.copy(
                noteFolders = b.noteFolders.replacingOrAdding(entry) { it.id == id },
                prophecies = b.prophecies.map {
                    if (folderKey(it.folder) == oldKey) it.copy(folder = trimmed) else it
                }
            )
        }
        // The unlock travels with the rename, or the folder you are inside re-seals
        // under your feet.
        if (oldKey in unlockedFolders) markFolderUnlocked(trimmed)
    }

    /** Deletes the folder record; its notes go loose, they are not deleted. */
    fun deleteFolder(folder: FolderEntity) {
        val key = folderKey(folder.name)
        mutate { b ->
            b.copy(
                noteFolders = b.noteFolders.filterNot { it.id == folder.id },
                prophecies = b.prophecies.map {
                    if (folderKey(it.folder) == key) it.copy(folder = null) else it
                }
            )
        }
    }

    /**
     * Locks or unlocks a folder, creating the record if it only existed implicitly.
     * [passcode] non-blank sets a fresh passcode; null keeps whatever is already
     * set; unlocking always clears it.
     */
    fun setFolderLock(folder: FolderEntity, locked: Boolean, passcode: String?) {
        val updated = when {
            !locked -> folder.copy(isLocked = false, passcodeHash = null, passcodeSalt = null)
            passcode.isNullOrEmpty() -> folder.copy(isLocked = true)
            else -> {
                val salt = newFolderSalt()
                folder.copy(
                    isLocked = true,
                    passcodeSalt = salt,
                    passcodeHash = hashFolderPasscode(salt, passcode)
                )
            }
        }
        saveFolder(updated)
        if (!locked) markFolderUnlocked(folder.name)
    }

    /**
     * The recovery path for a forgotten folder passcode: proving the vault master
     * password proves ownership. Argon2id takes about a second — callers run this
     * off the main thread like [unlock].
     */
    fun verifyMasterPassword(password: CharArray): Boolean = runCatching {
        store.unlock(password).lock()
        true
    }.getOrDefault(false)

    // ── Bulk note actions ─────────────────────────────────────────────────────

    /** [folderName] null unfiles; a name snaps onto an existing folder's spelling. */
    fun moveNotesToFolder(ids: Collection<Int>, folderName: String?) {
        if (ids.isEmpty()) return
        mutate { b ->
            val target = folderName?.trim()?.takeIf { it.isNotEmpty() }?.let { name ->
                val key = folderKey(name)
                b.noteFolders.firstOrNull { folderKey(it.name) == key }?.name ?: name
            }
            b.copy(prophecies = b.prophecies.map {
                if (it.id in ids) it.copy(folder = target) else it
            })
        }
    }

    fun archiveNotes(ids: Collection<Int>, archived: Boolean) {
        if (ids.isEmpty()) return
        mutate { b ->
            b.copy(prophecies = b.prophecies.map {
                if (it.id in ids) it.copy(isArchived = archived, isPinned = if (archived) false else it.isPinned)
                else it
            })
        }
    }

    fun pinNotes(ids: Collection<Int>, pinned: Boolean) {
        if (ids.isEmpty()) return
        mutate { b ->
            b.copy(prophecies = b.prophecies.map {
                if (it.id in ids && !it.isArchived) it.copy(isPinned = pinned) else it
            })
        }
    }

    fun deleteNotes(ids: Collection<Int>) {
        if (ids.isEmpty()) return
        mutate { b -> b.copy(prophecies = b.prophecies.filterNot { it.id in ids }) }
    }

    fun toggleNotePinned(note: ProphecyEntity) = patchProphecy(note.copy(isPinned = !note.isPinned))

    /** Archive is the recoverable "delete". An archived note also drops its pin. */
    fun toggleNoteArchived(note: ProphecyEntity) =
        patchProphecy(note.copy(isArchived = !note.isArchived, isPinned = false))

    /**
     * Rewrites a checklist after a checkbox tap. `content` is regenerated alongside
     * `checkItems` — it is the plain-text mirror that search, copy and pre-v8 builds
     * read, and the two must never disagree.
     */
    fun setNoteChecklist(note: ProphecyEntity, items: List<CheckItem>) =
        patchProphecy(note.copy(checkItems = items.encodeCheckItems(), content = itemsToText(items)))

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

    // ── Bank book ─────────────────────────────────────────────────────────────

    fun upsertBankTxn(txn: BankTxnEntity) = mutate { b ->
        val id = if (txn.id == 0L) nextLongId(b.bankTxns) { it.id } else txn.id
        val entry = txn.copy(id = id, timestamp = txn.timestamp.orNow())
        b.copy(bankTxns = b.bankTxns.replacingOrAdding(entry) { it.id == id })
    }

    fun deleteBankTxn(id: Long) = mutate { b -> b.copy(bankTxns = b.bankTxns.filterNot { it.id == id }) }

    /** Deletes a whole statement book and every transaction in it. */
    fun deleteBankBook(book: String) = mutate { b ->
        b.copy(bankTxns = b.bankTxns.filterNot { it.book.equals(book, ignoreCase = true) })
    }

    /**
     * Renames a book across its member rows. Fingerprints deliberately exclude
     * the book name (merge identity is the *pair*), so this rewrites one field
     * and invalidates nothing.
     */
    fun renameBankBook(oldName: String, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty() || trimmed.equals(oldName, ignoreCase = true)) return
        mutate { b ->
            b.copy(bankTxns = b.bankTxns.map {
                if (it.book.equals(oldName, ignoreCase = true)) it.copy(book = trimmed) else it
            })
        }
    }

    /**
     * Commits one parsed statement as ONE mutation — one save, one undo
     * snapshot — deduplicating on `(book, fingerprint)` against what the vault
     * already holds. Returns imported to duplicate counts for the report.
     */
    fun importBankTxns(book: String, rows: List<BankTxnEntity>): Pair<Int, Int> {
        val trimmedBook = book.trim().ifEmpty { "Bank" }
        var imported = 0
        var duplicates = 0
        mutate { b ->
            val seen = b.bankTxns
                .mapTo(HashSet()) { it.book.trim().lowercase() to it.fingerprint }
            var next = (b.bankTxns.maxOfOrNull { it.id } ?: 0L) + 1
            val fresh = ArrayList<BankTxnEntity>(rows.size)
            val now = System.currentTimeMillis()
            for (row in rows) {
                val key = trimmedBook.lowercase() to row.fingerprint
                if (!seen.add(key)) {
                    duplicates++
                    continue
                }
                fresh.add(row.copy(id = next++, book = trimmedBook, timestamp = now))
                imported++
            }
            b.copy(bankTxns = b.bankTxns + fresh)
        }
        return imported to duplicates
    }

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
        panels.closeAll()
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
