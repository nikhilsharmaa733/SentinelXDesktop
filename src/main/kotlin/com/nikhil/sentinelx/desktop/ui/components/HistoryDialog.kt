package com.nikhil.sentinelx.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nikhil.sentinelx.desktop.ui.AppState
import com.nikhil.sentinelx.desktop.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val stamp = SimpleDateFormat("d MMM, HH:mm:ss", Locale.getDefault())

/**
 * Version history — the undo mechanism.
 *
 * Built on the snapshots the store already writes before every save, rather than a
 * parallel recycle bin. One mechanism covers an accidental delete, a bad edit, and a
 * botched import alike; a bin would only cover the first. Snapshots are metadata
 * only (tens of KB), so keeping twenty costs nothing.
 *
 * Each entry shows what it contains, so you can tell which one you want without
 * restoring it first — "12 logins, 6 cards" is far more useful than a bare time.
 */
@Composable
fun HistoryDialog(state: AppState, onClose: () -> Unit) {
    val versions = remember { state.history() }
    var selected by remember { mutableStateOf<Long?>(null) }
    var confirming by remember { mutableStateOf(false) }
    val current = state.backup

    AlertDialog(
        onDismissRequest = onClose,
        containerColor = BackgroundDeep,
        shape = RoundedCornerShape(20.dp),
        title = {
            Text(
                "VERSION HISTORY",
                color = GoldTarnished, fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp, fontSize = 14.sp
            )
        },
        text = {
            Column(Modifier.width(460.dp)) {
                Text(
                    "A snapshot is taken before every change. Restoring one is itself " +
                        "recorded, so this is always reversible.",
                    color = TextSubtle, fontSize = 11.sp, lineHeight = 16.sp
                )
                Spacer(Modifier.height(14.dp))

                Row(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(CyanGlowDim)
                        .padding(horizontal = 12.dp, vertical = 9.dp)
                ) {
                    Text("NOW", color = CyanGlow, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.width(120.dp))
                    Text(summarise(current), color = TextSubtle, fontSize = 10.sp)
                }

                Spacer(Modifier.height(8.dp))

                if (versions.isEmpty()) {
                    Text("No snapshots yet.", color = TextMuted, fontSize = 12.sp,
                        modifier = Modifier.padding(vertical = 14.dp))
                } else {
                    LazyColumn(Modifier.heightIn(max = 280.dp)) {
                        items(versions) { ts ->
                            val on = selected == ts
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 2.dp)
                                    .clip(RoundedCornerShape(9.dp))
                                    .background(if (on) GoldDim.copy(0.35f) else Color.Transparent)
                                    .clickable { selected = ts }
                                    .padding(horizontal = 12.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    stamp.format(Date(ts)),
                                    color = if (on) GoldIce else TextParchment,
                                    fontSize = 11.sp,
                                    modifier = Modifier.width(120.dp)
                                )
                                // Loading each snapshot to summarise it costs an
                                // Argon2-free AES decrypt of a few KB — cheap enough
                                // to be worth the clarity.
                                val preview = remember(ts) { state.previewVersion(ts) }
                                Text(
                                    preview?.let { summarise(it) } ?: "unreadable",
                                    color = TextMuted, fontSize = 10.sp
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = selected != null,
                onClick = { confirming = true }
            ) {
                Text(
                    "RESTORE",
                    color = if (selected != null) AmberWarn else TextMuted,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onClose) { Text("CLOSE", color = TextMuted) }
        }
    )

    if (confirming && selected != null) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            containerColor = BackgroundDeep,
            shape = RoundedCornerShape(18.dp),
            title = { Text("RESTORE SNAPSHOT?", color = AmberWarn, fontWeight = FontWeight.Black, fontSize = 14.sp) },
            text = {
                Text(
                    "The vault will be replaced with its state at " +
                        "${stamp.format(Date(selected!!))}. The current state is snapshotted " +
                        "first, so you can come back.",
                    color = TextSubtle, fontSize = 12.sp, lineHeight = 18.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = { state.restoreVersion(selected!!); confirming = false; onClose() },
                    colors = ButtonDefaults.buttonColors(containerColor = AmberWarn),
                    shape = RoundedCornerShape(9.dp)
                ) { Text("RESTORE", color = BackgroundVoid, fontWeight = FontWeight.Bold, fontSize = 12.sp) }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text("CANCEL", color = TextMuted) }
            }
        )
    }
}

private fun summarise(b: com.nikhil.sentinelx.desktop.core.format.MasterBackup): String =
    listOfNotNull(
        b.logins.size.takeIf { it > 0 }?.let { "$it logins" },
        b.artifacts.size.takeIf { it > 0 }?.let { "$it cards" },
        b.prophecies.size.takeIf { it > 0 }?.let { "$it notes" },
        b.chronicles.size.takeIf { it > 0 }?.let { "$it docs" },
        b.ledger.size.takeIf { it > 0 }?.let { "$it tx" }
    ).joinToString(", ").ifBlank { "empty" }
