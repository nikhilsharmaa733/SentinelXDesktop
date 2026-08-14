package com.nikhil.sentinelx.desktop.core.format

import com.nikhil.sentinelx.desktop.core.format.VaultMerge.DuplicatePolicy
import com.nikhil.sentinelx.desktop.core.format.VaultMerge.Mode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The merge engine, which decides whether an import destroys what is already there.
 *
 * Worth over-testing: a mistake here is silent. Every `@Insert` on the Android side is
 * `OnConflictStrategy.REPLACE`, so a merged list that still contains two records with
 * the same unique-index key does not raise anything — the database quietly keeps one
 * and the user finds out weeks later.
 */
class VaultMergeTest {

    private fun login(site: String, user: String, password: String, id: Int = 0) =
        LoginEntity(id = id, siteName = site, username = user, password = password)

    private fun note(title: String, content: String = "body", sigil: String = "GENERAL") =
        ProphecyEntity(title = title, content = content, sigil = sigil)

    private fun account(name: String, colour: String = "#fff", id: Long = 0L) =
        AccountEntity(id = id, name = name, colorHex = colour, sigilType = "RUNE")

    private fun tx(accountId: Long, title: String, amount: Double, at: Long, id: Long = 0L) =
        TransactionEntity(
            id = id, accountId = accountId, title = title, amount = amount,
            isIncoming = true, timestamp = at
        )

    private fun cash(date: Long, amount: Double, particulars: String = "handover") =
        CashEntryEntity(
            entryDate = date, direction = CashBook.IN, slot = CashBook.SLOT_EVENING,
            amount = amount, particulars = particulars
        )

    // ── Identity and classification ───────────────────────────────────────────

    @Test
    fun `an unchanged record is identical, not a conflict`() {
        val vault = MasterBackup(logins = listOf(login("Gmail", "ray", "hunter2")))
        val archive = MasterBackup(logins = listOf(login("Gmail", "ray", "hunter2", id = 99)))

        val plan = VaultMerge.preview(vault, archive)
        val logins = plan.sections.single { it.section == VaultSection.LOGINS }

        assertEquals(1, logins.identical)
        assertEquals(0, logins.conflicting)
        assertEquals(0, logins.fresh)
    }

    @Test
    fun `a differing id alone does not make a record new`() {
        // ids are per-device autoincrements. If they counted, every merge would
        // duplicate the entire vault.
        val vault = MasterBackup(logins = listOf(login("Gmail", "ray", "hunter2", id = 4)))
        val archive = MasterBackup(logins = listOf(login("Gmail", "ray", "hunter2", id = 17)))

        val merged = VaultMerge.merge(vault, archive, DuplicatePolicy.SKIP)
        assertEquals(1, merged.vault.logins.size)
    }

    @Test
    fun `a changed password is a conflict, not a new login`() {
        val vault = MasterBackup(logins = listOf(login("Gmail", "ray", "old")))
        val archive = MasterBackup(logins = listOf(login("Gmail", "ray", "new")))

        val logins = VaultMerge.preview(vault, archive)
            .sections.single { it.section == VaultSection.LOGINS }

        assertEquals(1, logins.conflicting)
        assertEquals(0, logins.fresh)
    }

    @Test
    fun `identity matching ignores case and surrounding space`() {
        val vault = MasterBackup(logins = listOf(login("Gmail", "ray", "same")))
        val archive = MasterBackup(logins = listOf(login("  gmail ", "RAY", "same")))

        val merged = VaultMerge.merge(vault, archive, DuplicatePolicy.SKIP)
        assertEquals(1, merged.vault.logins.size, "Gmail and gmail are the same site")
    }

    @Test
    fun `the plan does not depend on the policy the user has not picked yet`() {
        val vault = MasterBackup(logins = listOf(login("Gmail", "ray", "old")))
        val archive = MasterBackup(
            logins = listOf(login("Gmail", "ray", "new"), login("GitHub", "ray", "x"))
        )

        val counts = DuplicatePolicy.entries.map { policy ->
            VaultMerge.merge(vault, archive, policy)
                .plan.sections.single { it.section == VaultSection.LOGINS }
                .let { Triple(it.fresh, it.identical, it.conflicting) }
        }
        assertEquals(1, counts.distinct().size, "counts are shown before the policy is chosen")
        assertEquals(Triple(1, 0, 1), counts.first())
    }

    // ── The three policies ────────────────────────────────────────────────────

