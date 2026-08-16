package com.nikhil.sentinelx.desktop.core.format

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.abs

/**
 * The `.sxv` payload schema — mirrors `MasterBackup` and the Room entities in
 * the Android app, with the Room annotations dropped.
 *
 * ⚠️ FIELD NAMES ARE THE WIRE FORMAT. Gson serialises by property name, so renaming
 * any property here silently breaks compatibility with the phone: the field simply
 * arrives absent and is filled with a default, losing data with no error. If a name
 * changes on Android, it must change here in the same commit.
 *
 * Types match the Android side exactly, including the Int/Long split on `id`
 * (logins/artifacts/chronicles/prophecies use Int; ledger/accounts/cashBook use Long).
 *
 * Every property has a default. Gson does not run Kotlin constructors the way the
 * compiler would, and a missing JSON field on a non-null property would otherwise
 * yield a null in a `String` — a NullPointerException much later, far from the
 * cause. Defaults make a truncated or older archive degrade instead of crashing.
 */
data class MasterBackup(
    val logins: List<LoginEntity> = emptyList(),
    val artifacts: List<ArtifactEntity> = emptyList(),
    val chronicles: List<ChronicleEntity> = emptyList(),
    val prophecies: List<ProphecyEntity> = emptyList(),
    val ledger: List<TransactionEntity> = emptyList(),
    val accounts: List<AccountEntity> = emptyList(),
    /** Daily cash handover / balance sheet. Added in v7; absent from v6 archives. */
    val cashBook: List<CashEntryEntity> = emptyList(),
    /**
     * Bank transactions imported from statements. Additive like the notes-v8
     * fields — the archive version does NOT move: an old build drops the key,
     * a new build reads an old archive as an empty bank book.
     */
    val bankTxns: List<BankTxnEntity> = emptyList(),
    /**
     * Note folders — colour, glyph, lock, passcode gate. Travels with the NOTES
     * section (a folder without its notes is a label; its notes without the folder
     * lose their lock). Absent from pre-v9 archives, in which case folders exist only
     * implicitly through the `folder` strings on the notes themselves, unlocked.
     */
    val noteFolders: List<FolderEntity> = emptyList(),
    /**
     * Which sections this archive claims to carry, as [VaultSection] constants.
     *
     * Null means "the whole vault" — that is what every archive written before v8 is,
     * and what a full export still writes. A *scoped* export ("just my logins") names
     * only the sections it holds, so a Replace import can clear those and leave the
     * rest of the vault alone. Without this, a logins-only file would be
     * indistinguishable from a full backup that happens to have nothing else in it,
     * and replacing from it would wipe the ledger.
     *
     * Added in v8. A pre-v8 build reading a v8 scoped archive ignores this field and
     * treats it as a full backup, which is why scoped exports are named
     * `Sentinel_<Section>_*.sxv` — the mistake is at least visible before it is made.
     */
    val sections: List<String>? = null,
    val version: Int = 8,
    val timestamp: Long = 0L
)

/**
 * The units an import or export can be scoped to.
 *
 * Accounts and ledger rows are one section, not two: a transaction without its account
 * is an orphan, and a merge has to resolve both together to remap `accountId`.
 */
object VaultSection {
    const val LOGINS = "LOGINS"
    const val CARDS = "CARDS"
    const val NOTES = "NOTES"
    const val CHRONICLES = "CHRONICLES"
    const val LEDGER = "LEDGER"
    const val CASHBOOK = "CASHBOOK"
    const val BANK = "BANK"

    val ALL = listOf(LOGINS, CARDS, NOTES, CHRONICLES, LEDGER, CASHBOOK, BANK)

    fun label(section: String): String = when (section) {
        LOGINS -> "Logins"
        CARDS -> "Cards & Documents"
        NOTES -> "Notes"
        CHRONICLES -> "Chronicles"
        LEDGER -> "Ledger & Accounts"
        CASHBOOK -> "Cash Book"
        BANK -> "Bank Book"
        else -> section
    }
}

/** What this archive actually carries. Absent (pre-v8) means the whole vault. */
fun MasterBackup.carriedSections(): List<String> =
    sections?.takeIf { it.isNotEmpty() } ?: VaultSection.ALL

