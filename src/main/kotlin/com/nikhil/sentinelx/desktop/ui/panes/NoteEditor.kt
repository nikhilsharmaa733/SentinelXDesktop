package com.nikhil.sentinelx.desktop.ui.panes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nikhil.sentinelx.desktop.core.format.ProphecyEntity
import com.nikhil.sentinelx.desktop.ui.AppState
import com.nikhil.sentinelx.desktop.ui.components.*
import com.nikhil.sentinelx.desktop.ui.theme.*

@Composable
fun NoteEditor(state: AppState, existing: ProphecyEntity?, onClose: () -> Unit) {
    var title by remember { mutableStateOf(existing?.title ?: "") }
    var content by remember { mutableStateOf(existing?.content ?: "") }
    var sigil by remember { mutableStateOf(existing?.sigil ?: "GENERAL") }
    var confirmDelete by remember { mutableStateOf(false) }

    val words = remember(content) { content.split(Regex("\\s+")).count { it.isNotBlank() } }

    // The phone enforces a unique index on prophecy titles, so a duplicate here
    // would collide on restore and silently overwrite the other note.
    val titleClash = remember(title, state.backup.prophecies) {
        state.backup.prophecies.any {
            it.title.equals(title.trim(), ignoreCase = true) && it.id != existing?.id
        } && title.isNotBlank()
    }

    EditorDialog(
        title = if (existing == null) "New Note" else "Edit Note",
        canSave = title.isNotBlank() && !titleClash,
        onSave = {
            state.upsertProphecy(
                ProphecyEntity(
                    id = existing?.id ?: 0,
                    title = title.trim(),
                    content = content,
                    sigil = sigil
                )
            )
            onClose()
        },
        onCancel = onClose,
        onDelete = existing?.let { { confirmDelete = true } },
        width = 620
    ) {
        EditorField(title, { title = it }, "Title", placeholder = "Untitled")

        if (titleClash) {
            Text(
                "⚠ A note with this title already exists. The phone keys notes by title, " +
                    "so restoring would overwrite the other one.",
                color = ExpenseRed,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                modifier = Modifier.padding(bottom = 10.dp)
            )
        }

        Text(
            "SIGIL",
            color = GoldTarnished,
            fontSize = 8.sp,
            letterSpacing = 1.5.sp,
            modifier = Modifier.padding(start = 2.dp, bottom = 6.dp)
        )
        Row(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
            sigils.forEach { s ->
                val on = sigil == s.name
                Column(
                    Modifier
                        .padding(end = 8.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (on) s.color.copy(0.18f) else SurfaceStone)
                        .border(1.dp, if (on) s.color.copy(0.5f) else GoldDark.copy(0.15f), RoundedCornerShape(10.dp))
                        .clickable { sigil = s.name }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(s.glyph, color = if (on) s.color else TextMuted, fontSize = 15.sp)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        s.name.take(4),
                        color = if (on) s.color else TextMuted,
                        fontSize = 7.sp,
                        letterSpacing = 0.5.sp
                    )
                }
            }
        }

        EditorField(
            value = content,
            onValueChange = { content = it },
            label = "Content",
            singleLine = false,
            minLines = 8,
            placeholder = "Write freely…"
        )

        Text("$words words · ${content.length} characters", color = TextMuted, fontSize = 10.sp)
    }

    if (confirmDelete && existing != null) {
        ConfirmDelete(
            itemName = existing.title.ifBlank { "Untitled" },
            onConfirm = { state.deleteProphecy(existing.id); confirmDelete = false; onClose() },
            onDismiss = { confirmDelete = false }
        )
    }
}