    @Test
    fun `skip keeps what the vault already holds`() {
        val vault = MasterBackup(logins = listOf(login("Gmail", "ray", "old")))
        val archive = MasterBackup(logins = listOf(login("Gmail", "ray", "new")))

        val merged = VaultMerge.merge(vault, archive, DuplicatePolicy.SKIP).vault
        assertEquals(1, merged.logins.size)
        assertEquals("old", merged.logins.single().password)
    }

    @Test
    fun `overwrite takes the incoming copy`() {
        val vault = MasterBackup(logins = listOf(login("Gmail", "ray", "old")))
        val archive = MasterBackup(logins = listOf(login("Gmail", "ray", "new")))

        val merged = VaultMerge.merge(vault, archive, DuplicatePolicy.OVERWRITE).vault
        assertEquals(1, merged.logins.size)
        assertEquals("new", merged.logins.single().password)
    }

    @Test
    fun `keep both marks the newcomer so the unique index still holds`() {
        val vault = MasterBackup(logins = listOf(login("Gmail", "ray", "old")))
        val archive = MasterBackup(logins = listOf(login("Gmail", "ray", "new")))

        val merged = VaultMerge.merge(vault, archive, DuplicatePolicy.KEEP_BOTH).vault
        assertEquals(2, merged.logins.size)
        assertEquals(
            setOf("Gmail", "Gmail (imported)"),
            merged.logins.mapTo(mutableSetOf()) { it.siteName }
        )
        assertUniqueOnIndex(merged)
    }

    @Test
    fun `keep both numbers the mark when the marked name is taken too`() {
        val vault = MasterBackup(
            logins = listOf(login("Gmail", "ray", "a"), login("Gmail (imported)", "ray", "b"))
        )
        val archive = MasterBackup(logins = listOf(login("Gmail", "ray", "c")))

        val merged = VaultMerge.merge(vault, archive, DuplicatePolicy.KEEP_BOTH).vault
        assertEquals(3, merged.logins.size)
        assertTrue(merged.logins.any { it.siteName == "Gmail (imported 2)" })
        assertUniqueOnIndex(merged)
    }

    @Test
    fun `no policy can leave two records sharing a unique index key`() {
        val vault = MasterBackup(
            logins = listOf(login("Gmail", "ray", "a")),
            prophecies = listOf(note("Ideas", "one")),
            accounts = listOf(account("HDFC", "#111", id = 1))
        )
        val archive = MasterBackup(
            logins = listOf(login("gmail", "RAY", "b")),
            prophecies = listOf(note("ideas", "two")),
            accounts = listOf(account("hdfc", "#222", id = 1))
        )

        DuplicatePolicy.entries.forEach { policy ->
            assertUniqueOnIndex(VaultMerge.merge(vault, archive, policy).vault, "policy=$policy")
        }
    }

    // ── Ordering and duplicate handling ───────────────────────────────────────

    @Test
    fun `an exact copy claims its counterpart before an edited sibling does`() {
        // Single-pass greedy matching pairs "edited" with the one existing row, then
        // calls the unchanged copy brand new and duplicates it. Order must not matter.
        val vault = MasterBackup(logins = listOf(login("Gmail", "ray", "same")))
        val archive = MasterBackup(
            logins = listOf(login("Gmail", "ray", "edited"), login("Gmail", "ray", "same"))
        )

        val result = VaultMerge.merge(vault, archive, DuplicatePolicy.SKIP)
        val logins = result.plan.sections.single { it.section == VaultSection.LOGINS }

        assertEquals(1, logins.identical)
        assertEquals(1, logins.conflicting)
        assertEquals(0, logins.fresh)
        assertEquals(1, result.vault.logins.size)
    }

    @Test
    fun `a note differing only in pin, colour or checklist is a conflict, not identical`() {
        // The fingerprint has to see the v8 fields, or a pin toggled on one device
        // would be classified "already present, field for field" and silently dropped
        // by the merge.
        val vault = MasterBackup(prophecies = listOf(note("Ideas", "body")))
        val archive = MasterBackup(
            prophecies = listOf(note("Ideas", "body").copy(isPinned = true, colorHex = "#B0413E"))
        )

        val plan = VaultMerge.preview(vault, archive)
        assertEquals(0, plan.identical)
        assertEquals(1, plan.conflicting)

        val overwritten = VaultMerge.merge(vault, archive, DuplicatePolicy.OVERWRITE).vault
        assertTrue(overwritten.prophecies.single().isPinned)
    }

