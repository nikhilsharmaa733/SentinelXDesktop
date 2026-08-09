package com.nikhil.sentinelx.desktop.core.format

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

    val ALL = listOf(LOGINS, CARDS, NOTES, CHRONICLES, LEDGER, CASHBOOK)

    fun label(section: String): String = when (section) {
        LOGINS -> "Logins"
        CARDS -> "Cards & Documents"
        NOTES -> "Notes"
        CHRONICLES -> "Chronicles"
        LEDGER -> "Ledger & Accounts"
        CASHBOOK -> "Cash Book"
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
    VaultSection.NOTES -> prophecies.size
    VaultSection.CHRONICLES -> chronicles.size
    VaultSection.LEDGER -> ledger.size + accounts.size
    VaultSection.CASHBOOK -> cashBook.size
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

data class ProphecyEntity(
    val id: Int = 0,
    val title: String = "",
    val content: String = "",
    val sigil: String = "GENERAL",
    val timestamp: Long = 0L
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
