package com.nikhil.sentinelx.desktop.core.format

/**
 * The `.sxv` payload schema — mirrors `MasterBackup` and the six Room entities in
 * the Android app, with the Room annotations dropped.
 *
 * ⚠️ FIELD NAMES ARE THE WIRE FORMAT. Gson serialises by property name, so renaming
 * any property here silently breaks compatibility with the phone: the field simply
 * arrives absent and is filled with a default, losing data with no error. If a name
 * changes on Android, it must change here in the same commit.
 *
 * Types match the Android side exactly, including the Int/Long split on `id`
 * (logins/artifacts/chronicles/prophecies use Int; ledger/accounts use Long).
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
    val version: Int = 6,
    val timestamp: Long = 0L
)

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

// ── Separator helpers ────────────────────────────────────────────────────────
// The phone stores image lists as delimited strings inside a single column. Both
// delimiters can legitimately appear in user text elsewhere, so parsing is kept in
// one place rather than scattered through the UI.

fun ChronicleEntity.pageFilenames(): List<String> =
    pages.split('|').filter { it.isNotBlank() }

fun TransactionEntity.billFilenames(): List<String> =
    billImageUris?.split(',')?.filter { it.isNotBlank() } ?: emptyList()

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
}