    @Test
    fun `an absent note type and an explicit TEXT fingerprint identically`() {
        // Gson gives `type` "TEXT" here (the all-defaults no-arg constructor) and null
        // on Android for the same pre-v8 archive; noteType() folds both to TEXT so the
        // two apps classify the record the same way.
        val vault = MasterBackup(prophecies = listOf(note("Ideas").copy(type = null)))
        val archive = MasterBackup(prophecies = listOf(note("Ideas").copy(type = Notes.TYPE_TEXT)))

        assertEquals(1, VaultMerge.preview(vault, archive).identical)
    }

    @Test
    fun `two identical cash entries against one held yields one identical and one fresh`() {
        // cash_book carries no unique index on purpose: two identical handovers in a
        // day are legitimate. Counting free counterparts, rather than asking whether
        // one exists, is what stops the second being swallowed.
        val day = LocalDateBusinessDate
        val vault = MasterBackup(cashBook = listOf(cash(day, 500.0)))
        val archive = MasterBackup(cashBook = listOf(cash(day, 500.0), cash(day, 500.0)))

        val result = VaultMerge.merge(vault, archive, DuplicatePolicy.SKIP)
        val stats = result.plan.sections.single { it.section == VaultSection.CASHBOOK }

        assertEquals(1, stats.identical)
        assertEquals(1, stats.fresh)
        assertEquals(2, result.vault.cashBook.size)
    }

    @Test
    fun `keep both leaves cash entries unmarked because nothing constrains them`() {
        val day = LocalDateBusinessDate
        val vault = MasterBackup(cashBook = listOf(cash(day, 500.0, "evening")))
        val archive = MasterBackup(cashBook = listOf(cash(day, 500.0, "evening").copy(countedBy = "ray")))

        val merged = VaultMerge.merge(vault, archive, DuplicatePolicy.KEEP_BOTH).vault
        assertEquals(2, merged.cashBook.size)
        assertTrue(merged.cashBook.all { it.particulars == "evening" }, "no index to satisfy")
    }

    // ── Ledger ────────────────────────────────────────────────────────────────

    @Test
    fun `imported transactions follow their account by name, not by id`() {
        val vault = MasterBackup(
            accounts = listOf(account("HDFC", id = 7)),
            ledger = listOf(tx(7, "Rent", 100.0, at = 1))
        )
        // Same account, entirely different local id on the other device.
        val archive = MasterBackup(
            accounts = listOf(account("HDFC", id = 2)),
            ledger = listOf(tx(2, "Salary", 900.0, at = 2))
        )

        val merged = VaultMerge.merge(vault, archive, DuplicatePolicy.SKIP).vault
        val hdfc = merged.accounts.single { it.name == "HDFC" }

        assertEquals(1, merged.accounts.size)
        assertEquals(2, merged.ledger.size)
        assertTrue(merged.ledger.all { it.accountId == hdfc.id }, "both rows filed under HDFC")
    }

    @Test
    fun `an overwritten account keeps its id so existing rows stay attached`() {
        val vault = MasterBackup(
            accounts = listOf(account("HDFC", "#111", id = 7)),
            ledger = listOf(tx(7, "Rent", 100.0, at = 1))
        )
        val archive = MasterBackup(accounts = listOf(account("HDFC", "#222", id = 2)))

        val merged = VaultMerge.merge(vault, archive, DuplicatePolicy.OVERWRITE).vault
        val hdfc = merged.accounts.single()

        assertEquals(7L, hdfc.id, "the local id survives; the colour is what changed")
        assertEquals("#222", hdfc.colorHex)
        assertEquals(7L, merged.ledger.single().accountId)
    }

    @Test
    fun `an account arriving with a colliding id is renumbered, not merged`() {
        val vault = MasterBackup(
            accounts = listOf(account("HDFC", id = 1)),
            ledger = listOf(tx(1, "Rent", 100.0, at = 1))
        )
        // Different account, same id — the classic two-device collision.
        val archive = MasterBackup(
            accounts = listOf(account("ICICI", id = 1)),
            ledger = listOf(tx(1, "Salary", 900.0, at = 2))
        )

        val merged = VaultMerge.merge(vault, archive, DuplicatePolicy.SKIP).vault
        val hdfc = merged.accounts.single { it.name == "HDFC" }
        val icici = merged.accounts.single { it.name == "ICICI" }

        assertEquals(2, merged.accounts.size)
        assertTrue(hdfc.id != icici.id, "two accounts cannot share a primary key")
        assertEquals(hdfc.id, merged.ledger.single { it.title == "Rent" }.accountId)
        assertEquals(icici.id, merged.ledger.single { it.title == "Salary" }.accountId)
    }

