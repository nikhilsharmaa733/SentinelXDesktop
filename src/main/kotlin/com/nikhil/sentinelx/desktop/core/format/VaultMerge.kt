package com.nikhil.sentinelx.desktop.core.format

/**
 * Folds an imported [MasterBackup] into an existing one instead of replacing it.
 *
 * ⚠️ MIRRORED FILE. `SentinelX/app/src/main/java/com/nikhil/sentinelx/data/VaultMerge.kt`
 * is the same logic against the Room entities. The two must agree record-for-record, or
 * a merge performed on the phone and the same merge performed on the desktop produce
 * different vaults. Change both in the same pair of commits, as with [MasterBackup].
 *
 * ## What "already exists" means
 *
 * Not the primary key — `id` is a per-device autoincrement, so the same login can be
 * `id = 4` on the phone and `id = 17` on the desktop, while two unrelated records can
 * share an id. Identity is instead the **unique index the Room schema already declares**
 * for each table:
 *
 * | Section      | Identity                                   |
 * |--------------|--------------------------------------------|
 * | Logins       | `siteName` + `username`                    |
 * | Cards        | `label1` + `label2`                        |
 * | Chronicles   | `title`                                    |
 * | Notes        | `title`                                    |
 * | Note folders | `name`                                     |
 * | Accounts     | `name`                                     |
 * | Ledger       | account + `title` + `amount` + `timestamp` |
 * | Cash Book    | date + slot + direction + amount + text    |
 * | Bank Book    | `book` + `fingerprint`                     |
 *
 * Using anything else would let the merge emit a list the database then silently
 * collapses: every `@Insert` in this project is `OnConflictStrategy.REPLACE`, so a
 * duplicate does not fail loudly, it eats a row. The engine's contract is therefore
 * that **the list it returns is already unique on that key** — which is why
 * [DuplicatePolicy.KEEP_BOTH] has to rename rather than simply append.
 *
 * Matching is case-insensitive and trims whitespace, deliberately stricter than
 * SQLite's default BINARY collation: a human importing "Gmail" over "gmail" means the
 * same site. Being stricter only ever merges more; it can never violate the index.
 *
 * ## Identical vs conflicting
 *
 * Two records sharing an identity are *identical* when every other field matches too,
 * and *conflicting* when they do not. Identical records are dropped silently — there is
 * nothing to decide. Only conflicts consult [DuplicatePolicy].
 *
 * `timestamp` is excluded from the comparison on logins, cards, chronicles, notes and
 * accounts. The same card typed by hand into both apps differs only in when it was
 * typed, and making the user adjudicate that is noise. Where a timestamp genuinely
 * distinguishes two records — ledger rows, cash entries — it is part of the identity
 * instead, so it still counts.
 */
object VaultMerge {

    /** How an import treats the vault it lands in. */
    enum class Mode {
        /** Clear the sections the archive carries, then write the archive. */
        REPLACE,

        /** Keep what is there and fold the archive in, record by record. */
        MERGE
    }

    /** What to do when a record already exists but its contents differ. */
    enum class DuplicatePolicy {
        /** Keep the copy already in the vault; drop the incoming one. */
        SKIP,

        /** Take the incoming copy. */
        OVERWRITE,

        /** Keep both, marking the incoming one so it can satisfy the unique index. */
        KEEP_BOTH
    }

    /** Suffix that lets a kept-both record clear the unique index it would otherwise hit. */
    const val IMPORT_MARK = "(imported)"

    // ── Reporting ─────────────────────────────────────────────────────────────

    /**
     * What a merge would do to one section. Deliberately independent of
     * [DuplicatePolicy] — the user reads these numbers *before* choosing one.
     */
    data class SectionStats(
        val section: String,
        val existing: Int = 0,
        val incoming: Int = 0,
        /** No counterpart in the vault. Always imported, whatever the policy. */
        val fresh: Int = 0,
        /** Already present, field for field. Never imported, whatever the policy. */
        val identical: Int = 0,
        /** Present but different. The policy decides these, and only these. */
        val conflicting: Int = 0
    )