/** How many records this archive holds in [section]. */
fun MasterBackup.countIn(section: String): Int = when (section) {
    VaultSection.LOGINS -> logins.size
    VaultSection.CARDS -> artifacts.size
    // Folders count with their notes, the way accounts count with their ledger rows:
    // one section, arriving together.
    VaultSection.NOTES -> prophecies.size + noteFolders.size
    VaultSection.CHRONICLES -> chronicles.size
    VaultSection.LEDGER -> ledger.size + accounts.size
    VaultSection.CASHBOOK -> cashBook.size
    VaultSection.BANK -> bankTxns.size
    else -> 0
}

/**
 * A copy holding only [wanted], tagged with what it carries — the shape a scoped
 * export writes. A full-vault export stays untagged so older builds keep reading it.
 */
fun MasterBackup.scopedTo(wanted: Collection<String>): MasterBackup {
    val keep = wanted.toSet()
    return MasterBackup(
        logins = if (VaultSection.LOGINS in keep) logins else emptyList(),
        artifacts = if (VaultSection.CARDS in keep) artifacts else emptyList(),
        chronicles = if (VaultSection.CHRONICLES in keep) chronicles else emptyList(),
        prophecies = if (VaultSection.NOTES in keep) prophecies else emptyList(),
        ledger = if (VaultSection.LEDGER in keep) ledger else emptyList(),
        accounts = if (VaultSection.LEDGER in keep) accounts else emptyList(),
        cashBook = if (VaultSection.CASHBOOK in keep) cashBook else emptyList(),
        bankTxns = if (VaultSection.BANK in keep) bankTxns else emptyList(),
        noteFolders = if (VaultSection.NOTES in keep) noteFolders else emptyList(),
        sections = if (keep.containsAll(VaultSection.ALL)) null
        else VaultSection.ALL.filter { it in keep },
        timestamp = System.currentTimeMillis()
    )
}

data class LoginEntity(
    val id: Int = 0,
    val siteName: String = "",
    val username: String = "",
    val password: String = ""
)

data class ArtifactEntity(
    val id: Int = 0,
    val type: String = "",
    val label1: String = "",
    val label2: String = "",
    val label3: String = "",
    val label4: String? = null,
    val label5: String? = null,
    val label6: String? = null,
    val secret: String = "",
    val frontImageUri: String? = null,
    val backImageUri: String? = null,
    val timestamp: Long = 0L
)

data class ChronicleEntity(
    val id: Int = 0,
    val title: String = "",
    val year: String = "",
    val authority: String = "",
    /** Pipe-separated image filenames, e.g. "IMG_a.webp|IMG_b.webp". */
    val pages: String = "",
    val frontImageUri: String? = null,
    val timestamp: Long = 0L
)

/**
 * One note — plain text or a checklist.
 *
 * The v8 fields are all nullable or defaulted primitives, so a pre-v8 archive
 * degrades to safe defaults and a v8 archive read by an old build simply drops them.
 * `title` is the note's identity (the phone's unique index and the merge key); the
 * editor blocks duplicates before save for the same reason the phone does.
 *
 * For checklist notes, [checkItems] is authoritative and [content] is a plain-text
 * mirror regenerated on every save ([itemsToText]) — it is what search, copy and
 * pre-v8 builds read. Anything that mutates the checklist must rewrite both.
 */
data class ProphecyEntity(
    val id: Int = 0,
    val title: String = "",
    val content: String = "",
    val sigil: String = "GENERAL",
    val timestamp: Long = 0L,
    /** [Notes.TYPE_TEXT] or [Notes.TYPE_CHECKLIST]. Read through [noteType], never raw. */
    val type: String? = Notes.TYPE_TEXT,
    val isPinned: Boolean = false,
    val isArchived: Boolean = false,
    /** On the phone this demands a biometric pass; here it hides the body until revealed. */
    val isLocked: Boolean = false,
    /** Per-note colour as `#RRGGBB`, independent of the sigil. Null = default surface. */
    val colorHex: String? = null,
    /** JSON array of checklist items, e.g. `[{"text":"Milk","done":false}]`. */
    val checkItems: String? = null,
    /** Free-text folder name. A filter tag, not a second table — the `book` pattern. */
    val folder: String? = null
)

