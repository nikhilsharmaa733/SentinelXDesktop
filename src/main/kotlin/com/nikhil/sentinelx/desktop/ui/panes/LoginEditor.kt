package com.nikhil.sentinelx.desktop.ui.panes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nikhil.sentinelx.desktop.core.audit.PasswordGenerator
import com.nikhil.sentinelx.desktop.core.audit.Strength
import com.nikhil.sentinelx.desktop.core.format.LoginEntity
import com.nikhil.sentinelx.desktop.ui.AppState
import com.nikhil.sentinelx.desktop.ui.components.*
import com.nikhil.sentinelx.desktop.ui.theme.*

/**
 * Add / edit a login, with a generator built in.
 *
 * The generator sits next to the field rather than behind a separate screen, because
 * the moment you want a strong password is precisely while creating the entry — the
 * phone app had none, which is why so many entries share one.
 */
@Composable
fun LoginEditor(
    state: AppState,
    existing: LoginEntity?,
    /**
     * Pre-fills the site when adding another account to one that already exists.
     * Mirrors the phone's `prefillSiteName`, and matters more here because logins
     * are grouped by site — a typo would silently create a second group rather
     * than joining the existing one.
     */
    prefillSite: String? = null,
    onClose: () -> Unit
) {
    var site by remember { mutableStateOf(existing?.siteName ?: prefillSite ?: "") }
    var user by remember { mutableStateOf(existing?.username ?: "") }
    var password by remember { mutableStateOf(existing?.password ?: "") }
    var confirmDelete by remember { mutableStateOf(false) }

    var length by remember { mutableStateOf(20) }
    var useSymbols by remember { mutableStateOf(true) }

    val strength = remember(password) { Strength.of(password) }
    val duplicate = remember(password, state.backup.logins) {
        state.backup.logins.filter { it.password == password && it.id != existing?.id && password.isNotBlank() }
    }

    EditorDialog(
        title = if (existing == null) "New Login" else "Edit Login",
        canSave = site.isNotBlank() && password.isNotBlank(),
        onSave = {
            state.upsertLogin(
                LoginEntity(
                    id = existing?.id ?: 0,
                    // Matches the phone's toVaultTitle(), which title-cases site
                    // names so "github" and "GitHub" don't become two entries.
                    siteName = site.trim().lowercase().replaceFirstChar { it.uppercase() },
                    username = user.trim(),
                    password = password
                )
            )
            onClose()
        },
        onCancel = onClose,
        onDelete = existing?.let { { confirmDelete = true } }
    ) {
        EditorField(site, { site = it }, "Site", placeholder = "Github")

        // Offers the sites already in the vault as you type, the way the phone's
        // AddLoginScreen does. Logins are grouped by site name, so a second account on
        // an existing service has to land on the *same* string — picking it beats
        // spelling it, and this is the field where a typo costs the most.
        val knownSites = remember(state.backup.logins) {
            state.backup.logins.map { it.siteName }.distinct().sorted()
        }
        val suggestions = remember(site, knownSites) {
            val typed = site.trim()
            if (typed.isEmpty()) emptyList()
            else {
                // Prefix matches first — they are what you were most likely typing —
                // then anything else containing it, which the phone does not offer.
                val byPrefix = knownSites.filter {
                    it.startsWith(typed, true) && !it.equals(typed, true)
                }
                val byContent = knownSites.filter {
                    it.contains(typed, true) && !it.equals(typed, true) && it !in byPrefix
                }
                (byPrefix + byContent).take(5)
            }
        }
        if (suggestions.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 12.dp)
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "EXISTING", color = TextMuted, fontSize = 8.sp,
                    letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.width(10.dp))
                suggestions.forEach { name ->
                    Box(
                        Modifier.padding(end = 6.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(CyanGlow.copy(0.10f))
                            .clickable { site = name }
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Text(name, color = CyanGlow, fontSize = 11.sp, maxLines = 1)
                    }
                }
            }
        }

        // Near-miss warning. Catches what the suggestions above cannot: site names are
        // title-cased on save, so "github" and "GitHub" collapse — but "Git hub"
        // matches neither by prefix nor by content and would silently become its own
        // group.
        val nearMatch = remember(site, state.backup.logins) {
            val trimmed = site.trim()
            if (trimmed.length < 3) null
            else state.backup.logins.map { it.siteName }.distinct().firstOrNull { existingSite ->
                !existingSite.equals(trimmed, true) &&
                    existingSite.replace(" ", "").equals(trimmed.replace(" ", ""), true)
            }
        }
        if (nearMatch != null && existing == null) {
            Text(
                "Did you mean \"$nearMatch\"? Otherwise this becomes a separate group.",
                color = AmberWarn, fontSize = 11.sp,
                modifier = Modifier.padding(bottom = 10.dp)
            )
        }
        EditorField(user, { user = it }, "Username", placeholder = "you@example.com")

        EditorField(
            value = password,
            onValueChange = { password = it },
            label = "Password",
            accent = CyanGlow,
            trailing = {
                Row {
                    IconButton(onClick = {
                        password = PasswordGenerator.generate(
                            PasswordGenerator.Options(length = length, symbols = useSymbols)
                        )
                    }) {
                        Icon(
                            Icons.Default.Autorenew,
                            contentDescription = "Generate password",
                            tint = CyanGlow,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }
            }
        )

        // Generator controls
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 10.dp)) {
            Text("LENGTH $length", color = TextMuted, fontSize = 9.sp, letterSpacing = 1.sp)
            Spacer(Modifier.width(10.dp))
            Slider(
                value = length.toFloat(),
                onValueChange = { length = it.toInt() },
                valueRange = 8f..48f,
                modifier = Modifier.width(150.dp).height(20.dp),
                colors = SliderDefaults.colors(
                    thumbColor = CyanGlow,
                    activeTrackColor = CyanGlow.copy(0.6f),
                    inactiveTrackColor = SurfaceElevated
                )
            )
            Spacer(Modifier.width(14.dp))
            Box(
                Modifier.clip(CircleShape)
                    .background(if (useSymbols) CyanGlow.copy(0.2f) else SurfaceStone)
                    .clickable { useSymbols = !useSymbols }
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text(
                    "SYMBOLS",
                    color = if (useSymbols) CyanGlow else TextMuted,
                    fontSize = 9.sp,
                    letterSpacing = 1.sp
                )
            }
        }

        // Live strength feedback
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
            val tone = when (strength) {
                Strength.NONE -> TextMuted
                Strength.WEAK -> ExpenseRed
                Strength.FAIR -> AmberWarn
                Strength.STRONG -> GoldTarnished
                Strength.FORTRESS -> IncomeGreen
            }
            repeat(4) { i ->
                Box(
                    Modifier.padding(end = 3.dp).width(28.dp).height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (i < strength.bars) tone else SurfaceElevated)
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(strength.label, color = tone, fontSize = 9.sp, letterSpacing = 1.sp)
        }

        // Warn about reuse at the moment of creation, not weeks later in an audit.
        if (duplicate.isNotEmpty()) {
            Text(
                "⚠ Already used for ${duplicate.joinToString(", ") { it.siteName }}",
                color = ExpenseRed,
                fontSize = 11.sp
            )
        }
    }

    if (confirmDelete && existing != null) {
        ConfirmDelete(
            itemName = existing.siteName,
            onConfirm = { state.deleteLogin(existing.id); confirmDelete = false; onClose() },
            onDismiss = { confirmDelete = false }
        )
    }
}
