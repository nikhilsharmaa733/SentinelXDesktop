package com.nikhil.sentinelx.desktop.core.format

import com.google.gson.Gson
import java.io.File
import java.time.LocalDate
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The cash-book half of the wire format.
 *
 * These run before the Android side exists on purpose. Once a phone ships the v7 Room
 * migration the field names and the denomination encoding are frozen — changing either
 * afterwards costs a second migration on every installed device.
 */
class CashBookTest {

    private fun entry(
        id: Long = 1L,
        direction: String = CashBook.IN,
        amount: Double = 0.0,
        denominations: String? = null,
        slipImageUris: String? = null,
        status: String = CashBook.STATUS_PENDING
    ) = CashEntryEntity(
        id = id,
        entryDate = LocalDate.of(2026, 8, 5).toBusinessDate(),
        direction = direction,
        amount = amount,
        denominations = denominations,
        slipImageUris = slipImageUris,
        status = status
    )

    // ── Denomination codec ───────────────────────────────────────────────────

    @Test
    fun `a tally round-trips through the encoding`() {
        val counts = mapOf(500 to 12, 200 to 3, 50 to 7)
        val encoded = counts.encodeDenominations()
        assertEquals(counts, decodeDenominations(encoded))
    }

    @Test
    fun `encoding is highest-denomination-first so the same tally never churns the vault file`() {
        // Map iteration order is not guaranteed. If the encoder inherited it, re-saving an
        // untouched entry would produce different bytes, and every save would burn a version
        // snapshot for no change.
        val a = mapOf(50 to 7, 500 to 12, 200 to 3).encodeDenominations()
        val b = mapOf(200 to 3, 50 to 7, 500 to 12).encodeDenominations()
        assertEquals(a, b)
        assertEquals("""{"500":12,"200":3,"50":7}""", a)
    }

    @Test
    fun `zero and negative counts are dropped, and an empty tally encodes to null`() {
        assertEquals("""{"100":2}""", mapOf(500 to 0, 100 to 2, 20 to -3).encodeDenominations())
        assertNull(emptyMap<Int, Int>().encodeDenominations())
        assertNull(mapOf(500 to 0).encodeDenominations())
    }

    @Test
    fun `garbage in the denominations column never throws`() {
        // This column can be reached by a hand-edited archive, a truncated write, or a
        // future build writing something this one doesn't understand. Losing the
        // breakdown is acceptable; taking the vault down with an exception is not.
        val garbage = listOf(
            "not json at all",
            "{",
            "[]",
            "null",
            """{"500":"twelve"}""",
            """{"abc":3}""",
            """{"500":{"nested":1}}""",
            "",
            "   "
        )
        garbage.forEach { raw ->
            assertEquals(emptyMap(), decodeDenominations(raw), "should have degraded quietly: $raw")
        }
        assertEquals(emptyMap(), decodeDenominations(null))
    }

    @Test
    fun `a partly-valid tally keeps the entries it can read`() {
        assertEquals(
            mapOf(500 to 2),
            decodeDenominations("""{"500":2,"abc":9,"200":"x"}""")
        )
    }

    // ── Reconciliation ───────────────────────────────────────────────────────

    @Test
    fun `a tally that matches the declared amount reconciles`() {
        val e = entry(amount = 6700.0, denominations = mapOf(500 to 12, 200 to 3, 100 to 1).encodeDenominations())
        assertEquals(6700.0, e.countedTotal())
        assertTrue(e.isReconciled())
        assertEquals(0.0, e.reconciliationDifference())
    }

    @Test
    fun `a miscount is caught and the difference is signed toward the error`() {
        // The entire point of the nightly ritual. One ₹500 note short of the stated amount.
        val e = entry(amount = 6700.0, denominations = mapOf(500 to 11, 200 to 3, 100 to 1).encodeDenominations())
        assertFalse(e.isReconciled())
        assertEquals(-500.0, e.reconciliationDifference())
    }

    @Test
    fun `an entry with no breakdown reconciles trivially`() {
        // A plain balance-sheet line has nothing to check against and must not be
        // flagged as a mismatch just for lacking a note count.
        val e = entry(amount = 4200.0, denominations = null)
        assertTrue(e.isReconciled())
        assertFalse(e.hasBreakdown())
    }

    @Test
    fun `an unreadable breakdown reconciles rather than crying wolf`() {
        // decodeDenominations already degraded to empty; the amount is still trustworthy,
        // so this must behave like "no breakdown", not like "off by the full amount".
        val e = entry(amount = 4200.0, denominations = "corrupt")
        assertTrue(e.isReconciled())
    }

    // ── Totals ───────────────────────────────────────────────────────────────

    @Test
    fun `debit, credit and net position are computed from direction`() {
        val entries = listOf(
            entry(id = 1, direction = CashBook.IN, amount = 50_000.0),
            entry(id = 2, direction = CashBook.OUT, amount = 50_000.0),
            entry(id = 3, direction = CashBook.IN, amount = 12_000.0)
        )
        assertEquals(62_000.0, entries.totalDebit())
        assertEquals(50_000.0, entries.totalCredit())
        assertEquals(12_000.0, entries.netPosition())
    }

