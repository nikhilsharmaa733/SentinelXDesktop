package com.nikhil.sentinelx.desktop.ui.panes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
    onClose: () -> Unit
) {
    var site by remember { mutableStateOf(existing?.siteName ?: "") }
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
