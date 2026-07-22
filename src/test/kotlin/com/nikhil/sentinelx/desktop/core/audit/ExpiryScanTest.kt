package com.nikhil.sentinelx.desktop.core.audit

import com.nikhil.sentinelx.desktop.core.format.ArtifactEntity
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExpiryScanTest {

    private val today = LocalDate.of(2026, 7, 22)

    @Test
    fun `parses the date formats a real vault actually contains`() {
        assertEquals(LocalDate.of(2031, 8, 20), ExpiryScan.parse("20/08/2031"))
        assertEquals(LocalDate.of(2031, 8, 20), ExpiryScan.parse("20-08-2031"))
        assertEquals(LocalDate.of(2031, 8, 20), ExpiryScan.parse("2031-08-20"))
        assertEquals(LocalDate.of(2031, 8, 20), ExpiryScan.parse("20.08.2031"))
        assertEquals(LocalDate.of(2031, 8, 5), ExpiryScan.parse("5/8/2031"))
    }

    @Test
    fun `month-only dates resolve to the last day of that month`() {
        // A card marked 08/2031 is valid through the end of August, not the 1st.
        assertEquals(LocalDate.of(2031, 8, 31), ExpiryScan.parse("08/2031"))
        assertEquals(LocalDate.of(2031, 2, 28), ExpiryScan.parse("02/2031"))
        // Two-digit year, as printed on a physical card.
        assertEquals(LocalDate.of(2031, 8, 31), ExpiryScan.parse("08/31"))
    }

    @Test
    fun `unparseable text is ignored rather than guessed at`() {
        // Presenting a confidently wrong expiry date is worse than showing none.
        assertNull(ExpiryScan.parse("sometime next year"))
        assertNull(ExpiryScan.parse(""))
        assertNull(ExpiryScan.parse("N/A"))
    }

    @Test
    fun `reads the expiry from the right label slot per document type`() {
        val bank = ArtifactEntity(1, "BANK", "HDFC", "4111", "NIKHIL", label4 = "08/2031")
        val passport = ArtifactEntity(2, "PASSPORT", "NIKHIL", "Z1", "INDIAN", "12/03/2001", "20/08/2027")
        val aadhaar = ArtifactEntity(3, "AADHAR", "NIKHIL", "1234", "12/03/2001")

        val found = ExpiryScan.scan(listOf(bank, passport, aadhaar), today, withinDays = 3650)
        val types = found.map { it.artifact.type }

        assertTrue("BANK" in types, "bank expiry lives in label4")
        assertTrue("PASSPORT" in types, "passport expiry lives in label5")
        assertTrue("AADHAR" !in types, "Aadhaar does not expire and must not be scanned")
    }

    @Test
    fun `expired documents are always included regardless of the horizon`() {
        val lapsed = ArtifactEntity(1, "PASSPORT", "Old", "Z1", "IN", "x", "01/01/2020")
        val found = ExpiryScan.scan(listOf(lapsed), today, withinDays = 30)
        assertEquals(1, found.size, "an already-expired document is the one that needs action")
        assertTrue(found.single().expired)
    }

    @Test
    fun `results are ordered soonest first`() {
        val far = ArtifactEntity(1, "PASSPORT", "Far", "A", "IN", "x", "20/08/2030")
        val near = ArtifactEntity(2, "PASSPORT", "Near", "B", "IN", "x", "01/09/2026")
        val found = ExpiryScan.scan(listOf(far, near), today, withinDays = 3650)
        assertEquals("Near", found.first().artifact.label1)
    }

    @Test
    fun `urgency bands are classified correctly`() {
        val soon = ArtifactEntity(1, "BANK", "Card", "1", "N", label4 = "10/08/2026") // 19 days
        val expiry = ExpiryScan.scan(listOf(soon), today).single()
        assertEquals(19, expiry.daysRemaining)
        assertTrue(expiry.urgent)
        assertTrue(!expiry.expired)
    }
}
