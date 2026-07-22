package com.nikhil.sentinelx.desktop.ui.panes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nikhil.sentinelx.desktop.core.audit.AuditFinding
import com.nikhil.sentinelx.desktop.core.audit.PasswordAudit
import com.nikhil.sentinelx.desktop.core.audit.Strength
import com.nikhil.sentinelx.desktop.core.format.LoginEntity
import com.nikhil.sentinelx.desktop.ui.AppState
import com.nikhil.sentinelx.desktop.ui.components.*
import com.nikhil.sentinelx.desktop.ui.theme.*

/**
 * Logins, grouped by site — matching the phone, where `groupedLogins` is
 * `groupBy { it.siteName }` and the detail screen receives every entry for a site.
 *
 * A flat list was wrong: four Google accounts took four rows and read as four
 * unrelated services. Grouping also makes the desktop layout pay off properly — the
 * phone needs two screens to go site → accounts, whereas here the site list stays on
 * the left while every account for the selected site is stacked on the right.
 */
@Composable
fun LoginsPane(state: AppState) {
    var query by remember { mutableStateOf("") }
    var selectedSite by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<LoginEntity?>(null) }
    var creating by remember { mutableStateOf(false) }
    var prefillSite by remember { mutableStateOf<String?>(null) }

    val logins = state.backup.logins
    val findings = remember(logins) { PasswordAudit.run(logins).associateBy { it.login.id } }

    // A site matches if its NAME matches, or if any of its usernames do — otherwise
    // searching for an email address would hide the very site that holds it.
    val grouped: List<Pair<String, List<LoginEntity>>> = remember(logins, query, state.favourites) {
        logins.groupBy { it.siteName }
            .filter { (site, entries) ->
                query.isBlank() ||
                    site.contains(query, true) ||
                    entries.any { it.username.contains(query, true) }
            }
            .toList()
            .sortedWith(
                compareByDescending<Pair<String, List<LoginEntity>>> {
                    state.isFavourite(state.favouriteKey("login", it.first))
                }.thenBy { it.first.lowercase() }
            )
    }

    val selected = grouped.firstOrNull { it.first == selectedSite }
        ?: grouped.firstOrNull().also { selectedSite = it?.first }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            PaneHeader(
                title = "Logins",
                subtitle = "${logins.size} records across ${logins.map { it.siteName }.distinct().size} sites"
            ) {
                val score = remember(logins) { PasswordAudit.score(logins) }
                if (logins.isNotEmpty()) {
                    Pill(
                        "HEALTH $score%",
                        when {
                            score >= 85 -> IncomeGreen
                            score >= 60 -> AmberWarn
                            else -> ExpenseRed
                        }
                    )
                }
            }

            Row(Modifier.fillMaxSize()) {
                // ── Site list ─────────────────────────────────────────────────
                Column(
                    Modifier.width(320.dp).fillMaxHeight()
                        .background(BackgroundVoid.copy(0.4f))
                        .padding(horizontal = 18.dp)
                ) {
                    SearchField(query, { query = it }, "Search site or username")
                    Spacer(Modifier.height(12.dp))

                    if (grouped.isEmpty()) {
                        EmptyState(
                            "ᛗ",
                            if (logins.isEmpty()) "NO LOGINS" else "NO MATCHES",
                            if (logins.isEmpty()) "Import a Migration Seal to begin" else "Try a different search"
                        )
                    } else {
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(grouped, key = { it.first }) { (site, entries) ->
                                val favKey = state.favouriteKey("login", site)
                                SiteRow(
                                    site = site,
                                    entries = entries,
                                    selected = site == selectedSite,
                                    flagged = entries.any { findings[it.id] != null },
                                    favourite = state.isFavourite(favKey),
                                    onToggleFavourite = { state.toggleFavourite(favKey) },
                                    onClick = { selectedSite = site }
                                )
                            }
                            item { Spacer(Modifier.height(20.dp)) }
                        }
                    }
                }

                // ── Every account for the selected site ───────────────────────
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    if (selected == null) {
                        EmptyState("ᛗ", "NOTHING SELECTED", "Choose a site from the list")
                    } else {
                        SiteDetail(
                            site = selected.first,
                            entries = selected.second,
                            findings = findings,
                            onEdit = { editing = it },
                            onAddAnother = { prefillSite = selected.first; creating = true }
                        )
                    }
                }
            }
        }

        Box(Modifier.align(Alignment.BottomEnd).padding(28.dp)) {
            AddButton(onClick = { prefillSite = null; creating = true })
        }
    }

    if (creating) {
        LoginEditor(state, null, prefillSite) { creating = false; prefillSite = null }
    }
    editing?.let { target -> LoginEditor(state, target, null) { editing = null } }
}