    @Test
    fun `a kept-both account takes its own transactions with it`() {
        val vault = MasterBackup(
            accounts = listOf(account("HDFC", "#111", id = 1)),
            ledger = listOf(tx(1, "Rent", 100.0, at = 1))
        )
        val archive = MasterBackup(
            accounts = listOf(account("HDFC", "#222", id = 1)),
            ledger = listOf(tx(1, "Salary", 900.0, at = 2))
        )

        val merged = VaultMerge.merge(vault, archive, DuplicatePolicy.KEEP_BOTH).vault
        val original = merged.accounts.single { it.name == "HDFC" }
        val imported = merged.accounts.single { it.name == "HDFC (imported)" }

        assertEquals(original.id, merged.ledger.single { it.title == "Rent" }.accountId)
        assertEquals(
            imported.id,
            merged.ledger.single { it.title == "Salary" }.accountId,
            "the imported rows belong to the imported account, not the one it collided with"
        )
    }

    @Test
    fun `a transaction whose account is missing from the archive is dropped, not orphaned`() {
        val vault = MasterBackup()
        val archive = MasterBackup(ledger = listOf(tx(accountId = 99, "Ghost", 5.0, at = 1)))

        val merged = VaultMerge.merge(vault, archive, DuplicatePolicy.SKIP).vault
        assertTrue(merged.ledger.isEmpty(), "an accountId pointing nowhere is not importable")
    }

    // ── Scoping ───────────────────────────────────────────────────────────────

    @Test
    fun `a scoped archive leaves every other section untouched`() {
        val vault = MasterBackup(
            logins = listOf(login("Gmail", "ray", "a")),
            prophecies = listOf(note("Kept")),
            accounts = listOf(account("HDFC", id = 1)),
            ledger = listOf(tx(1, "Rent", 100.0, at = 1)),
            cashBook = listOf(cash(LocalDateBusinessDate, 500.0))
        )
        val archive = MasterBackup(logins = listOf(login("GitHub", "ray", "b")))
            .scopedTo(listOf(VaultSection.LOGINS))

        listOf(Mode.MERGE, Mode.REPLACE).forEach { mode ->
            val merged = VaultMerge.apply(vault, archive, mode, DuplicatePolicy.SKIP).vault
            assertEquals(listOf("Kept"), merged.prophecies.map { it.title }, "$mode")
            assertEquals(1, merged.ledger.size, "$mode")
            assertEquals(1, merged.accounts.size, "$mode")
            assertEquals(1, merged.cashBook.size, "$mode")
        }
    }

    @Test
    fun `replacing from a scoped archive clears only that section`() {
        val vault = MasterBackup(
            logins = listOf(login("Gmail", "ray", "a"), login("GitHub", "ray", "b")),
            prophecies = listOf(note("Kept"))
        )
        val archive = MasterBackup(logins = listOf(login("Only", "ray", "c")))
            .scopedTo(listOf(VaultSection.LOGINS))

        val merged = VaultMerge.replace(vault, archive).vault
        assertEquals(listOf("Only"), merged.logins.map { it.siteName })
        assertEquals(listOf("Kept"), merged.prophecies.map { it.title })
    }

    @Test
    fun `replacing from a full archive is still wholesale`() {
        val vault = MasterBackup(
            logins = listOf(login("Gmail", "ray", "a")),
            prophecies = listOf(note("Gone"))
        )
        val archive = MasterBackup(logins = listOf(login("Only", "ray", "c")))

        val merged = VaultMerge.replace(vault, archive).vault
        assertEquals(listOf("Only"), merged.logins.map { it.siteName })
        assertTrue(merged.prophecies.isEmpty(), "an untagged archive means the whole vault")
    }

    @Test
    fun `a pre-v8 archive with no sections field is treated as the whole vault`() {
        val legacy = MasterBackup(logins = listOf(login("Gmail", "ray", "a")), sections = null)
        assertEquals(VaultSection.ALL, legacy.carriedSections())
    }

    @Test
    fun `a full export stays untagged so older builds still read it`() {
        val scoped = MasterBackup().scopedTo(VaultSection.ALL)
        assertNull(scoped.sections, "tagging a full archive would confuse a pre-v8 build")
    }