    @Test
    fun `a balanced day nets to zero — money home at night, back at the office by morning`() {
        val tally = mapOf(500 to 100).encodeDenominations()
        val entries = listOf(
            entry(id = 1, direction = CashBook.IN, amount = 50_000.0, denominations = tally),
            entry(id = 2, direction = CashBook.OUT, amount = 50_000.0, denominations = tally)
        )
        assertEquals(0.0, entries.netPosition())
        assertTrue(entries.noteInventory().isEmpty(), "nothing should be left at home")
    }

    @Test
    fun `note inventory reports what is actually still held`() {
        val entries = listOf(
            entry(id = 1, direction = CashBook.IN, amount = 56_000.0,
                denominations = mapOf(500 to 100, 200 to 30).encodeDenominations()),
            entry(id = 2, direction = CashBook.OUT, amount = 38_500.0,
                denominations = mapOf(500 to 77).encodeDenominations())
        )
        assertEquals(mapOf(500 to 23, 200 to 30), entries.noteInventory())
    }

    // ── Business dates ───────────────────────────────────────────────────────

    @Test
    fun `a business date survives normalisation and is timezone-independent`() {
        // Stored at UTC midnight so the same entry reads as the same calendar day on any
        // device. Local midnight would shift the day for anyone west of Greenwich.
        val date = LocalDate.of(2026, 8, 5)
        val stored = date.toBusinessDate()
        assertEquals(date, businessDateOf(stored))
        assertEquals(stored, normaliseToBusinessDate(stored))
    }

    @Test
    fun `any instant during a day normalises to that day`() {
        val date = LocalDate.of(2026, 8, 5)
        val lateEvening = date.toBusinessDate() + 23 * 3_600_000L + 59 * 60_000L
        assertEquals(date.toBusinessDate(), normaliseToBusinessDate(lateEvening))
    }

    // ── Archive round-trip ───────────────────────────────────────────────────

    @Test
    fun `cash entries survive a write and read of a real archive`() {
        val backup = MasterBackup(
            cashBook = listOf(
                CashEntryEntity(
                    id = 1L,
                    book = "MAIN",
                    entryDate = LocalDate.of(2026, 8, 5).toBusinessDate(),
                    direction = CashBook.IN,
                    slot = CashBook.SLOT_EVENING,
                    amount = 6700.0,
                    denominations = mapOf(500 to 12, 200 to 3, 100 to 1).encodeDenominations(),
                    particulars = "Day's takings, counted at home",
                    countedBy = "Papa",
                    verifiedBy = "Nikhil",
                    status = CashBook.STATUS_VERIFIED,
                    slipImageUris = "IMG_slip_a.webp,IMG_slip_b.webp",
                    notes = "₹100 note torn, set aside",
                    timestamp = 1_754_000_000_000L
                )
            ),
            timestamp = 1_754_000_000_000L
        )
        val images = mapOf(
            "IMG_slip_a.webp" to byteArrayOf(1, 2, 3),
            "IMG_slip_b.webp" to byteArrayOf(4, 5, 6)
        )

        val dir = createTempDirectory("cashbook").toFile()
        try {
            val file = File(dir, "vault.sxv")
            val password = "correct horse battery staple".toCharArray()
            SxvArchive.write(file, backup, images, password)
            val read = SxvArchive.read(file, "correct horse battery staple".toCharArray())

            assertEquals(backup.cashBook, read.backup.cashBook)
            assertEquals(8, read.backup.version)
            // Slips must be packed, which only happens if referencedImages() knows about them.
            assertEquals(setOf("IMG_slip_a.webp", "IMG_slip_b.webp"), read.images.keys)
            assertTrue(read.missingImages().isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `slip photos are counted as referenced images`() {
        val backup = MasterBackup(cashBook = listOf(entry(slipImageUris = "slip.webp")))
        assertTrue("slip.webp" in backup.referencedImages())
    }

    // ── Backward compatibility ───────────────────────────────────────────────

    @Test
    fun `a v6 payload with no cashBook key loads as an empty book`() {
        // Every existing archive on the user's disk and phone looks like this. It must
        // open without complaint, not fail and not null-pointer somewhere downstream.
        val v6Json = """
            {"logins":[],"artifacts":[],"chronicles":[],"prophecies":[],
             "ledger":[],"accounts":[],"version":6,"timestamp":1700000000000}
        """.trimIndent()

        val restored = Gson().fromJson(v6Json, MasterBackup::class.java)
        assertEquals(emptyList(), restored.cashBook)
        assertEquals(6, restored.version)
        assertTrue(restored.referencedImages().isEmpty())
    }

    @Test
    fun `a v7 payload keeps every cash-book field name`() {
        // Gson serialises by property name, and the Android entity has to match this
        // list character for character. A rename here is a silent data loss there.
        val json = Gson().toJson(MasterBackup(cashBook = listOf(entry())))
        listOf(
            "cashBook", "book", "entryDate", "direction", "slot", "amount",
            "particulars", "countedBy", "verifiedBy", "status", "timestamp"
        ).forEach { field ->
            assertTrue("\"$field\"" in json, "field '$field' missing from the wire format:\n$json")
        }
    }
}
