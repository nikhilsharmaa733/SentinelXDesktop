package com.nikhil.sentinelx.desktop.ui.panes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nikhil.sentinelx.desktop.core.format.*
import com.nikhil.sentinelx.desktop.ui.AppState
import com.nikhil.sentinelx.desktop.ui.PanelRequest
import com.nikhil.sentinelx.desktop.ui.Section
import com.nikhil.sentinelx.desktop.ui.components.*
import com.nikhil.sentinelx.desktop.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Sigil definitions, matching `sigilDefs` in the Android app. */
data class Sigil(val name: String, val color: Color, val glyph: String)

val sigils = listOf(
    Sigil("GENERAL", GoldTarnished, "ᚠ"),
    Sigil("SECRET", PurpleMystic, "ᛉ"),
    Sigil("BATTLE", ExpenseRed, "ᛏ"),
    Sigil("WISDOM", CyanGlow, "ᚱ"),
    Sigil("WEALTH", IncomeGreen, "ᚢ"),
    Sigil("WARNING", AmberWarn, "ᛜ")
)

fun sigilOf(name: String): Sigil = sigils.firstOrNull { it.name == name } ?: sigils[0]

/**
 * Per-note colours, hex-identical to `noteColorChoices` on the phone so a note keeps
 * its colour across a merge. The stored value is the hex, so anything the phone sends
 * renders here even if this list drifts.
 */
val noteColorChoices: List<String?> = listOf(
    null, "#B0413E", "#B07C3E", "#7A8C3E", "#3E8C6B",
    "#3E7A8C", "#4E5FA8", "#7B4E9E", "#9E4E7B"
)

/** Never throws — a hand-edited or foreign hex just falls back to no colour. */
fun parseNoteColor(hex: String?): Color? = hex?.trim()?.removePrefix("#")
    ?.takeIf { it.length == 6 }
    ?.let { runCatching { Color(0xFF000000L or it.toLong(16)) }.getOrNull() }

/** Thin checklist progress track, shared by the list row, the reader and the editor. */
@Composable
fun NoteProgressTrack(fraction: Float, accent: Color, height: androidx.compose.ui.unit.Dp = 3.dp) {
    Box(
        Modifier.fillMaxWidth().height(height)
            .clip(RoundedCornerShape(2.dp))
            .background(SurfaceElevated)
    ) {
        Box(
            Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight()
                .clip(RoundedCornerShape(2.dp))
                .background(accent.copy(0.8f))
        )
    }
}

private val dateFormat = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
private val sortModes = listOf("RECENT", "TITLE", "SIGIL")

