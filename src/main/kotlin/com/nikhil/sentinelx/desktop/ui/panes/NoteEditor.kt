package com.nikhil.sentinelx.desktop.ui.panes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nikhil.sentinelx.desktop.core.format.*
import com.nikhil.sentinelx.desktop.ui.AppState
import com.nikhil.sentinelx.desktop.ui.components.*
import com.nikhil.sentinelx.desktop.ui.theme.*

@Composable
fun NoteEditor(
    state: AppState,
    existing: ProphecyEntity?,
    prefillFolder: String? = null,
    onClose: () -> Unit
) {
    var title by remember { mutableStateOf(existing?.title ?: "") }
    var content by remember { mutableStateOf(existing?.content ?: "") }
    var sigil by remember { mutableStateOf(existing?.sigil ?: "GENERAL") }
    var colorHex by remember { mutableStateOf(existing?.colorHex) }
    var folderText by remember { mutableStateOf(existing?.folderName() ?: prefillFolder ?: "") }
    var isPinned by remember { mutableStateOf(existing?.isPinned ?: false) }
    var isLockedNote by remember { mutableStateOf(existing?.isLocked ?: false) }
    var isArchived by remember { mutableStateOf(existing?.isArchived ?: false) }
    var isChecklist by remember { mutableStateOf(existing?.isChecklist() ?: false) }
    var items by remember { mutableStateOf(existing?.checklistItems() ?: emptyList()) }
    var confirmDelete by remember { mutableStateOf(false) }

    val accent = parseNoteColor(colorHex) ?: sigilOf(sigil).color
    val words = remember(content) { content.split(Regex("\\s+")).count { it.isNotBlank() } }

    // The title is the note's identity on both apps (unique index + merge key), and
    // the phone's insert is REPLACE — an unguarded duplicate would silently destroy
    // the other note there rather than fail.
    val titleClash = remember(title, state.backup.prophecies) {
        state.backup.prophecies.any {
            it.title.trim().equals(title.trim(), ignoreCase = true) && it.id != existing?.id
        } && title.isNotBlank()
    }

    EditorDialog(
        title = if (existing == null) "New Note" else "Edit Note",
        canSave = title.isNotBlank() && !titleClash,
        onSave = {
            val cleanItems = items.map { it.copy(text = it.text.trim()) }.filter { it.text.isNotEmpty() }
            state.upsertProphecy(
                ProphecyEntity(
                    id = existing?.id ?: 0,
                    title = title.trim(),
                    // For a checklist, content is the plain-text mirror of the items —
                    // what search, copy and pre-v8 builds read.
                    content = if (isChecklist) itemsToText(cleanItems) else content,
                    sigil = sigil,
                    type = if (isChecklist) Notes.TYPE_CHECKLIST else Notes.TYPE_TEXT,
                    isPinned = isPinned,
                    isArchived = isArchived,
                    isLocked = isLockedNote,
                    colorHex = colorHex,
                    checkItems = if (isChecklist) cleanItems.encodeCheckItems() else null,
                    folder = folderText.trim().takeIf { it.isNotEmpty() }
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
                "⚠ A note with this title already exists. The title is the note's identity " +
                    "on both apps — saving would silently overwrite the other one.",
                color = ExpenseRed,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                modifier = Modifier.padding(bottom = 10.dp)
            )
        }

        // ── Pin · lock · archive ─────────────────────────────────────────────
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            StateChip("ᛘ PINNED", isPinned, GoldBright) { isPinned = !isPinned }
            StateChip("🔒 LOCKED", isLockedNote, AmberWarn) { isLockedNote = !isLockedNote }
            StateChip("ARCHIVED", isArchived, PurpleMystic) { isArchived = !isArchived }
        }

        // ── Mode ─────────────────────────────────────────────────────────────
        Row(
            Modifier.fillMaxWidth().height(34.dp).padding(bottom = 0.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(SurfaceStone)
                .border(1.dp, GoldDark.copy(0.15f), RoundedCornerShape(9.dp))
                .padding(3.dp)
        ) {
            ModeHalf("✎ TEXT", !isChecklist, accent, Modifier.weight(1f)) {
                if (isChecklist) {
                    content = itemsToText(items.filter { it.text.isNotBlank() })
                    isChecklist = false
                }
            }
            ModeHalf("☑ CHECKLIST", isChecklist, accent, Modifier.weight(1f)) {
                if (!isChecklist) {
                    items = textToItems(content)
                    isChecklist = true
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        if (!isChecklist) {
            EditorField(
                value = content,
                onValueChange = { content = it },
                label = "Content",
                singleLine = false,
                minLines = 8,
                placeholder = "Write freely…"
            )
            Text("$words words · ${content.length} characters", color = TextMuted, fontSize = 10.sp)
        } else {
            Text(
                "STEPS",
                color = accent, fontSize = 8.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 2.dp, bottom = 6.dp)
            )
            if (items.isNotEmpty()) {
                NoteProgressTrack(
                    items.count { it.done }.toFloat() / items.size, accent
                )
                Spacer(Modifier.height(8.dp))
            }
            items.forEachIndexed { index, item ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(22.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (item.done) accent.copy(0.16f) else SurfaceStone)
                            .border(1.dp, if (item.done) accent.copy(0.5f) else GoldDark.copy(0.25f), RoundedCornerShape(6.dp))
                            .clickable {
                                items = items.toMutableList().also { it[index] = item.copy(done = !item.done) }
                            },
                        Alignment.Center
                    ) {
                        if (item.done) Text("✓", color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(9.dp))
                    BasicTextField(
                        value = item.text,
                        onValueChange = { text ->
                            items = items.toMutableList().also { it[index] = item.copy(text = text) }
                        },
                        singleLine = true,
                        textStyle = TextStyle(
                            color = if (item.done) TextMuted else TextParchment,
                            fontSize = 13.sp,
                            textDecoration = if (item.done) TextDecoration.LineThrough else null
                        ),
                        cursorBrush = SolidColor(accent),
                        modifier = Modifier.weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(SurfaceGem)
                            .border(1.dp, GoldDark.copy(0.18f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        decorationBox = { inner ->
                            if (item.text.isEmpty()) Text("Step…", color = TextMuted, fontSize = 13.sp)
                            inner()
                        }
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        "✕", color = TextMuted, fontSize = 12.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { items = items.toMutableList().also { it.removeAt(index) } }
                            .padding(5.dp)
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { items = items + CheckItem("") }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("+", color = accent, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Text("ADD STEP", color = accent, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
            }
            Text(
                "${items.size} steps · ${items.count { it.done }} done",
                color = TextMuted, fontSize = 10.sp
            )
        }

        Spacer(Modifier.height(14.dp))

        // ── Sigil ────────────────────────────────────────────────────────────
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

        // ── Colour ───────────────────────────────────────────────────────────
        Text(
            "COLOR",
            color = accent,
            fontSize = 8.sp,
            letterSpacing = 1.5.sp,
            modifier = Modifier.padding(start = 2.dp, bottom = 6.dp)
        )
        Row(Modifier.fillMaxWidth().padding(bottom = 14.dp).horizontalScroll(rememberScrollState())) {
            noteColorChoices.forEach { choice ->
                val swatch = parseNoteColor(choice)
                val on = colorHex == choice
                Box(
                    Modifier.padding(end = 8.dp).size(26.dp)
                        .clip(CircleShape)
                        .background(swatch?.copy(0.85f) ?: SurfaceStone)
                        .border(
                            width = if (on) 2.dp else 1.dp,
                            color = if (on) GoldIce else GoldDark.copy(0.25f),
                            shape = CircleShape
                        )
                        .clickable { colorHex = choice },
                    Alignment.Center
                ) {
                    if (swatch == null) Text("⌀", color = TextMuted, fontSize = 11.sp)
                    else if (on) Text("✓", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // ── Folder ───────────────────────────────────────────────────────────
        EditorField(folderText, { folderText = it }, "Folder", placeholder = "No folder")
        // Existing folders as one-tap chips — picking beats spelling, and a typo
        // here would quietly start a second folder. Records come first so an empty
        // (note-less) folder is still offered.
        val knownFolders = remember(state.backup.prophecies, state.backup.noteFolders) {
            (state.backup.noteFolders.map { it.name } +
                state.backup.prophecies.mapNotNull { it.folderName() })
                .distinctBy { it.lowercase() }
                .sortedWith(String.CASE_INSENSITIVE_ORDER)
        }
        val folderSuggestions = remember(folderText, knownFolders) {
            knownFolders.filter {
                !it.equals(folderText.trim(), true) &&
                    (folderText.isBlank() || it.contains(folderText.trim(), true))
            }.take(6)
        }
        if (folderSuggestions.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 8.dp).horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically
            ) {
                folderSuggestions.forEach { name ->
                    Box(
                        Modifier.padding(end = 6.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(GoldTarnished.copy(0.10f))
                            .clickable { folderText = name }
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Text(name, color = GoldTarnished, fontSize = 11.sp, maxLines = 1)
                    }
                }
            }
        }
    }

    if (confirmDelete && existing != null) {
        ConfirmDelete(
            itemName = existing.title.ifBlank { "Untitled" },
            onConfirm = { state.deleteProphecy(existing.id); confirmDelete = false; onClose() },
            onDismiss = { confirmDelete = false }
        )
    }
}

@Composable
private fun StateChip(label: String, on: Boolean, color: Color, onClick: () -> Unit) {
    Box(
        Modifier.padding(end = 8.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(if (on) color.copy(0.16f) else SurfaceStone)
            .border(1.dp, if (on) color.copy(0.5f) else GoldDark.copy(0.15f), RoundedCornerShape(9.dp))
            .clickable { onClick() }
            .padding(horizontal = 11.dp, vertical = 6.dp)
    ) {
        Text(
            label,
            color = if (on) color else TextMuted,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
    }
}

@Composable
private fun ModeHalf(label: String, selected: Boolean, accent: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(7.dp))
            .background(if (selected) accent.copy(0.18f) else Color.Transparent)
            .clickable { onClick() },
        Alignment.Center
    ) {
        Text(
            label,
            color = if (selected) accent else TextMuted,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp
        )
    }
}
