package com.nikhil.sentinelx.desktop.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nikhil.sentinelx.desktop.ui.theme.*

/**
 * Full-size pager over vault images — cash-book slips today, anything filename-listed
 * tomorrow. Lived privately in CashBookPane until the entry editor needed it too;
 * shared composables do not live in pane files.
 */
@Composable
fun SlipViewerDialog(
    names: List<String>,
    loader: (String) -> ByteArray?,
    label: String = "SLIP",
    initialIndex: Int = 0,
    onClose: () -> Unit
) {
    var index by remember { mutableStateOf(initialIndex) }
    val safe = index.coerceIn(0, (names.size - 1).coerceAtLeast(0))
    if (names.isEmpty()) { onClose(); return }

    AlertDialog(
        onDismissRequest = onClose,
        containerColor = BackgroundDeep,
        shape = RoundedCornerShape(18.dp),
        title = {
            Text(
                "$label ${safe + 1} OF ${names.size}",
                color = GoldTarnished, fontSize = 13.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 2.sp
            )
        },
        text = {
            Column(Modifier.width(560.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                VaultImage(
                    fileName = names[safe],
                    loader = loader,
                    modifier = Modifier.fillMaxWidth().height(460.dp).clip(RoundedCornerShape(12.dp))
                )
                if (names.size > 1) {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { index = (safe - 1 + names.size) % names.size }) {
                            Text("◀ PREV", color = CyanGlow, fontSize = 11.sp)
                        }
                        Spacer(Modifier.width(16.dp))
                        TextButton(onClick = { index = (safe + 1) % names.size }) {
                            Text("NEXT ▶", color = CyanGlow, fontSize = 11.sp)
                        }
                    }
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