@Composable
fun NotesPane(state: AppState) {
    var query by remember { mutableStateOf("") }
    var sigilFilter by remember { mutableStateOf<String?>(null) }
    var folderFilter by remember { mutableStateOf<String?>(null) }
    var showArchive by remember { mutableStateOf(false) }
    var sortIndex by remember { mutableStateOf(0) }
    var selectedId by remember { mutableStateOf<Int?>(null) }

    val notes = state.backup.prophecies

    // Folders exist exactly as long as a note claims one — the `book` pattern.
    val folders = remember(notes) {
        notes.filter { !it.isArchived }.mapNotNull { it.folderName() }
            .groupingBy { it }.eachCount().toSortedMap(String.CASE_INSENSITIVE_ORDER)
    }
    val archivedCount = remember(notes) { notes.count { it.isArchived } }
    LaunchedEffect(folders.keys.toList()) {
        if (folderFilter != null && folderFilter !in folders) folderFilter = null
    }

    val filtered = remember(notes, query, sigilFilter, folderFilter, showArchive, sortIndex) {
        val searching = query.isNotBlank()
        val base = notes.filter { n ->
            n.isArchived == showArchive &&
                (sigilFilter == null || n.sigil == sigilFilter) &&
                // Search reaches across folders — the note you are typing the name
                // of should not hide behind a filter you forgot you set.
                (searching || folderFilter == null || n.folderName() == folderFilter) &&
                n.matchesQuery(query)
        }
        val sorted = when (sortModes[sortIndex]) {
            "TITLE" -> base.sortedBy { it.title.lowercase() }
            "SIGIL" -> base.sortedWith(compareBy({ it.sigil }, { -it.timestamp }))
            else -> base.sortedByDescending { it.timestamp }
        }
        // Pinned float to the top whatever the sort — that is what a pin is.
        sorted.sortedByDescending { it.isPinned }
    }

    val selected = filtered.firstOrNull { it.id == selectedId }
        ?: filtered.firstOrNull().also { selectedId = it?.id }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            PaneHeader(
                "Notes",
                "${notes.count { !it.isArchived }} entries" +
                    (if (archivedCount > 0) " · $archivedCount archived" else "")
            ) { TransferActions(state, Section.NOTES) }

            Row(Modifier.fillMaxSize()) {
                Column(
                    Modifier.width(330.dp).fillMaxHeight()
                        .background(BackgroundVoid.copy(0.5f))
                        .padding(horizontal = 18.dp)
                ) {
                    SearchField(query, { query = it }, "Search notes, steps and folders")
                    Spacer(Modifier.height(10.dp))

                    // Folder rail. ARCHIVE lives here too: archived notes are filed
                    // away, not gone, and this is where you find them again.
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilterChipPill("ALL", folderFilter == null && !showArchive, GoldTarnished) {
                            folderFilter = null; showArchive = false
                        }
                        folders.forEach { (name, count) ->
                            FilterChipPill("$name ($count)", folderFilter == name && !showArchive, GoldBright) {
                                folderFilter = if (folderFilter == name) null else name
                                showArchive = false
                            }
                        }
                        FilterChipPill(
                            if (archivedCount > 0) "ARCHIVE ($archivedCount)" else "ARCHIVE",
                            showArchive, PurpleMystic
                        ) { showArchive = !showArchive }
                    }

                    Spacer(Modifier.height(8.dp))

                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Row(
                            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            sigils.forEach { s ->
                                if (notes.any { it.sigil == s.name && it.isArchived == showArchive }) {
                                    FilterChipPill(s.glyph, sigilFilter == s.name, s.color) {
                                        sigilFilter = if (sigilFilter == s.name) null else s.name
                                    }
                                }
                            }
                        }
                        Box(
                            Modifier.clip(CircleShape)
                                .background(SurfaceStone)
                                .clickable { sortIndex = (sortIndex + 1) % sortModes.size }
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Text(
                                "⇅ ${sortModes[sortIndex]}",
                                color = CyanGlow, fontSize = 9.sp,
                                fontWeight = FontWeight.Bold, letterSpacing = 1.sp
                            )
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    if (filtered.isEmpty()) {
                        EmptyState(
                            "ᚱ",
                            when {
                                showArchive && archivedCount == 0 -> "ARCHIVE EMPTY"
                                notes.isEmpty() -> "NO NOTES"
                                else -> "NO MATCHES"
                            },
                            when {
                                showArchive && archivedCount == 0 -> "Archived notes rest here"
                                notes.isEmpty() -> "Create one, or import a Migration Seal"
                                else -> "Try a different search"
                            }
                        )
                    } else {
                        val pinnedCount = filtered.count { it.isPinned }
                        LazyColumn(Modifier.fillMaxSize()) {
                            itemsIndexed(filtered, key = { _, n -> n.id }) { index, note ->
                                if (pinnedCount > 0 && index == 0) {
                                    ListSectionTag("ᛘ PINNED", GoldBright)
                                }
                                if (pinnedCount in 1 until filtered.size && index == pinnedCount) {
                                    ListSectionTag("OTHERS", TextMuted)
                                }
                                NoteRow(note, note.id == selectedId, showFolder = folderFilter == null) {
                                    selectedId = note.id
                                }
                            }
                            item { Spacer(Modifier.height(20.dp)) }
                        }
                    }
                }

                Box(Modifier.weight(1f).fillMaxHeight().padding(32.dp)) {
                    if (selected == null) EmptyState("ᚱ", "NOTHING SELECTED", "Choose a note")
                    else NoteReader(state, selected) { state.panels.open(PanelRequest.Note(selected)) }
                }
            }
        }

        Box(Modifier.align(Alignment.BottomEnd).padding(28.dp)) {
            AddButton(onClick = { state.panels.open(PanelRequest.Note(null)) })
        }
    }
}

@Composable
private fun ListSectionTag(label: String, tint: Color) {
    Text(
        label,
        color = tint.copy(0.75f),
        fontSize = 8.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 2.5.sp,
        modifier = Modifier.padding(start = 4.dp, top = 6.dp, bottom = 4.dp)
    )
}

