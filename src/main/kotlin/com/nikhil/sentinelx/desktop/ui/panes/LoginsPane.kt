package com.nikhil.sentinelx.desktop.ui.panes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import com.nikhil.sentinelx.desktop.core.audit.PasswordAudit
import com.nikhil.sentinelx.desktop.core.audit.Strength
import com.nikhil.sentinelx.desktop.core.format.LoginEntity
import com.nikhil.sentinelx.desktop.ui.AppState
import com.nikhil.sentinelx.desktop.ui.components.*
import com.nikhil.sentinelx.desktop.ui.theme.*

/**
 * Logins, as a desktop master/detail rather than the phone's drill-down.
 *
 * The list stays on screen while you read an entry, so comparing two accounts or
 * scanning down a site's credentials takes no navigation at all. That is the whole
 * argument for the layout — it is not decoration.
 */
@Composable
fun LoginsPane(state: AppState) {
    var query by remember { mutableStateOf("") }
    var selectedId by remember { mutableStateOf<Int?>(null) }

    val logins = state.backup.logins
    val filtered = remember(logins, query) {
        if (query.isBlank()) logins
        else logins.filter {
            it.siteName.contains(query, true) || it.username.contains(query, true)
        }
    }.sortedBy { it.siteName.lowercase() }

    // Findings are keyed by id so the detail pane can show why an entry is flagged.
    val findings = remember(logins) { PasswordAudit.run(logins).associateBy { it.login.id } }

    val selected = filtered.firstOrNull { it.id == selectedId }
        ?: filtered.firstOrNull().also { selectedId = it?.id }

    var editing by remember { mutableStateOf<LoginEntity?>(null) }
    var creating by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        PaneHeader("Logins", "${logins.size} records") {
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
            // ── List ──────────────────────────────────────────────────────────
            Column(
                Modifier
                    .width(320.dp)
                    .fillMaxHeight()
                    .background(BackgroundVoid.copy(0.5f))
                    .padding(horizontal = 18.dp)
            ) {
                SearchField(query, { query = it }, "Search site or username")
                Spacer(Modifier.height(12.dp))

                if (filtered.isEmpty()) {
                    EmptyState(
                        "ᛗ",
                        if (logins.isEmpty()) "NO LOGINS" else "NO MATCHES",
                        if (logins.isEmpty()) "Import a Migration Seal to begin" else "Try a different search"
                    )
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(filtered, key = { it.id }) { login ->
                            LoginRow(
                                login = login,
                                selected = login.id == selectedId,
                                flagged = findings[login.id] != null,
                                onClick = { selectedId = login.id }
                            )
                        }
                        item { Spacer(Modifier.height(20.dp)) }
                    }
                }
            }

            // ── Detail ────────────────────────────────────────────────────────
            Box(Modifier.weight(1f).fillMaxHeight().padding(28.dp)) {
                if (selected == null) {
                    EmptyState("ᛗ", "NOTHING SELECTED", "Choose a login from the list")
                } else {
                    LoginDetail(
                        login = selected,
                        sharedWith = findings[selected.id]?.sharedWith.orEmpty(),
                        onEdit = { editing = selected }
                    )
                }
            }
        }
    }

        Box(Modifier.align(Alignment.BottomEnd).padding(28.dp)) {
            AddButton(onClick = { creating = true })
        }
    }

    if (creating) LoginEditor(state, null) { creating = false }
    editing?.let { target -> LoginEditor(state, target) { editing = null } }
}

@Composable
private fun LoginRow(
    login: LoginEntity,
    selected: Boolean,
    flagged: Boolean,
    onClick: () -> Unit
) {
    val accent = accentFor(login.siteName)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) GoldDim.copy(0.3f) else Color.Transparent)
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
                login.siteName.take(1).uppercase(),
                color = accent,
                fontSize = 15.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Serif
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                login.siteName,
                color = if (selected) GoldIce else TextParchment,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1
            )
            Text(login.username, color = TextMuted, fontSize = 11.sp, maxLines = 1)
        }
        if (flagged) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(AmberWarn))
        }
    }
}

@Composable
private fun LoginDetail(login: LoginEntity, sharedWith: List<String>, onEdit: () -> Unit) {
    var revealed by remember(login.id) { mutableStateOf(false) }
    val accent = accentFor(login.siteName)
    val strength = remember(login.password) { Strength.of(login.password) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box(
                Modifier.size(52.dp).clip(CircleShape)
                    .background(accent.copy(0.14f))
                    .border(1.dp, accent.copy(0.4f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    login.siteName.take(1).uppercase(),
                    color = accent,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Serif
                )
            }
            Spacer(Modifier.width(16.dp))
            Column {
                Text(
                    login.siteName,
                    color = GoldIce,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Serif
                )
                Text("LOGIN RECORD", color = TextMuted, fontSize = 9.sp, letterSpacing = 3.sp)
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onEdit) {
                Text("EDIT", color = CyanGlow, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
        }

        Spacer(Modifier.height(26.dp))

        FieldCard("USERNAME", login.username, accent, copyLabel = "username")

        Spacer(Modifier.height(12.dp))

        GemCard(accent = CyanGlow, modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("PASSWORD", color = CyanGlow, fontSize = 9.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                StrengthBars(strength)
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (revealed) login.password else "•".repeat(login.password.length.coerceAtMost(18)),
                    color = TextParchment,
                    fontSize = 15.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { revealed = !revealed }) {
                    Icon(
                        if (revealed) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (revealed) "Hide" else "Reveal",
                        tint = TextSubtle,
                        modifier = Modifier.size(17.dp)
                    )
                }
                CopyButton(login.password, "password", CyanGlow)
            }
        }

        if (sharedWith.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            GemCard(accent = ExpenseRed, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "REUSED PASSWORD",
                    color = ExpenseRed,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "This password is also used for ${sharedWith.joinToString(", ")}. " +
                        "A breach at any one of them exposes all of them.",
                    color = TextSubtle,
                    fontSize = 12.sp,
                    lineHeight = 18.sp
                )
            }
        }
    }
}

@Composable
private fun FieldCard(label: String, value: String, accent: Color, copyLabel: String) {
    GemCard(accent = accent, modifier = Modifier.fillMaxWidth()) {
        Text(label, color = accent, fontSize = 9.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                value.ifBlank { "—" },
                color = TextParchment,
                fontSize = 15.sp,
                modifier = Modifier.weight(1f)
            )
            if (value.isNotBlank()) CopyButton(value, copyLabel, accent)
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
                Modifier
                    .padding(end = 3.dp)
                    .width(16.dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (i < strength.bars) color else SurfaceElevated)
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(strength.label, color = color, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    }
}