    data class Plan(val sections: List<SectionStats>) {
        val fresh: Int get() = sections.sumOf { it.fresh }
        val identical: Int get() = sections.sumOf { it.identical }
        val conflicting: Int get() = sections.sumOf { it.conflicting }
        val incoming: Int get() = sections.sumOf { it.incoming }
    }

    data class Result(val vault: MasterBackup, val plan: Plan)

    // ── Entry points ──────────────────────────────────────────────────────────

    /**
     * Classifies [incoming] against [current] without changing anything, so the counts
     * can be shown before the user commits. The policy cannot affect these numbers, so
     * any policy yields the same plan.
     */
    fun preview(current: MasterBackup, incoming: MasterBackup): Plan =
        merge(current, incoming, DuplicatePolicy.SKIP).plan

    /** Dispatches to [merge] or [replace]. */
    fun apply(
        current: MasterBackup,
        incoming: MasterBackup,
        mode: Mode,
        policy: DuplicatePolicy
    ): Result = when (mode) {
        Mode.REPLACE -> replace(current, incoming)
        Mode.MERGE -> merge(current, incoming, policy)
    }

    /**
     * Clears only the sections [incoming] carries and writes them from the archive.
     *
     * For a full archive this is the old wholesale behaviour; for a scoped one it is
     * what makes "replace just my logins" possible without touching anything else.
     */
    fun replace(current: MasterBackup, incoming: MasterBackup): Result {
        val carried = incoming.carriedSections().toSet()
        val plan = Plan(
            VaultSection.ALL.filter { it in carried }.map { section ->
                SectionStats(
                    section = section,
                    existing = current.countIn(section),
                    incoming = incoming.countIn(section),
                    fresh = incoming.countIn(section)
                )
            }
        )
        return Result(
            vault = MasterBackup(
                logins = pick(carried, VaultSection.LOGINS, incoming.logins, current.logins),
                artifacts = pick(carried, VaultSection.CARDS, incoming.artifacts, current.artifacts),
                chronicles = pick(carried, VaultSection.CHRONICLES, incoming.chronicles, current.chronicles),
                prophecies = pick(carried, VaultSection.NOTES, incoming.prophecies, current.prophecies),
                noteFolders = pick(carried, VaultSection.NOTES, incoming.noteFolders, current.noteFolders),
                ledger = pick(carried, VaultSection.LEDGER, incoming.ledger, current.ledger),
                accounts = pick(carried, VaultSection.LEDGER, incoming.accounts, current.accounts),
                cashBook = pick(carried, VaultSection.CASHBOOK, incoming.cashBook, current.cashBook),
                bankTxns = pick(carried, VaultSection.BANK, incoming.bankTxns, current.bankTxns),
                sections = null,
                timestamp = System.currentTimeMillis()
            ),
            plan = plan
        )
    }

