package com.nikhil.sentinelx.desktop.core.format

import com.nikhil.sentinelx.desktop.core.format.VaultMerge.DuplicatePolicy
import com.nikhil.sentinelx.desktop.core.format.VaultMerge.Mode
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The bank book's place in the wire format: it must survive an archive round
 * trip, scope correctly, and merge on `(book, fingerprint)` without ever
 * emitting two rows that share that pair — the phone declares it as a unique
 * REPLACE index, so a duplicate would silently eat a transaction.
 */
class BankBookFormatTest {

    private fun txn(
        book: String = "HDFC",
        fingerprint: String,
        narration: String = "UPI/P2M/1/N",
        amount: Double = 100.0,
        category: String = "Other",
        id: Long = 0L
    ) = BankTxnEntity(
        id = id, book = book, txnDate = 1_754_006_400_000, narration = narration,
        amount = amount, direction = BankBook.DEBIT, balance = 900.0,
        mode = "UPI", channel = "P2M", reference = "083743276468",
        party = "RATHOD LAXMAN BHOJU", remark = "NO REM", bankName = "YES BANK",
        category = category, fingerprint = fingerprint, timestamp = 5L
    )

    // ── Archive round trip ───────────────────────────────────────────────────

    @Test
    fun `bank txns survive a sealed archive round trip`() {
        val dir = createTempDirectory("bank-rt").toFile()
        try {
            val file = java.io.File(dir, "t.sxv")
            val original = MasterBackup(bankTxns = listOf(txn(fingerprint = "abc123", id = 7)))
            SxvArchive.write(file, original, emptyMap(), "pw123456".toCharArray())
            val loaded = SxvArchive.read(file, "pw123456".toCharArray())
            assertEquals(original.bankTxns, loaded.backup.bankTxns)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `an archive without the bankTxns key degrades to an empty book`() {
        // What every pre-bank-book archive is: the field is simply absent.
        val gson = com.google.gson.Gson()
        val decoded = gson.fromJson("""{"logins":[],"version":8}""", MasterBackup::class.java)
        assertEquals(emptyList(), decoded.bankTxns)
    }

    // ── Scoping ──────────────────────────────────────────────────────────────

    @Test
    fun `scoping to bank keeps only bank and tags the archive`() {
        val full = MasterBackup(
            logins = listOf(LoginEntity(siteName = "s", username = "u", password = "p")),
            bankTxns = listOf(txn(fingerprint = "f1"))
        )
        val scoped = full.scopedTo(listOf(VaultSection.BANK))
        assertEquals(listOf(VaultSection.BANK), scoped.sections)
        assertEquals(1, scoped.bankTxns.size)
        assertTrue(scoped.logins.isEmpty())
        assertEquals(1, scoped.countIn(VaultSection.BANK))
    }

    @Test
    fun `a full export stays untagged with bank aboard`() {
        val full = MasterBackup(bankTxns = listOf(txn(fingerprint = "f1")))
        assertEquals(null, full.scopedTo(VaultSection.ALL).sections)
    }

    // ── Merge ────────────────────────────────────────────────────────────────

    @Test
    fun `merge keeps identical rows once and adds fresh ones`() {
        val current = MasterBackup(bankTxns = listOf(txn(fingerprint = "aaa", id = 1)))
        val incoming = MasterBackup(
            bankTxns = listOf(txn(fingerprint = "aaa", id = 9), txn(fingerprint = "bbb", id = 10))
        )
        val result = VaultMerge.apply(current, incoming, Mode.MERGE, DuplicatePolicy.SKIP)
        assertEquals(2, result.vault.bankTxns.size)
        val stats = result.plan.sections.first { it.section == VaultSection.BANK }
        assertEquals(1, stats.identical)
        assertEquals(1, stats.fresh)
        assertEquals(0, stats.conflicting)
    }

    @Test
    fun `same fingerprint with edited category is a conflict the policy decides`() {
        val current = MasterBackup(bankTxns = listOf(txn(fingerprint = "aaa", category = "Other", id = 1)))
        val incoming = MasterBackup(bankTxns = listOf(txn(fingerprint = "aaa", category = "Food", id = 2)))

        val skip = VaultMerge.apply(current, incoming, Mode.MERGE, DuplicatePolicy.SKIP)
        assertEquals("Other", skip.vault.bankTxns.single().category)

        val overwrite = VaultMerge.apply(current, incoming, Mode.MERGE, DuplicatePolicy.OVERWRITE)
        assertEquals("Food", overwrite.vault.bankTxns.single().category)

        val both = VaultMerge.apply(current, incoming, Mode.MERGE, DuplicatePolicy.KEEP_BOTH)
        assertEquals(2, both.vault.bankTxns.size)
        // The kept-both copy must not collide on (book, fingerprint).
        val keys = both.vault.bankTxns.map { it.book.lowercase() to it.fingerprint }.toSet()
        assertEquals(2, keys.size)
    }

    @Test
    fun `same fingerprint in a different book is simply fresh`() {
        val current = MasterBackup(bankTxns = listOf(txn(book = "HDFC", fingerprint = "aaa", id = 1)))
        val incoming = MasterBackup(bankTxns = listOf(txn(book = "SBI", fingerprint = "aaa", id = 2)))
        val result = VaultMerge.apply(current, incoming, Mode.MERGE, DuplicatePolicy.SKIP)
        assertEquals(2, result.vault.bankTxns.size)
    }

    @Test
    fun `a scoped bank replace clears only the bank book`() {
        val current = MasterBackup(
            logins = listOf(LoginEntity(id = 1, siteName = "keep", username = "me", password = "x")),
            bankTxns = listOf(txn(fingerprint = "old", id = 1))
        )
        val incoming = MasterBackup(
            bankTxns = listOf(txn(fingerprint = "new", id = 1)),
            sections = listOf(VaultSection.BANK)
        )
        val result = VaultMerge.apply(current, incoming, Mode.REPLACE, DuplicatePolicy.SKIP)
        assertEquals(1, result.vault.bankTxns.size)
        assertEquals("new", result.vault.bankTxns.single().fingerprint)
        assertEquals(1, result.vault.logins.size)  // untouched
    }

    @Test
    fun `a logins-only merge leaves the bank book alone`() {
        val current = MasterBackup(bankTxns = listOf(txn(fingerprint = "aaa", id = 1)))
        val incoming = MasterBackup(
            logins = listOf(LoginEntity(siteName = "s", username = "u", password = "p")),
            sections = listOf(VaultSection.LOGINS)
        )
        val result = VaultMerge.apply(current, incoming, Mode.MERGE, DuplicatePolicy.SKIP)
        assertEquals(1, result.vault.bankTxns.size)
    }
}