    @Test
    fun `scopedTo keeps accounts with the ledger`() {
        val vault = MasterBackup(
            accounts = listOf(account("HDFC", id = 1)),
            ledger = listOf(tx(1, "Rent", 100.0, at = 1))
        )
        val scoped = vault.scopedTo(listOf(VaultSection.LEDGER))

        assertEquals(1, scoped.accounts.size, "a transaction without its account is an orphan")
        assertEquals(1, scoped.ledger.size)
        assertEquals(listOf(VaultSection.LEDGER), scoped.sections)
    }

    @Test
    fun `merging an empty archive into a vault changes nothing`() {
        val vault = MasterBackup(
            logins = listOf(login("Gmail", "ray", "a")),
            prophecies = listOf(note("Kept"))
        )
        val merged = VaultMerge.merge(vault, MasterBackup(), DuplicatePolicy.SKIP).vault

        assertEquals(vault.logins, merged.logins)
        assertEquals(vault.prophecies, merged.prophecies)
    }

    @Test
    fun `merging into an empty vault imports everything as fresh`() {
        val archive = MasterBackup(
            logins = listOf(login("Gmail", "ray", "a")),
            accounts = listOf(account("HDFC", id = 3)),
            ledger = listOf(tx(3, "Rent", 100.0, at = 1))
        )
        val result = VaultMerge.merge(MasterBackup(), archive, DuplicatePolicy.SKIP)

        assertEquals(1, result.vault.logins.size)
        assertEquals(1, result.vault.ledger.size)
        assertEquals(0, result.plan.identical)
        assertEquals(0, result.plan.conflicting)
        assertEquals(3, result.plan.fresh, "one login, one account, one row")
    }

    @Test
    fun `slip and bill images survive a merge so the archive still references them`() {
        val vault = MasterBackup()
        val archive = MasterBackup(
            cashBook = listOf(cash(LocalDateBusinessDate, 500.0).copy(slipImageUris = "IMG_a.webp"))
        )
        val merged = VaultMerge.merge(vault, archive, DuplicatePolicy.SKIP).vault
        assertTrue("IMG_a.webp" in merged.referencedImages())
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private val LocalDateBusinessDate: Long get() = todayBusinessDate()

    /**
     * Asserts the invariant the whole engine exists to hold: nothing it emits can
     * violate a unique index, because `OnConflictStrategy.REPLACE` would not complain
     * about it — it would just delete a row.
     */
    private fun assertUniqueOnIndex(vault: MasterBackup, hint: String = "") {
        fun check(name: String, keys: List<String>) {
            val duplicates = keys.groupBy { it }.filterValues { it.size > 1 }.keys
            assertTrue(duplicates.isEmpty(), "$hint duplicate $name key(s): $duplicates")
        }
        check("logins", vault.logins.map { "${it.siteName.lowercase()}|${it.username.lowercase()}" })
        check("artifacts", vault.artifacts.map { "${it.label1.lowercase()}|${it.label2.lowercase()}" })
        check("chronicles", vault.chronicles.map { it.title.lowercase() })
        check("prophecies", vault.prophecies.map { it.title.lowercase() })
        check("accounts", vault.accounts.map { it.name.lowercase() })
        check("account ids", vault.accounts.map { it.id.toString() })
        check("ledger", vault.ledger.map { "${it.accountId}|${it.title}|${it.amount}|${it.timestamp}" })
    }

    @Test
    fun `every account in a merged vault is reachable by its transactions`() {
        val vault = MasterBackup(
            accounts = listOf(account("A", id = 1), account("B", id = 2)),
            ledger = listOf(tx(1, "one", 1.0, at = 1), tx(2, "two", 2.0, at = 2))
        )
        val archive = MasterBackup(
            accounts = listOf(account("B", id = 1), account("C", id = 2)),
            ledger = listOf(tx(1, "three", 3.0, at = 3), tx(2, "four", 4.0, at = 4))
        )

        val merged = VaultMerge.merge(vault, archive, DuplicatePolicy.SKIP).vault
        val ids = merged.accounts.mapTo(mutableSetOf()) { it.id }

        assertEquals(3, merged.accounts.size, "A, B and C")
        assertEquals(4, merged.ledger.size)
        merged.ledger.forEach { assertTrue(it.accountId in ids, "${it.title} points at a real account") }
        assertNotNull(merged.accounts.singleOrNull { it.name == "C" })
    }
}
