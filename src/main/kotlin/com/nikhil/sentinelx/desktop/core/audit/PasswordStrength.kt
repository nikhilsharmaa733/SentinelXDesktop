package com.nikhil.sentinelx.desktop.core.audit

import com.nikhil.sentinelx.desktop.core.format.LoginEntity

/**
 * Password strength, matching the Android app's scale in `AddLoginScreen.kt` so the
 * same password never reads as "WEAK" on one device and "STRONG" on the other.
 */
enum class Strength(val label: String, val bars: Int) {
    NONE("", 0),
    WEAK("WEAK", 1),
    FAIR("FAIR", 2),
    STRONG("STRONG", 3),
    FORTRESS("FORTRESS", 4);

    companion object {
        fun of(password: String): Strength = when {
            password.isEmpty() -> NONE
            password.length < 8 -> WEAK
            password.length < 12 -> FAIR
            password.any { it.isDigit() } &&
                password.any { it.isUpperCase() } &&
                password.length >= 14 -> FORTRESS
            else -> STRONG
        }
    }
}

/**
 * A problem worth showing the user, ordered by how much it matters.
 *
 * REUSED is deliberately the most severe. A weak password costs you one account; a
 * reused one means a breach anywhere becomes a breach everywhere, which is exactly
 * the failure a vault exists to prevent. It is also the check the phone cannot
 * practically do — spotting reuse means comparing every entry against every other.
 */
enum class Issue(val severity: Int, val label: String) {
    REUSED(3, "Reused"),
    WEAK(2, "Weak"),
    SHORT(1, "Short")
}

data class AuditFinding(
    val login: LoginEntity,
    val issues: List<Issue>,
    /** Other site names sharing this password — empty unless REUSED. */
    val sharedWith: List<String> = emptyList()
) {
    val worst: Issue? get() = issues.maxByOrNull { it.severity }
}

object PasswordAudit {

    /**
     * Audits every login.
     *
     * Reuse is grouped by the exact password. Blank passwords are skipped rather
     * than reported as reused with each other, which would be noise.
     */
    fun run(logins: List<LoginEntity>): List<AuditFinding> {
        val byPassword = logins
            .filter { it.password.isNotBlank() }
            .groupBy { it.password }

        return logins.mapNotNull { login ->
            val issues = mutableListOf<Issue>()

            val sharing = byPassword[login.password]
                ?.filter { it.id != login.id }
                ?.map { it.siteName }
                ?.distinct()
                .orEmpty()

            if (sharing.isNotEmpty()) issues += Issue.REUSED

            when (Strength.of(login.password)) {
                Strength.NONE, Strength.WEAK -> issues += Issue.WEAK
                Strength.FAIR -> issues += Issue.SHORT
                else -> Unit
            }

            if (issues.isEmpty()) null
            else AuditFinding(login, issues.sortedByDescending { it.severity }, sharing)
        }.sortedWith(
            compareByDescending<AuditFinding> { it.worst?.severity ?: 0 }
                .thenBy { it.login.siteName.lowercase() }
        )
    }

    /** 0..100, for a headline figure. Entirely clean vaults score 100. */
    fun score(logins: List<LoginEntity>): Int {
        if (logins.isEmpty()) return 100
        val findings = run(logins)
        // Explicit Int, otherwise sumOf cannot choose between its Int/Long/Double
        // overloads from a `when` expression alone.
        val penalty: Int = findings.sumOf { finding ->
            val cost: Int = when (finding.worst) {
                Issue.REUSED -> 3
                Issue.WEAK -> 2
                Issue.SHORT -> 1
                null -> 0
            }
            cost
        }
        val worstCase: Double = logins.size * 3.0
        return ((1 - penalty / worstCase) * 100).toInt().coerceIn(0, 100)
    }
}