data class TransactionEntity(
    val id: Long = 0L,
    val accountId: Long = 0L,
    val title: String = "",
    val amount: Double = 0.0,
    val isIncoming: Boolean = false,
    val category: String = "MISC",
    val timestamp: Long = 0L,
    val isSettled: Boolean = false,
    /** Comma-separated image filenames, e.g. "bill_1.webp,bill_2.webp". */
    val billImageUris: String? = null
)

data class AccountEntity(
    val id: Long = 0L,
    val name: String = "",
    val colorHex: String = "",
    val sigilType: String = "",
    val timestamp: Long = 0L
)

/**
 * One movement of physical cash — the nightly "brought the takings home", the morning
 * "carried them back", or any ordinary balance-sheet line.
 *
 * `amount` is the authoritative figure. Every total, balance and export reads it and
 * never parses [denominations], so a malformed breakdown costs you the note-by-note
 * detail and nothing else — the money is still right. That asymmetry is deliberate.
 */
data class CashEntryEntity(
    val id: Long = 0L,
    /** Free-text book name. A filter tag, not a second table — see [CashBook.DEFAULT_BOOK]. */
    val book: String = CashBook.DEFAULT_BOOK,
    /** The business date, normalised to local midnight by [normaliseToBusinessDate]. */
    val entryDate: Long = 0L,
    /** [CashBook.IN] — cash enters this book's custody — or [CashBook.OUT]. */
    val direction: String = CashBook.IN,
    /** [CashBook.SLOT_EVENING] / [CashBook.SLOT_MORNING] / [CashBook.SLOT_OTHER]. */
    val slot: String = CashBook.SLOT_OTHER,
    val amount: Double = 0.0,
    /** JSON object of denomination to count, e.g. `{"500":12,"200":3}`. Null = no breakdown. */
    val denominations: String? = null,
    val particulars: String = "",
    val countedBy: String = "",
    val verifiedBy: String = "",
    /** [CashBook.STATUS_PENDING] or [CashBook.STATUS_VERIFIED]. */
    val status: String = CashBook.STATUS_PENDING,
    /** Comma-separated image filenames — same convention as [TransactionEntity.billImageUris]. */
    val slipImageUris: String? = null,
    val notes: String? = null,
    val timestamp: Long = 0L
)

// ── Separator helpers ────────────────────────────────────────────────────────
// The phone stores image lists as delimited strings inside a single column. Both
// delimiters can legitimately appear in user text elsewhere, so parsing is kept in
// one place rather than scattered through the UI.

fun ChronicleEntity.pageFilenames(): List<String> =
    pages.split('|').filter { it.isNotBlank() }

fun TransactionEntity.billFilenames(): List<String> =
    billImageUris?.split(',')?.filter { it.isNotBlank() } ?: emptyList()

fun CashEntryEntity.slipFilenames(): List<String> =
    slipImageUris?.split(',')?.filter { it.isNotBlank() } ?: emptyList()

/** Every image filename this backup references, for integrity checks against the ZIP. */
fun MasterBackup.referencedImages(): Set<String> = buildSet {
    artifacts.forEach { a ->
        a.frontImageUri?.takeIf { it.isNotBlank() }?.let { add(it) }
        a.backImageUri?.takeIf { it.isNotBlank() }?.let { add(it) }
    }
    chronicles.forEach { c ->
        c.frontImageUri?.takeIf { it.isNotBlank() }?.let { add(it) }
        addAll(c.pageFilenames())
    }
    ledger.forEach { addAll(it.billFilenames()) }
    cashBook.forEach { addAll(it.slipFilenames()) }
}

/**
 * One note folder — a real container, not a filter tag. Mirrors the phone's
 * `FolderEntity` with the Room annotations dropped.
 *
 * **The join to notes is the folder's `name`, not its id** — ids are per-device
 * autoincrements Room reassigns on restore, while the name is already the folder's
 * merge identity (the same reason ledger rows follow their account by name). Renaming
 * a folder therefore MUST rename every member note's `folder` field in the same
 * operation; `AppState.renameFolder` is the only place that does it correctly here.
 *
 * ## What the lock actually is
 *
 * A **privacy gate over the screen, not encryption over the bytes** — the vault is
 * already sealed, and a folder's notes travel in the same archive JSON as everything
 * else. What it buys is that an unlocked, handed-over machine shows nothing of the
 * folder's contents: not in the list, not in search, not in the command palette.
 *
 * - `isLocked` with no passcode → owner check: a biometric prompt on the phone, a
 *   plain reveal-curtain here (the vault password already proved ownership).
 * - `isLocked` + passcode → both apps demand the passcode. The phone's biometric
 *   still opens it, and here the vault master password is the recovery path — a lost
 *   passcode never loses notes on either device.
 */
