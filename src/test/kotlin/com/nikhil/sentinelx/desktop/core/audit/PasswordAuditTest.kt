package com.nikhil.sentinelx.desktop.core.audit

import com.nikhil.sentinelx.desktop.core.format.LoginEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PasswordAuditTest {

    private fun login(id: Int, site: String, pw: String) =
        LoginEntity(id, site, "user$id", pw)

    @Test
    fun `strength scale matches the Android thresholds`() {
        assertEquals(Strength.NONE, Strength.of(""))
        assertEquals(Strength.WEAK, Strength.of("short"))          // < 8
        assertEquals(Strength.FAIR, Strength.of("elevenchars"))    // < 12
        assertEquals(Strength.STRONG, Strength.of("twelvecharsxx"))// >= 12, no digit/upper
        assertEquals(Strength.FORTRESS, Strength.of("Fourteen1Chars"))
    }

    @Test
    fun `reuse is detected across sites and reported both ways`() {
        val logins = listOf(
            login(1, "Github", "SharedPassword1"),
            login(2, "Gitlab", "SharedPassword1"),
            login(3, "Unique", "Fourteen1Chars!")
        )
        val findings = PasswordAudit.run(logins).associateBy { it.login.id }

        assertTrue(Issue.REUSED in findings.getValue(1).issues)
        assertTrue(Issue.REUSED in findings.getValue(2).issues)
        assertEquals(listOf("Gitlab"), findings.getValue(1).sharedWith)
        assertEquals(listOf("Github"), findings.getValue(2).sharedWith)
        assertTrue(3 !in findings, "a strong unique password should not be flagged")
    }

    @Test
    fun `blank passwords are not reported as reused with each other`() {
        val findings = PasswordAudit.run(
            listOf(login(1, "A", ""), login(2, "B", ""))
        )
        assertTrue(findings.none { Issue.REUSED in it.issues })
    }

    @Test
    fun `reuse outranks weakness in ordering`() {
        val logins = listOf(
            login(1, "WeakOnly", "abc"),
            login(2, "ReusedA", "SharedPassword1"),
            login(3, "ReusedB", "SharedPassword1")
        )
        val findings = PasswordAudit.run(logins)
        // A reused password compromises every site sharing it, so it must sort first.
        assertEquals(Issue.REUSED, findings.first().worst)
    }

    @Test
    fun `score is 100 for an empty or clean vault, and drops as issues appear`() {
        assertEquals(100, PasswordAudit.score(emptyList()))
        assertEquals(100, PasswordAudit.score(listOf(login(1, "Good", "Fourteen1Chars!"))))

        val messy = listOf(
            login(1, "A", "abc"),
            login(2, "B", "same1234"),
            login(3, "C", "same1234")
        )
        val score = PasswordAudit.score(messy)
        assertTrue(score < 60, "expected a low score for a messy vault, got $score")
        assertTrue(score >= 0)
    }
}
