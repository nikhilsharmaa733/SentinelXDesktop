package com.nikhil.sentinelx.desktop.core.format

import com.nikhil.sentinelx.desktop.core.format.VaultMerge.DuplicatePolicy
import com.nikhil.sentinelx.desktop.core.format.VaultMerge.Mode
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The bills book's place in the wire format, plus the OTP-only login flag —
 * both additive fields on an unmoved archive version, so the pins here are the
 * degradation paths: absent keys default, scoped archives carry only their own
 * images, and the merge follows the cash book's no-unique-index convention.
 */
class BillsFormatTest {

    private fun bill(
        provider: String = "MSEB",
        type: String = Bills.TYPE_ELECTRICITY,
        amount: Double = 1240.0,
        dueDate: Long = 1_755_648_000_000,   // 2026-08-20 UTC midnight
        status: String = Bills.UNPAID,
        refNo: String? = "K-4471",
        images: String? = null,
        id: Long = 0L
    ) = BillEntity(
        id = id, billType = type, provider = provider, refNo = refNo,
        amount = amount, dueDate = dueDate, status = status,
        paidDate = if (status == Bills.PAID) dueDate else null,
        billImageUris = images, notes = null, timestamp = 9L
    )

    // ── Archive round trip & degradation ─────────────────────────────────────

    @Test
    fun `bills survive a sealed archive round trip`() {
        val dir = createTempDirectory("bills-rt").toFile()
        try {
            val file = java.io.File(dir, "t.sxv")
            val original = MasterBackup(bills = listOf(bill(id = 3, images = "b1.jpg,b2.jpg")))
            SxvArchive.write(file, original, emptyMap(), "pw123456".toCharArray())
            val loaded = SxvArchive.read(file, "pw123456".toCharArray())
            assertEquals(original.bills, loaded.backup.bills)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `an archive without the bills key degrades to an empty book`() {
        val gson = com.google.gson.Gson()
        val decoded = gson.fromJson("""{"logins":[],"version":8}""", MasterBackup::class.java)
        assertEquals(emptyList(), decoded.bills)
    }

    @Test
    fun `a login without the isOtpOnly key defaults to false`() {
        val gson = com.google.gson.Gson()
        val decoded = gson.fromJson(
            """{"logins":[{"id":1,"siteName":"Swiggy","username":"me","password":"x"}],"version":8}""",
            MasterBackup::class.java
        )
        assertFalse(decoded.logins.single().isOtpOnly)
    }

    // ── Scoping ──────────────────────────────────────────────────────────────

    @Test
    fun `scoping to bills keeps only bills and tags the archive`() {
        val full = MasterBackup(
            logins = listOf(LoginEntity(1, "Gmail", "me", "pw")),
            bills = listOf(bill())
        )
        val scoped = full.scopedTo(listOf(VaultSection.BILLS))
        assertEquals(1, scoped.bills.size)
        assertTrue(scoped.logins.isEmpty())
        assertEquals(listOf(VaultSection.BILLS), scoped.sections)
        assertEquals(1, scoped.countIn(VaultSection.BILLS))
    }

    @Test
    fun `bill photos are referenced images`() {
        val backup = MasterBackup(bills = listOf(bill(images = "bill_a.jpg,bill_b.jpg")))
        assertEquals(setOf("bill_a.jpg", "bill_b.jpg"), backup.referencedImages())
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    @Test
    fun `overdue means unpaid and past due`() {
        val due = 1_755_648_000_000
        assertTrue(bill(status = Bills.UNPAID).isOverdue(today = due + 86_400_000))
        assertFalse(bill(status = Bills.UNPAID).isOverdue(today = due))            // due today ≠ overdue
        assertFalse(bill(status = Bills.PAID).isOverdue(today = due + 86_400_000)) // paid never overdue
    }

    // ── Merge ────────────────────────────────────────────────────────────────

    @Test
    fun `identical bills on both sides merge to one`() {
        val a = MasterBackup(bills = listOf(bill(id = 1)))
        val b = MasterBackup(bills = listOf(bill(id = 44)))
        val merged = VaultMerge.merge(a, b, DuplicatePolicy.SKIP).vault
        assertEquals(1, merged.bills.size)
    }

    @Test
    fun `two identical bills are legitimate — keep both keeps both, untouched`() {
        // Two SIMs on the same plan: same provider, amount and due date.
        val a = MasterBackup(bills = listOf(bill(id = 1, status = Bills.UNPAID)))
        val b = MasterBackup(bills = listOf(bill(id = 2, status = Bills.PAID)))
        val merged = VaultMerge.merge(a, b, DuplicatePolicy.KEEP_BOTH).vault
        assertEquals(2, merged.bills.size)
        // No "(imported)" mark — bills carry no unique index to clear.
        assertTrue(merged.bills.all { !it.provider.contains(VaultMerge.IMPORT_MARK) })
    }

    @Test
    fun `overwrite takes the incoming copy of a conflicting bill`() {
        val a = MasterBackup(bills = listOf(bill(id = 1, status = Bills.UNPAID)))
        val b = MasterBackup(bills = listOf(bill(id = 2, status = Bills.PAID)))
        val merged = VaultMerge.merge(a, b, DuplicatePolicy.OVERWRITE).vault
        assertEquals(1, merged.bills.size)
        assertEquals(Bills.PAID, merged.bills.single().status)
    }

    @Test
    fun `a bills-only replace leaves the rest of the vault alone`() {
        val current = MasterBackup(
            logins = listOf(LoginEntity(1, "Gmail", "me", "pw")),
            bills = listOf(bill(provider = "Old Provider"))
        )
        val incoming = MasterBackup(bills = listOf(bill(provider = "Airtel", type = Bills.TYPE_WIFI)))
            .scopedTo(listOf(VaultSection.BILLS))
        val result = VaultMerge.apply(current, incoming, Mode.REPLACE, DuplicatePolicy.SKIP).vault
        assertEquals(1, result.logins.size)                       // untouched
        assertEquals("Airtel", result.bills.single().provider)    // replaced
    }
}