    /**
     * Folds [incoming] into [current], touching only the sections [incoming] carries.
     *
     * A logins-only archive leaves the ledger exactly as it was — including its
     * `accountId` values, which is why the remap below runs only when the ledger
     * section is actually in play.
     */
    fun merge(
        current: MasterBackup,
        incoming: MasterBackup,
        policy: DuplicatePolicy
    ): Result {
        val carried = incoming.carriedSections().toSet()
        val stats = mutableListOf<SectionStats>()

        val logins = if (VaultSection.LOGINS in carried) {
            reconcile(
                VaultSection.LOGINS, current.logins, incoming.logins, policy,
                identity = { join(norm(it.siteName), norm(it.username)) },
                fingerprint = { join(it.siteName, it.username, it.password) },
                rename = { login, n -> login.copy(siteName = mark(login.siteName, n)) }
            ).also { stats += it.stats }.merged
        } else current.logins

        val artifacts = if (VaultSection.CARDS in carried) {
            reconcile(
                VaultSection.CARDS, current.artifacts, incoming.artifacts, policy,
                identity = { join(norm(it.label1), norm(it.label2)) },
                fingerprint = {
                    join(
                        it.type, it.label1, it.label2, it.label3, it.label4, it.label5,
                        it.label6, it.secret, it.frontImageUri, it.backImageUri
                    )
                },
                rename = { card, n -> card.copy(label1 = mark(card.label1, n)) }
            ).also { stats += it.stats }.merged
        } else current.artifacts

        val chronicles = if (VaultSection.CHRONICLES in carried) {
            reconcile(
                VaultSection.CHRONICLES, current.chronicles, incoming.chronicles, policy,
                identity = { norm(it.title) },
                fingerprint = { join(it.title, it.year, it.authority, it.pages, it.frontImageUri) },
                rename = { volume, n -> volume.copy(title = mark(volume.title, n)) }
            ).also { stats += it.stats }.merged
        } else current.chronicles

        val notes = if (VaultSection.NOTES in carried) {
            mergeNotes(current, incoming, policy).also { stats += it.stats }
        } else null

        val cashBook = if (VaultSection.CASHBOOK in carried) {
            reconcile(
                VaultSection.CASHBOOK, current.cashBook, incoming.cashBook, policy,
                identity = {
                    join(it.book, it.entryDate, it.direction, it.slot, it.amount, norm(it.particulars))
                },
                fingerprint = {
                    join(
                        it.book, it.entryDate, it.direction, it.slot, it.amount, it.denominations,
                        it.particulars, it.countedBy, it.verifiedBy, it.status,
                        it.slipImageUris, it.notes
                    )
                },
                // `cash_book` carries no unique index — two genuinely identical handovers
                // in one day are legitimate — so a kept-both entry goes in untouched and
                // a second copy is never mistaken for something that needs adjudicating.
                rename = { entry, _ -> entry },
                unique = false
            ).also { stats += it.stats }.merged
        } else current.cashBook

        val bankTxns = if (VaultSection.BANK in carried) {
            reconcile(
                VaultSection.BANK, current.bankTxns, incoming.bankTxns, policy,
                // The fingerprint IS the row's identity within its book — that is
                // the whole point of computing it at import time. The book stays
                // outside the hash so renaming one never rewrites fingerprints.
                identity = { join(norm(it.book), it.fingerprint) },
                fingerprint = {
                    join(
                        it.book, it.txnDate, it.narration, it.amount, it.direction,
                        it.balance, it.mode, it.channel, it.reference, it.party,
                        it.remark, it.bankName, it.category, it.fingerprint
                    )
                },
                // A kept-both row marks the fingerprint itself; the narration and
                // every user-visible field stay untouched.
                rename = { txn, n -> txn.copy(fingerprint = mark(txn.fingerprint, n)) }
            ).also { stats += it.stats }.merged
        } else current.bankTxns

        val ledger = if (VaultSection.LEDGER in carried) {
            mergeLedger(current, incoming, policy).also { stats += it.stats }
        } else null

        return Result(
            vault = MasterBackup(
                logins = logins,
                artifacts = artifacts,
                chronicles = chronicles,
                prophecies = notes?.prophecies ?: current.prophecies,
                noteFolders = notes?.folders ?: current.noteFolders,
                ledger = ledger?.transactions ?: current.ledger,
                accounts = ledger?.accounts ?: current.accounts,
                cashBook = cashBook,
                bankTxns = bankTxns,
                sections = null,
                timestamp = System.currentTimeMillis()
            ),
            plan = Plan(VaultSection.ALL.mapNotNull { s -> stats.firstOrNull { it.section == s } })
        )
    }

    // ── Notes ─────────────────────────────────────────────────────────────────

    private data class NotesMerge(
        val folders: List<FolderEntity>,
        val prophecies: List<ProphecyEntity>,
        val stats: SectionStats
    )