@Composable
private fun FilterChipPill(label: String, selected: Boolean, color: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .padding(end = 6.dp)
            .clip(CircleShape)
            .background(if (selected) color.copy(0.22f) else SurfaceStone)
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(
            label,
            color = if (selected) color else TextMuted,
            fontSize = 11.sp,
            maxLines = 1,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
private fun NoteRow(note: ProphecyEntity, selected: Boolean, showFolder: Boolean, onClick: () -> Unit) {
    val sigil = sigilOf(note.sigil)
    val noteColor = parseNoteColor(note.colorHex)
    val accent = noteColor ?: sigil.color
    Column(
        Modifier.fillMaxWidth().padding(vertical = 3.dp)
            .clip(RoundedCornerShape(12.dp))
            .rowSurface(selected)
            .background((noteColor ?: Color.Transparent).copy(if (noteColor == null) 0f else 0.08f))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 11.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(sigil.glyph, color = sigil.color, fontSize = 13.sp, modifier = Modifier.width(20.dp))
            Text(
                note.title.ifBlank { "Untitled" },
                color = if (selected) GoldIce else TextParchment,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (note.isLocked) {
                Text("🔒", fontSize = 10.sp, modifier = Modifier.padding(start = 4.dp))
            }
            if (note.isPinned) {
                Text("ᛘ", color = GoldBright, fontSize = 11.sp, modifier = Modifier.padding(start = 4.dp))
            }
        }
        Spacer(Modifier.height(4.dp))
        if (note.isLocked) {
            // A locked note's row never leaks its body.
            Text("Sealed note", color = TextMuted, fontSize = 11.sp)
        } else {
            Text(
                note.content.replace('\n', ' ').take(90),
                color = TextMuted,
                fontSize = 11.sp,
                maxLines = 2,
                lineHeight = 15.sp
            )
        }
        val items = if (note.isChecklist()) note.checklistItems() else emptyList()
        if (items.isNotEmpty() && !note.isLocked) {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { NoteProgressTrack(note.checklistProgress(), accent) }
                Spacer(Modifier.width(8.dp))
                Text(
                    "${items.count { it.done }}/${items.size}",
                    color = accent.copy(0.8f), fontSize = 9.sp, fontWeight = FontWeight.Bold
                )
            }
        }
        val folder = note.folderName()
        if (showFolder && folder != null) {
            Spacer(Modifier.height(5.dp))
            Text("▸ $folder", color = accent.copy(0.65f), fontSize = 9.sp, maxLines = 1)
        }
    }
}

@Composable
private fun NoteReader(state: AppState, note: ProphecyEntity, onEdit: () -> Unit) {
    val sigil = sigilOf(note.sigil)
    val accent = parseNoteColor(note.colorHex) ?: sigil.color
    val checklist = note.isChecklist()
    val items = if (checklist) note.checklistItems() else emptyList()
    val words = remember(note.content) {
        note.content.split(Regex("\\s+")).count { it.isNotBlank() }
    }

    // The vault password already stood between the user and this note; the per-note
    // lock here is a curtain, not a second door — but it keeps a shared screen from
    // showing the body until it is deliberately drawn back. Collapses on reselection.
    var revealed by remember(note.id) { mutableStateOf(!note.isLocked) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(sigil.glyph, color = sigil.color, fontSize = 30.sp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    note.title.ifBlank { "Untitled" },
                    color = GoldIce,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Serif
                )
                Text(
                    listOfNotNull(
                        sigil.name,
                        note.folderName()?.let { "▸ $it" },
                        dateFormat.format(Date(note.timestamp)),
                        if (note.isLocked) "🔒 locked" else null
                    ).joinToString(" · "),
                    color = TextMuted,
                    fontSize = 10.sp,
                    letterSpacing = 1.5.sp
                )
            }
            TextButton(onClick = { state.toggleNotePinned(note) }) {
                Text(
                    if (note.isPinned) "ᛘ UNPIN" else "ᛘ PIN",
                    color = if (note.isPinned) GoldBright else TextMuted,
                    fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp
                )
            }
            TextButton(onClick = { state.toggleNoteArchived(note) }) {
                Text(
                    if (note.isArchived) "RESTORE" else "ARCHIVE",
                    color = PurpleMystic, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp
                )
            }
            TextButton(onClick = onEdit) {
                Text("EDIT", color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
            Spacer(Modifier.width(6.dp))
            CopyButton("${note.title}\n\n${note.content}", "note", accent)
        }

        if (note.isArchived) {
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(PurpleMystic.copy(0.10f))
                    .border(1.dp, PurpleMystic.copy(0.3f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text(
                    "This note is archived — it stays out of the main list until restored.",
                    color = PurpleMystic, fontSize = 11.sp
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        HorizontalDivider(color = accent.copy(0.2f))
        Spacer(Modifier.height(20.dp))

        if (!revealed) {
            Column(
                Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(vertical = 30.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("🔒", fontSize = 34.sp)
                Spacer(Modifier.height(10.dp))
                Text("SEALED INSCRIPTION", color = AmberWarn, fontSize = 12.sp, letterSpacing = 3.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(14.dp))
                Box(
                    Modifier.clip(RoundedCornerShape(10.dp))
                        .background(AmberWarn.copy(0.14f))
                        .border(1.dp, AmberWarn.copy(0.4f), RoundedCornerShape(10.dp))
                        .clickable { revealed = true }
                        .padding(horizontal = 18.dp, vertical = 9.dp)
                ) {
                    Text("REVEAL", color = AmberWarn, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                }
            }
        } else if (checklist) {
            if (items.isNotEmpty()) {
                Row(
                    Modifier.widthIn(max = 720.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val done = items.count { it.done }
                    Text(
                        "$done OF ${items.size} COMPLETE",
                        color = if (done == items.size) IncomeGreen else accent.copy(0.85f),
                        fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp
                    )
                    Spacer(Modifier.weight(1f))
                    if (done > 0) {
                        Text(
                            "UNCHECK ALL",
                            color = TextMuted, fontSize = 9.sp, letterSpacing = 1.sp,
                            modifier = Modifier
                                .clickable { state.setNoteChecklist(note, items.map { it.copy(done = false) }) }
                                .padding(horizontal = 6.dp)
                        )
                        Text(
                            "CLEAR DONE",
                            color = ExpenseRed.copy(0.8f), fontSize = 9.sp, letterSpacing = 1.sp,
                            modifier = Modifier
                                .clickable { state.setNoteChecklist(note, items.filter { !it.done }) }
                                .padding(horizontal = 6.dp)
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Box(Modifier.widthIn(max = 720.dp).fillMaxWidth()) {
                    NoteProgressTrack(note.checklistProgress(), accent, height = 4.dp)
                }
                Spacer(Modifier.height(14.dp))
            }
            Column(Modifier.widthIn(max = 720.dp)) {
                items.forEachIndexed { index, item ->
                    Row(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(9.dp))
                            .clickable {
                                state.setNoteChecklist(
                                    note,
                                    items.toMutableList().also { it[index] = item.copy(done = !item.done) }
                                )
                            }
                            .padding(horizontal = 6.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier.size(20.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (item.done) accent.copy(0.18f) else SurfaceStone)
                                .border(
                                    1.dp,
                                    if (item.done) accent.copy(0.55f) else GoldDark.copy(0.3f),
                                    RoundedCornerShape(6.dp)
                                ),
                            Alignment.Center
                        ) {
                            if (item.done) Text("✓", color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.width(11.dp))
                        Text(
                            item.text,
                            color = if (item.done) TextMuted else TextParchment,
                            fontSize = 14.sp,
                            lineHeight = 21.sp,
                            textDecoration = if (item.done) TextDecoration.LineThrough else null
                        )
                    }
                }
                if (items.isEmpty()) {
                    Text("This checklist is empty — edit it to add steps.", color = TextMuted, fontSize = 13.sp)
                }
            }
        } else {
            // Constrained measure — full-width prose on a wide monitor is unreadable.
            Text(
                note.content.ifBlank { "This note is empty." },
                color = TextParchment,
                fontSize = 14.sp,
                lineHeight = 23.sp,
                modifier = Modifier.widthIn(max = 720.dp)
            )
        }

        Spacer(Modifier.height(26.dp))
        if (revealed) {
            Text(
                if (checklist) "${items.size} steps · ${items.count { it.done }} done"
                else "$words words · ${note.content.length} characters",
                color = TextMuted,
                fontSize = 10.sp
            )
        }
        Spacer(Modifier.height(20.dp))
    }
}