data class FolderEntity(
    val id: Int = 0,
    val name: String = "",
    /** `#RRGGBB`, same palette as note colours. Null = default gold. */
    val colorHex: String? = null,
    /** One rune from [NoteFolders.GLYPHS]. Null = the default folder rune. */
    val glyph: String? = null,
    val isLocked: Boolean = false,
    /** Hex SHA-256 of "salt:passcode". Null = no passcode set (owner-check lock only). */
    val passcodeHash: String? = null,
    val passcodeSalt: String? = null,
    val timestamp: Long = 0L
)

object NoteFolders {
    /** Runes a folder can wear. First one is the default. */
    val GLYPHS = listOf("ᛝ", "ᚨ", "ᛟ", "ᛞ", "ᚷ", "ᛒ")
    const val DEFAULT_GLYPH = "ᛝ"
    const val MIN_PASSCODE = 4
}

fun FolderEntity.displayGlyph(): String = glyph?.takeIf { it.isNotBlank() } ?: NoteFolders.DEFAULT_GLYPH

fun FolderEntity.hasPasscode(): Boolean = !passcodeHash.isNullOrBlank() && !passcodeSalt.isNullOrBlank()

/**
 * Folder names compare case-insensitively everywhere — "Work" and "work" are one
 * folder — matching `VaultMerge.norm()`. The stored spelling is whatever the folder
 * record (or first note) used; save paths snap notes onto that spelling.
 */
fun folderKey(name: String?): String? = name?.trim()?.takeIf { it.isNotEmpty() }?.lowercase()

// Passcode gate. Deliberately a single salted SHA-256, not a KDF: this hash gates the
// UI of an already-encrypted vault. An attacker who can read the hash is holding the
// decrypted vault and can read the notes next to it — slowing them down here protects
// nothing, while a KDF would add a cross-app parameter to keep in sync.

fun newFolderSalt(): String {
    val bytes = ByteArray(16)
    java.security.SecureRandom().nextBytes(bytes)
    return bytes.joinToString("") { "%02x".format(it) }
}

