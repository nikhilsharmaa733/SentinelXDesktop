package com.nikhil.sentinelx.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nikhil.sentinelx.desktop.core.audit.PasswordGenerator
import com.nikhil.sentinelx.desktop.core.audit.Strength
import com.nikhil.sentinelx.desktop.ui.theme.*

/**
 * Standalone strong-password generator.
 *
 * The generator already lives inside the login editor, but the moment you most often
 * want a password is *before* there is an entry to put it in — signing up on a website
 * in the browser, say. This opens straight from the sidebar, generates, and copies to
 * the clipboard (30-second auto-clear via SecureClipboard) without touching the vault.
 */
@Composable
fun PasswordGeneratorDialog(onClose: () -> Unit) {
    var length by remember { mutableStateOf(20) }
    var upper by remember { mutableStateOf(true) }
    var digits by remember { mutableStateOf(true) }
    var symbols by remember { mutableStateOf(true) }
    var avoidAmbiguous by remember { mutableStateOf(true) }
    // Bumped to force a fresh password without changing any option.
    var nonce by remember { mutableStateOf(0) }

    // Recomputes on any option change or a manual regenerate — remember(keys) discards
    // and re-runs whenever a key differs, so this is the whole generation trigger.
    val password = remember(length, upper, digits, symbols, avoidAmbiguous, nonce) {
        PasswordGenerator.generate(
            PasswordGenerator.Options(
                length = length,
                upper = upper,
                digits = digits,
                symbols = symbols,
                avoidAmbiguous = avoidAmbiguous
            )
        )
    }
    val strength = remember(password) { Strength.of(password) }

    AlertDialog(
        onDismissRequest = onClose,
        containerColor = BackgroundDeep,
        shape = RoundedCornerShape(18.dp),
        title = {
            Text(
                "PASSWORD GENERATOR",
                color = GoldTarnished, fontWeight = FontWeight.Bold, fontSize = 14.sp, letterSpacing = 2.sp
            )
        },
        text = {
            Column(Modifier.width(420.dp)) {
                // The password itself, with copy and regenerate right on it.
                Row(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(BackgroundVoid)
                        .border(1.dp, GoldDark.copy(0.4f), RoundedCornerShape(10.dp))
                        .padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        password,
                        color = GoldIce,
                        fontSize = 16.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    CopyButton(value = password, label = "password", tint = CyanGlow)
                    Spacer(Modifier.width(2.dp))
                    IconButton(onClick = { nonce++ }) {
                        Icon(
                            Icons.Default.Autorenew,
                            contentDescription = "Generate a new password",
                            tint = CyanGlow,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Strength meter — same scale as the login editor and the phone app.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val tone = when (strength) {
                        Strength.NONE -> TextMuted
                        Strength.WEAK -> ExpenseRed
                        Strength.FAIR -> AmberWarn
                        Strength.STRONG -> GoldTarnished
                        Strength.FORTRESS -> IncomeGreen
                    }
                    repeat(4) { i ->
                        Box(
                            Modifier.padding(end = 4.dp).width(46.dp).height(4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(if (i < strength.bars) tone else SurfaceElevated)
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(strength.label, color = tone, fontSize = 10.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold)
                }

                Spacer(Modifier.height(18.dp))

                // Length
                Text("LENGTH  $length", color = TextSubtle, fontSize = 10.sp, letterSpacing = 1.sp)
                Slider(
                    value = length.toFloat(),
                    onValueChange = { length = it.toInt() },
                    valueRange = 8f..48f,
                    colors = SliderDefaults.colors(
                        thumbColor = CyanGlow,
                        activeTrackColor = CyanGlow.copy(0.6f),
                        inactiveTrackColor = SurfaceElevated
                    )
                )

                Spacer(Modifier.height(8.dp))

                // Character-set toggles. Lowercase is always on (there is no way to make
                // a usable password with none of the sets), so it isn't offered.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GenToggle("A-Z", upper) { upper = !upper }
                    GenToggle("0-9", digits) { digits = !digits }
                    GenToggle("!@#", symbols) { symbols = !symbols }
                    GenToggle("No look-alikes", avoidAmbiguous) { avoidAmbiguous = !avoidAmbiguous }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onClose) {
                Text("CLOSE", color = CyanGlow, fontWeight = FontWeight.Bold)
            }
        }
    )
}

@Composable
private fun GenToggle(label: String, on: Boolean, onToggle: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(8.dp))
            .background(if (on) CyanGlow.copy(0.18f) else SurfaceStone)
            .border(1.dp, if (on) CyanGlow.copy(0.4f) else GoldDark.copy(0.15f), RoundedCornerShape(8.dp))
            .clickable { onToggle() }
            .padding(horizontal = 10.dp, vertical = 7.dp)
    ) {
        Text(
            label,
            color = if (on) CyanGlow else TextMuted,
            fontSize = 10.sp,
            letterSpacing = 0.5.sp,
            fontWeight = if (on) FontWeight.Bold else FontWeight.Normal
        )
    }
}
