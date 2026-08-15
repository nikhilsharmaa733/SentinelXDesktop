package com.nikhil.sentinelx.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.nikhil.sentinelx.desktop.ui.AppState
import com.nikhil.sentinelx.desktop.ui.BridgeCaptureRequest
import com.nikhil.sentinelx.desktop.ui.BridgeFillRequest
import com.nikhil.sentinelx.desktop.ui.theme.*

/**
 * The two moments the bridge surfaces to the user, mounted once at the app root
 * so they float over whatever section is showing. Both are true modal
 * `Dialog`s — a fill request is a live browser waiting on an answer, not a
 * background nicety, so it deserves focus.
 */
@Composable
fun BridgeDialogs(state: AppState) {
    state.bridge.pendingFill?.let { req ->
        FillApprovalDialog(
            req = req,
            requireMaster = state.bridge.requireMasterConfirm,
            verifyMaster = { state.verifyMasterPassword(it) }
        )
    }
    state.bridge.pendingCapture?.let { req ->
        CaptureConfirmDialog(req)
    }
}

@Composable
private fun FillApprovalDialog(
    req: BridgeFillRequest,
    requireMaster: Boolean,
    verifyMaster: (CharArray) -> Boolean
) {
    var master by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = { req.deny() }) {
        Column(
            Modifier
                .width(420.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(BackgroundDeep)
                .border(1.dp, GoldDark.copy(0.4f), RoundedCornerShape(20.dp))
                .padding(24.dp)
        ) {
            Text(
                "AUTOFILL REQUEST",
                color = CyanGlow, fontSize = 15.sp,
                fontWeight = FontWeight.Black, fontFamily = FontFamily.Serif, letterSpacing = 2.sp
            )
            Spacer(Modifier.height(14.dp))
            Text(
                "A browser wants to fill this login into",
                color = TextSubtle, fontSize = 12.sp
            )
            Text(req.domain, color = GoldBright, fontSize = 13.sp, fontWeight = FontWeight.Bold)

            Spacer(Modifier.height(16.dp))
            Column(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(SurfaceGem)
                    .padding(14.dp)
            ) {
                Text(req.login.siteName, color = TextParchment, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text(req.login.username, color = TextSubtle, fontSize = 12.sp)
            }

            if (requireMaster) {
                Spacer(Modifier.height(16.dp))
                EditorField(
                    value = master,
                    onValueChange = { master = it; wrong = false },
                    label = "Master password",
                    placeholder = "Confirm to release",
                )
                if (wrong) Text("Incorrect master password.", color = ExpenseRed, fontSize = 11.sp)
            }

            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                DialogText("DENY", TextMuted) { req.deny() }
                Spacer(Modifier.width(8.dp))
                DialogButton(if (req.domain.isBlank()) "FILL" else "FILL", CyanGlow) {
                    if (requireMaster) {
                        val ok = verifyMaster(master.toCharArray())
                        if (ok) req.approve() else wrong = true
                    } else {
                        req.approve()
                    }
                }
            }
        }
    }
}

@Composable
private fun CaptureConfirmDialog(req: BridgeCaptureRequest) {
    var site by remember { mutableStateOf(req.suggestedSite) }
    var showPass by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = { req.decline() }) {
        Column(
            Modifier
                .width(440.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(BackgroundDeep)
                .border(1.dp, GoldTarnished.copy(0.4f), RoundedCornerShape(20.dp))
                .padding(24.dp)
        ) {
            Text(
                "SEAL THIS LOGIN?",
                color = GoldTarnished, fontSize = 16.sp,
                fontWeight = FontWeight.Black, fontFamily = FontFamily.Serif, letterSpacing = 2.sp
            )
            Spacer(Modifier.height(4.dp))
            Text("Captured from ${req.domain}", color = TextMuted, fontSize = 11.sp)

            Spacer(Modifier.height(16.dp))
            EditorField(
                value = site,
                onValueChange = { site = it },
                label = "Site / service",
                placeholder = "Where does this belong?"
            )
            EditorField(
                value = req.username,
                onValueChange = {},
                label = "Username",
                accent = TextMuted
            )
            EditorField(
                value = if (showPass) req.password else "•".repeat(req.password.length.coerceAtMost(16)),
                onValueChange = {},
                label = "Password",
                accent = TextMuted,
                trailing = {
                    DialogText(if (showPass) "HIDE" else "SHOW", CyanGlow) { showPass = !showPass }
                }
            )

            if (req.duplicateOf != null) {
                Text(
                    "⚠ \"${req.duplicateOf.siteName}\" already holds a password for this username — " +
                        "sealing replaces it.",
                    color = AmberWarn, fontSize = 11.sp
                )
            }

            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                DialogText("NOT NOW", TextMuted) { req.decline() }
                Spacer(Modifier.width(8.dp))
                DialogButton(
                    if (req.duplicateOf != null) "REPLACE & SEAL" else "SEAL TO VAULT",
                    GoldTarnished,
                    enabled = site.isNotBlank()
                ) { req.confirm(site) }
            }
        }
    }
}

@Composable
private fun DialogText(label: String, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Text(
        label, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp)
    )
}

@Composable
private fun DialogButton(
    label: String,
    color: androidx.compose.ui.graphics.Color,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Text(
        label,
        color = if (enabled) BackgroundDeep else TextMuted,
        fontSize = 12.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (enabled) color else SurfaceElevated)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    )
}