    /**
     * Folders and notes in one step, because a note references its folder by *name*.
     *
     * A kept-both folder lands under a marked name, so its incoming notes have to
     * follow it there — otherwise they would land in the folder it collided with and
     * inherit that folder's lock (or lose their own). And an incoming note whose
     * folder differs only in case from a surviving record snaps onto the record's
     * spelling, keeping "work" and "Work" one folder. Merging the two lists
     * independently would silently do both wrong things.
     *
     * Notes whose folder has no record on either side (implicit folders, and every
     * pre-v9 archive) pass through untouched.
     */
    private fun mergeNotes(
        current: MasterBackup,
        incoming: MasterBackup,
        policy: DuplicatePolicy
    ): NotesMerge {
        val folderResult = reconcile(
            VaultSection.NOTES, current.noteFolders, incoming.noteFolders, policy,
            identity = { norm(it.name) },
            fingerprint = {
                join(it.name, it.colorHex, it.glyph, it.isLocked, it.passcodeHash, it.passcodeSalt)
            },
            rename = { folder, n -> folder.copy(name = mark(folder.name, n)) }
        )

        val folders = folderResult.merged
        // Canonical spelling for every folder identity that has a record.
        val displayByIdentity = HashMap<String, String>()
        folders.forEach { displayByIdentity.putIfAbsent(norm(it.name), it.name) }

        // Identity the incoming folder arrived under → identity it was stored under.
        val landedAs = folderResult.renamed
        val remapped = incoming.prophecies.map { note ->
            val key = norm(note.folder)
            if (key.isEmpty()) note
            else {
                val landed = landedAs[key] ?: key
                note.copy(folder = displayByIdentity[landed] ?: note.folder)
            }
        }

        val noteResult = reconcile(
            VaultSection.NOTES, current.prophecies, remapped, policy,
            identity = { norm(it.title) },
            // noteType() rather than the raw field: the two apps' Gson defaults
            // differ for an absent `type` (null here, "TEXT" on the JVM's no-arg
            // constructor), and the normalised accessor keeps the same archive
            // classifying identically on both.
            fingerprint = {
                join(
                    it.title, it.content, it.sigil, it.noteType(), it.isPinned,
                    it.isArchived, it.isLocked, it.colorHex, it.checkItems, it.folder
                )
            },
            rename = { note, n -> note.copy(title = mark(note.title, n)) }
        )

        // One line in the plan, like the ledger: a folder and its notes arrive together.
        return NotesMerge(
            folders = folders,
            prophecies = noteResult.merged,
            stats = SectionStats(
                section = VaultSection.NOTES,
                existing = current.prophecies.size + current.noteFolders.size,
                incoming = incoming.prophecies.size + incoming.noteFolders.size,
                fresh = folderResult.stats.fresh + noteResult.stats.fresh,
                identical = folderResult.stats.identical + noteResult.stats.identical,
                conflicting = folderResult.stats.conflicting + noteResult.stats.conflicting
            )
        )
    }

    // ── Ledger ────────────────────────────────────────────────────────────────

    private data class LedgerMerge(
        val accounts: List<AccountEntity>,
        val transactions: List<TransactionEntity>,
        val stats: SectionStats
    )