@Composable
private fun SiteRow(
    site: String,
    entries: List<LoginEntity>,
    selected: Boolean,
    flagged: Boolean,
    favourite: Boolean,
    onToggleFavourite: () -> Unit,
    onClick: () -> Unit
) {
    val accent = accentFor(site)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp)
            .clip(RoundedCornerShape(12.dp))
            .rowSurface(selected)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(34.dp).clip(CircleShape)
                .background(accent.copy(0.14f))
                .border(1.dp, accent.copy(0.35f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                site.take(1).uppercase(),
                color = accent, fontSize = 15.sp,
                fontWeight = FontWeight.Black, fontFamily = FontFamily.Serif
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                site,
                color = if (selected) GoldIce else TextParchment,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1
            )
            Text(
                // One account shows its username; several show a count, since
                // listing them all would overflow the row anyway.
                if (entries.size == 1) entries.first().username else "${entries.size} accounts",
                color = TextMuted, fontSize = 11.sp, maxLines = 1
            )
        }
        if (entries.size > 1) {
            Box(
                Modifier.clip(CircleShape).background(accent.copy(0.15f))
                    .padding(horizontal = 7.dp, vertical = 2.dp)
            ) {
                Text("${entries.size}", color = accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(6.dp))
        }
        if (flagged) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(AmberWarn))
            Spacer(Modifier.width(6.dp))
        }
        Text(
            if (favourite) "★" else "☆",
            color = if (favourite) GoldBright else TextMuted.copy(0.5f),
            fontSize = 13.sp,
            modifier = Modifier.clickable { onToggleFavourite() }
        )
    }
}

@Composable
private fun SiteDetail(
    site: String,
    entries: List<LoginEntity>,
    findings: Map<Int, AuditFinding>,
    onEdit: (LoginEntity) -> Unit,
    onAddAnother: () -> Unit
) {
    val accent = accentFor(site)

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 28.dp)) {
        item {
            Spacer(Modifier.height(24.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(52.dp).clip(CircleShape)
                        .background(accent.copy(0.14f))
                        .border(1.dp, accent.copy(0.4f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        site.take(1).uppercase(),
                        color = accent, fontSize = 24.sp,
                        fontWeight = FontWeight.Black, fontFamily = FontFamily.Serif
                    )
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        site,
                        color = GoldIce, fontSize = 24.sp,
                        fontWeight = FontWeight.Black, fontFamily = FontFamily.Serif
                    )
                    Text(
                        "${entries.size} ACCOUNT${if (entries.size == 1) "" else "S"}",
                        color = TextMuted, fontSize = 9.sp, letterSpacing = 3.sp
                    )
                }
                TextButton(onClick = onAddAnother) {
                    Text(
                        "+ ADD ACCOUNT", color = CyanGlow, fontSize = 11.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = 1.sp
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
        }

        // Every account for this site, stacked. The phone needs a second screen to
        // reach these; here they are all visible at once.
        items(entries, key = { it.id }) { login ->
            AccountCard(
                login = login,
                accent = accent,
                sharedWith = findings[login.id]?.sharedWith.orEmpty(),
                onEdit = { onEdit(login) }
            )
            Spacer(Modifier.height(14.dp))
        }

        item { Spacer(Modifier.height(28.dp)) }
    }
}

@Composable
private fun AccountCard(
    login: LoginEntity,
    accent: Color,
    sharedWith: List<String>,
    onEdit: () -> Unit
) {
    var revealed by remember(login.id) { mutableStateOf(false) }
    val strength = remember(login.password) { Strength.of(login.password) }

    GemCard(accent = accent, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("USERNAME", color = accent, fontSize = 8.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(5.dp))
                Text(login.username.ifBlank { "—" }, color = TextParchment, fontSize = 15.sp)
            }
            if (login.username.isNotBlank()) CopyButton(login.username, "username", accent)
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onEdit) {
                Text("EDIT", color = CyanGlow, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(Modifier.height(16.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("PASSWORD", color = CyanGlow, fontSize = 8.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            StrengthBars(strength)
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (revealed) login.password else "•".repeat(login.password.length.coerceAtMost(18)),
                color = TextParchment, fontSize = 15.sp,
                fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { revealed = !revealed }) {
                Icon(
                    if (revealed) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = if (revealed) "Hide" else "Reveal",
                    tint = TextSubtle, modifier = Modifier.size(16.dp)
                )
            }
            CopyButton(login.password, "password", CyanGlow)
        }

        if (sharedWith.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(
                "⚠ Reused — also on ${sharedWith.joinToString(", ")}. A breach at any one " +
                    "of them exposes all of them.",
                color = ExpenseRed, fontSize = 11.sp, lineHeight = 16.sp
            )
        }
    }
}

@Composable
private fun StrengthBars(strength: Strength) {
    val color = when (strength) {
        Strength.NONE -> TextMuted
        Strength.WEAK -> ExpenseRed
        Strength.FAIR -> AmberWarn
        Strength.STRONG -> GoldTarnished
        Strength.FORTRESS -> IncomeGreen
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        repeat(4) { i ->
            Box(
                Modifier.padding(end = 3.dp).width(16.dp).height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (i < strength.bars) color else SurfaceElevated)
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(strength.label, color = color, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    }
}