fun hashFolderPasscode(salt: String, passcode: String): String =
    java.security.MessageDigest.getInstance("SHA-256")
        .digest("$salt:$passcode".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

/** Constant-shape check; never throws. False when no passcode is set. */
fun FolderEntity.verifyPasscode(passcode: String): Boolean {
    val salt = passcodeSalt ?: return false
    val expected = passcodeHash ?: return false
    return runCatching {
        java.security.MessageDigest.isEqual(
            hashFolderPasscode(salt, passcode).toByteArray(Charsets.US_ASCII),
            expected.lowercase().toByteArray(Charsets.US_ASCII)
        )
    }.getOrDefault(false)
}

// ── Notes ────────────────────────────────────────────────────────────────────

/**
 * The vocabulary of [ProphecyEntity]. Persisted strings that cross the `.sxv`
 * boundary, so constants rather than enums — an unrecognised string from a newer
 * build fails a comparison and falls through, where an enum would throw.
 */
object Notes {
    const val TYPE_TEXT = "TEXT"
    const val TYPE_CHECKLIST = "CHECKLIST"
}

/** One checklist entry. Never persisted directly — always through the codec below. */
data class CheckItem(val text: String, val done: Boolean = false)

/**
 * Decodes the checklist. **Never throws** — a corrupt value yields an empty list,
 * costing the checkbox structure while [ProphecyEntity.content] still shows the note
 * as plain lines. Same contract as [decodeDenominations].
 */
fun decodeCheckItems(raw: String?): List<CheckItem> {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching {
        JsonParser.parseString(raw).asJsonArray.mapNotNull { element ->
            runCatching {
                val obj = element.asJsonObject
                CheckItem(
                    text = obj.get("text")?.asString ?: "",
                    done = obj.get("done")?.asBoolean ?: false
                )
            }.getOrNull()
        }
    }.getOrDefault(emptyList())
}

/**
 * Encodes a checklist, or null when it is empty. Built through Gson's tree writer so
 * escaping is correct and the field order is fixed — the same list always produces
 * the same string, which keeps saves and merges from churning on no-ops.
 */
fun List<CheckItem>.encodeCheckItems(): String? {
    if (isEmpty()) return null
    val array = JsonArray()
    forEach { item ->
        val obj = JsonObject()
        obj.addProperty("text", item.text)
        obj.addProperty("done", item.done)
        array.add(obj)
    }
    return array.toString()
}

/**
 * The note's type with the null/unknown fallback applied. Old records and pre-v8
 * archives carry null here; both mean a plain text note. `VaultMerge` fingerprints
 * call this rather than the raw field so the two apps — whose Gson defaults differ —
 * classify the same record identically.
 */
fun ProphecyEntity.noteType(): String =
    if (type == Notes.TYPE_CHECKLIST) Notes.TYPE_CHECKLIST else Notes.TYPE_TEXT

fun ProphecyEntity.isChecklist(): Boolean = noteType() == Notes.TYPE_CHECKLIST

fun ProphecyEntity.checklistItems(): List<CheckItem> = decodeCheckItems(checkItems)

/** Fraction complete, 0 when the list is empty. */
fun ProphecyEntity.checklistProgress(): Float {
    val items = checklistItems()
    if (items.isEmpty()) return 0f
    return items.count { it.done }.toFloat() / items.size
}

/** The folder, normalised: null for blank so "" and null cannot become two folders. */
fun ProphecyEntity.folderName(): String? = folder?.trim()?.takeIf { it.isNotEmpty() }

/** Everything live search should look through — title, body and checklist lines. */
fun ProphecyEntity.matchesQuery(query: String): Boolean {
    if (query.isBlank()) return true
    return title.contains(query, true) ||
        content.contains(query, true) ||
        (folderName()?.contains(query, true) ?: false) ||
        checklistItems().any { it.text.contains(query, true) }
}

// Text ⇄ checklist conversion. Round-trip safe: a done item becomes a "✓ " line and
// comes back done. This is also the plain-text mirror written into `content` for
// checklist notes, so a pre-v8 build (or a corrupt checkItems) still shows the list.

fun textToItems(text: String): List<CheckItem> =
    text.lines().map { it.trim() }.filter { it.isNotEmpty() }.map { line ->
        if (line.startsWith("✓ ")) CheckItem(line.removePrefix("✓ ").trim(), done = true)
        else CheckItem(line, done = false)
    }

fun itemsToText(items: List<CheckItem>): String =
    items.joinToString("\n") { if (it.done) "✓ ${it.text}" else it.text }

// ── Cash book ────────────────────────────────────────────────────────────────

/**
 * The vocabulary of [CashEntryEntity]. These strings are persisted and cross the
 * `.sxv` boundary, so they are constants rather than enums: an enum that meets an
 * unrecognised name from a newer build throws, while an unrecognised string just
 * fails a comparison and falls through to a sane branch.
 */
object CashBook {
    const val DEFAULT_BOOK = "MAIN"

    /** Cash enters this book's custody. In cash-book terms, a receipt (Dr). */
    const val IN = "IN"

    /** Cash leaves this book's custody. In cash-book terms, a payment (Cr). */
    const val OUT = "OUT"

    /** End of day — the takings come home. Pairs with [SLOT_MORNING]. */
    const val SLOT_EVENING = "EVENING"

    /** Start of day — the money goes back to the office. */
    const val SLOT_MORNING = "MORNING"

    /** Anything outside the daily ritual: a mid-day pickup, an ordinary ledger line. */
    const val SLOT_OTHER = "OTHER"

    const val STATUS_PENDING = "PENDING"
    const val STATUS_VERIFIED = "VERIFIED"

    /**
     * Indian notes and coins, highest first. Adding one costs nothing — denominations
     * are stored as a JSON map, so no schema anywhere changes.
     */
    val DENOMINATIONS = listOf(2000, 500, 200, 100, 50, 20, 10, 5, 2, 1)

    /** The six actually in daily circulation. The rest stay collapsed until used. */
    val COMMON_DENOMINATIONS = listOf(500, 200, 100, 50, 20, 10)

    /** Rupee amounts land on whole units; this only absorbs Double representation drift. */
    const val EPSILON = 0.005
}

/**
 * Business dates are stored as **UTC midnight**, not local midnight.
 *
 * A cash book's date is a calendar day, not an instant, and local midnight would make
 * the same entry read as a different day on a device in another zone. Anything that
 * formats [CashEntryEntity.entryDate] must therefore format it in UTC — rendering it
 * in the local zone is the one trap here, and west of Greenwich it shows the day before.
 */
fun normaliseToBusinessDate(millis: Long): Long =
    Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
        .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

/** The calendar day of a business-date value. Inverse of [normaliseToBusinessDate]. */
fun businessDateOf(millis: Long): LocalDate =
    Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()

/** Today, as a business date. */
fun todayBusinessDate(): Long =
    LocalDate.now().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

fun LocalDate.toBusinessDate(): Long =
    atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

/**
 * Decodes the denomination breakdown. **Never throws** — a corrupt or hand-edited
 * value yields an empty map, which the UI shows as "no breakdown recorded" while
 * [CashEntryEntity.amount] keeps every total correct.
 */
fun decodeDenominations(raw: String?): Map<Int, Int> {
    if (raw.isNullOrBlank()) return emptyMap()
    return runCatching {
        val obj = JsonParser.parseString(raw).asJsonObject
        buildMap {
            obj.entrySet().forEach { (key, value) ->
                val denomination = key.toIntOrNull() ?: return@forEach
                val count = runCatching { value.asInt }.getOrNull() ?: return@forEach
                if (denomination > 0 && count > 0) put(denomination, count)
            }
        }
    }.getOrDefault(emptyMap())
}

/**
 * Encodes a breakdown, or null when nothing was counted. Zero counts are dropped and
 * keys are emitted highest-first, so the same tally always produces the same string —
 * which keeps the vault file from churning on a no-op save.
 */
fun Map<Int, Int>.encodeDenominations(): String? {
    val kept = filter { (denomination, count) -> denomination > 0 && count > 0 }
    if (kept.isEmpty()) return null
    return kept.keys.sortedDescending()
        .joinToString(separator = ",", prefix = "{", postfix = "}") { "\"$it\":${kept.getValue(it)}" }
}

fun CashEntryEntity.denominationCounts(): Map<Int, Int> = decodeDenominations(denominations)

/** Whether a note-by-note tally was recorded at all. */
fun CashEntryEntity.hasBreakdown(): Boolean = denominationCounts().isNotEmpty()

/** What the notes actually add up to. Zero when no breakdown was recorded. */
fun CashEntryEntity.countedTotal(): Double =
    denominationCounts().entries.sumOf { (denomination, count) -> denomination.toDouble() * count }

/** Counted minus declared. Non-zero means the tally disagrees with the stated amount. */
fun CashEntryEntity.reconciliationDifference(): Double = countedTotal() - amount

/**
 * True when the tally matches the amount — or when there is no tally to check, since a
 * plain balance-sheet line has nothing to reconcile against.
 */
fun CashEntryEntity.isReconciled(): Boolean =
    !hasBreakdown() || abs(reconciliationDifference()) < CashBook.EPSILON

fun CashEntryEntity.isIn(): Boolean = direction == CashBook.IN

fun CashEntryEntity.isVerified(): Boolean = status == CashBook.STATUS_VERIFIED

/** Positive for money in, negative for money out, so a plain sum gives the net position. */
fun CashEntryEntity.signedAmount(): Double = if (isIn()) amount else -amount

/** Net cash the book holds after these entries. */
fun List<CashEntryEntity>.netPosition(): Double = sumOf { it.signedAmount() }

/** Total received — the debit column of a traditional cash book. */
fun List<CashEntryEntity>.totalDebit(): Double = filter { it.isIn() }.sumOf { it.amount }

/** Total paid out — the credit column. */
fun List<CashEntryEntity>.totalCredit(): Double = filterNot { it.isIn() }.sumOf { it.amount }

/**
 * Notes currently held, denomination by denomination: everything that came in, less
 * everything that went out. Denominations that net to zero or below are dropped.
 */
fun List<CashEntryEntity>.noteInventory(): Map<Int, Int> {
    val running = mutableMapOf<Int, Int>()
    forEach { entry ->
        val sign = if (entry.isIn()) 1 else -1
        entry.denominationCounts().forEach { (denomination, count) ->
            running[denomination] = (running[denomination] ?: 0) + sign * count
        }
    }
    return running.filterValues { it > 0 }.toSortedMap(compareByDescending { it })
}

// ── Bank book ────────────────────────────────────────────────────────────────

/**
 * One bank transaction, as imported from a statement.
 *
 * [narration] is the untouched original line — the labelled fields beside it
 * ([mode], [party], [reference]…) are *extracted commentary*, chosen by the
 * user in the import wizard, and losing one costs a label while the narration
 * still holds the truth. The same authoritative/commentary split as the cash
 * book's `amount`/`denominations`.
 *
 * [fingerprint] is the dedup identity ([StatementParse.fingerprintOf] — date,
 * amount, direction, reference, narration, balance): re-importing an
 * overlapping statement produces the same fingerprints and the duplicates
 * collapse instead of doubling the book. Merge identity is `(book,
 * fingerprint)` — the book name stays *outside* the hash so renaming a book
 * never rewrites fingerprints. Mirrored as a Room unique index on the phone.
 */
data class BankTxnEntity(
    val id: Long = 0L,
    /** The account this statement belongs to, e.g. "HDFC ••1234". Free text — the `book` pattern. */
    val book: String = "",
    /** Business date, UTC midnight — the cash book's convention. */
    val txnDate: Long = 0L,
    /** The statement's full narration line, never trimmed down. */
    val narration: String = "",
    /** Positive magnitude; [direction] carries the sign. */
    val amount: Double = 0.0,
    /** [BankBook.DEBIT] or [BankBook.CREDIT]. */
    val direction: String = BankBook.DEBIT,
    /** Running balance after this transaction, when the statement had a balance column. */
    val balance: Double? = null,
    /** UPI / NEFT / IMPS / ATM…, extracted from the narration. */
    val mode: String? = null,
    /** P2M (merchant) / P2A / P2P (person). */
    val channel: String? = null,
    /** RRN / UTR / cheque number. */
    val reference: String? = null,
    /** Counterparty name. */
    val party: String? = null,
    /** The free-text remark the payer typed. */
    val remark: String? = null,
    /** Counterparty's bank, when the narration names it. */
    val bankName: String? = null,
    val category: String = BankBook.CAT_OTHER,
    val fingerprint: String = "",
    /** Imported / last-edited at. */
    val timestamp: Long = 0L
)

/**
 * The [BankTxnEntity] vocabulary. The direction and category strings cross the
 * `.sxv` boundary, so constants rather than enums, as everywhere else. The
 * canonical DEBIT/CREDIT strings live in the statement engine's `BankVocab`
 * (the parser is the one place that decides them); these aliases keep format
 * code reading naturally without importing across packages.
 */
object BankBook {
    const val DEBIT = "DEBIT"
    const val CREDIT = "CREDIT"
    const val CAT_OTHER = "Other"
}

fun BankTxnEntity.isCredit(): Boolean = direction == BankBook.CREDIT

/** Positive for money in, negative for money out — a plain sum gives the net. */
fun BankTxnEntity.signedAmount(): Double = if (isCredit()) amount else -amount

/** What a list row leads with: the party if one was extracted, else the narration. */
fun BankTxnEntity.displayParty(): String =
    party?.takeIf { it.isNotBlank() } ?: narration.take(48).ifBlank { "Transaction" }

fun List<BankTxnEntity>.totalIn(): Double = filter { it.isCredit() }.sumOf { it.amount }
fun List<BankTxnEntity>.totalOut(): Double = filterNot { it.isCredit() }.sumOf { it.amount }

/** Book names present, most recently touched first. */
fun List<BankTxnEntity>.bankBooks(): List<String> =
    sortedByDescending { it.timestamp }.map { it.book }.filter { it.isNotBlank() }.distinct()