    /**
     * Accounts and transactions in one step, because `accountId` is device-local.
     *
     * An incoming transaction points at an incoming account id that means nothing in
     * this vault. It is resolved through the account's *name* — the one thing both sides
     * agree on — to whichever account the merged list ends up holding. Merging the two
     * lists independently would leave every imported transaction filed against a
     * stranger's account, or against nothing at all.
     */
    private fun mergeLedger(
        current: MasterBackup,
        incoming: MasterBackup,
        policy: DuplicatePolicy
    ): LedgerMerge {
        // An overwritten account keeps the id it already had, so the transactions
        // already filed against it do not come unmoored.
        val accountResult = reconcile(
            VaultSection.LEDGER, current.accounts, incoming.accounts, policy,
            identity = { norm(it.name) },
            fingerprint = { join(it.name, it.colorHex, it.sigilType) },
            rename = { account, n -> account.copy(name = mark(account.name, n)) },
            onOverwrite = { existing, candidate -> candidate.copy(id = existing.id) }
        )

        // Accounts that arrived with the archive need ids that are free here: the ones
        // they came with can collide with an unrelated local account. `taken` starts
        // empty and fills as the merged list is walked — seeding it from the local
        // accounts would make every local account collide with itself, get renumbered,
        // and leave the transactions already filed against it pointing at a stranger.
        val taken = HashSet<Long>()
        var nextId = ((current.accounts + incoming.accounts).maxOfOrNull { it.id } ?: 0L) + 1
        val accounts = accountResult.merged.map { account ->
            if (account.id != 0L && taken.add(account.id)) account
            else account.copy(id = nextId++).also { taken.add(it.id) }
        }

        val idByName = accounts.associateBy({ norm(it.name) }, { it.id })

        // A kept-both account landed under a marked name, so its transactions have to
        // follow it there rather than piling onto the account it collided with.
        val landedAs = accountResult.renamed
        val nameByIncomingId = incoming.accounts.associateBy(
            { it.id },
            { landedAs[norm(it.name)] ?: norm(it.name) }
        )

        val remapped = incoming.ledger.mapNotNull { tx ->
            val target = nameByIncomingId[tx.accountId]?.let { idByName[it] } ?: return@mapNotNull null
            tx.copy(accountId = target)
        }

        val txResult = reconcile(
            VaultSection.LEDGER, current.ledger, remapped, policy,
            identity = { join(it.accountId, norm(it.title), it.amount, it.timestamp) },
            fingerprint = {
                join(
                    it.accountId, it.title, it.amount, it.isIncoming, it.category,
                    it.timestamp, it.isSettled, it.billImageUris
                )
            },
            rename = { tx, n -> tx.copy(title = mark(tx.title, n)) }
        )

        // Reported as one line, so the counts are the two sets added together: an
        // imported account and its imported rows are one thing arriving.
        return LedgerMerge(
            accounts = accounts,
            transactions = txResult.merged,
            stats = SectionStats(
                section = VaultSection.LEDGER,
                existing = current.ledger.size + current.accounts.size,
                incoming = incoming.ledger.size + incoming.accounts.size,
                fresh = accountResult.stats.fresh + txResult.stats.fresh,
                identical = accountResult.stats.identical + txResult.stats.identical,
                conflicting = accountResult.stats.conflicting + txResult.stats.conflicting
            )
        )
    }

    // ── The engine ────────────────────────────────────────────────────────────

    private data class Reconciled<T>(
        val merged: List<T>,
        val stats: SectionStats,
        /** Identity the record arrived with → identity it was actually stored under. */
        val renamed: Map<String, String>
    )

    /**
     * The whole merge, for one list.
     *
     * Runs in two passes on purpose. A single greedy pass pairing each incoming record
     * with the first free counterpart of the same identity would, given incoming
     * `[edited, unchanged]` against existing `[unchanged]`, spend the counterpart on
     * `edited` and then declare `unchanged` brand new — quietly duplicating a row that
     * was already there. Letting exact matches claim first makes the outcome independent
     * of the order records happen to sit in the archive.
     *
     * Counting free counterparts rather than asking "does one exist" is what preserves
     * legitimate duplicates: two identical cash entries in the archive against one in
     * the vault correctly yields one identical and one fresh.
     *
     * [unique] says whether the table behind this list carries a unique index. It decides
     * what happens when an incoming record finds no *free* counterpart but its key is
     * already present — every counterpart having been claimed by an exact match earlier
     * in the same import. Where the index exists, appending would emit a list the
     * database silently collapses, so the record is a conflict and the policy rules.
     * Where it does not (the cash book), a second identical handover is a real event and
     * is simply kept.
     */
    private fun <T> reconcile(
        section: String,
        existing: List<T>,
        incoming: List<T>,
        policy: DuplicatePolicy,
        identity: (T) -> String,
        fingerprint: (T) -> String,
        rename: (T, Int) -> T,
        unique: Boolean = true,
        onOverwrite: (existing: T, incoming: T) -> T = { _, candidate -> candidate }
    ): Reconciled<T> {
        val merged = ArrayList<T>(existing)
        // identity → indices into `merged` not yet claimed by an incoming record
        val free = HashMap<String, MutableList<Int>>()
        // identity → the index that holds it, for overwriting a row whose counterpart
        // has already been claimed. First writer wins; the list is unique on this key.
        val holder = HashMap<String, Int>()
        existing.forEachIndexed { i, item ->
            val key = identity(item)
            free.getOrPut(key) { mutableListOf() }.add(i)
            holder.putIfAbsent(key, i)
        }

        var identical = 0
        val undecided = ArrayList<T>()

        // Pass 1 — exact matches claim their counterpart.
        incoming.forEach { candidate ->
            val slots = free[identity(candidate)]
            val hit = slots?.indexOfFirst { fingerprint(merged[it]) == fingerprint(candidate) } ?: -1
            if (hit >= 0) {
                slots!!.removeAt(hit)
                identical++
            } else {
                undecided += candidate
            }
        }

        // Pass 2 — whatever is left is new, or a changed version of something we hold.
        var fresh = 0
        var conflicting = 0
        val renamed = HashMap<String, String>()

        fun append(record: T) {
            merged += record
            holder.putIfAbsent(identity(record), merged.lastIndex)
        }

        undecided.forEach { candidate ->
            val key = identity(candidate)
            val slot = free[key]?.removeFirstOrNull()

            if (slot == null && !(unique && key in holder)) {
                fresh++
                append(candidate)
                return@forEach
            }

            conflicting++
            when (policy) {
                DuplicatePolicy.SKIP -> Unit
                DuplicatePolicy.OVERWRITE -> {
                    val target = slot ?: holder.getValue(key)
                    merged[target] = onOverwrite(merged[target], candidate)
                }
                DuplicatePolicy.KEEP_BOTH -> {
                    val marked = uniquify(candidate, rename, identity, holder.keys)
                    renamed[key] = identity(marked)
                    append(marked)
                }
            }
        }

        return Reconciled(
            merged = merged,
            stats = SectionStats(section, existing.size, incoming.size, fresh, identical, conflicting),
            renamed = renamed
        )
    }

    /**
     * Marks a kept-both record until its identity is free: `… (imported)`, then
     * `… (imported 2)`, in the manner of a file manager.
     *
     * Every table but `cash_book` carries a unique index, and every `@Insert` in the
     * project is `OnConflictStrategy.REPLACE` — appending a colliding row therefore does
     * not raise an error, it deletes the row it collides with. A "keep both" that
     * silently keeps one would be the exact bug this feature exists to remove.
     *
     * When [rename] is the identity function the record has no index to satisfy (the
     * cash book), and the candidate goes in untouched.
     */
    private fun <T> uniquify(
        candidate: T,
        rename: (T, Int) -> T,
        identity: (T) -> String,
        used: Set<String>
    ): T {
        var attempt = 1
        while (attempt < 1000) {
            val marked = rename(candidate, attempt)
            if (identity(marked) == identity(candidate)) return candidate
            if (identity(marked) !in used) return marked
            attempt++
        }
        return rename(candidate, attempt)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun <T> pick(carried: Set<String>, section: String, incoming: T, current: T): T =
        if (section in carried) incoming else current

    private fun norm(value: String?): String = value?.trim()?.lowercase() ?: ""

    private fun mark(value: String, n: Int): String =
        if (n <= 1) "${value.trim()} $IMPORT_MARK" else "${value.trim()} (imported $n)"

    /** NUL-joined so a field ending where the next begins cannot forge a match. */
    private fun join(vararg parts: Any?): String =
        parts.joinToString("\u0000") { it?.toString() ?: "" }
}
