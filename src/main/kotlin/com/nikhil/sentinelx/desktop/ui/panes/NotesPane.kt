package com.nikhil.sentinelx.desktop.ui.panes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.nikhil.sentinelx.desktop.core.format.*
import com.nikhil.sentinelx.desktop.ui.AppState
import com.nikhil.sentinelx.desktop.ui.PanelRequest
import com.nikhil.sentinelx.desktop.ui.Section
import com.nikhil.sentinelx.desktop.ui.components.*
import com.nikhil.sentinelx.desktop.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

/** One folder as the pane sees it — a record, or implicit through its notes' strings. */
private data class FolderRowData(
    val record: FolderEntity?,
    val name: String,
    val key: String,
    val count: Int,
    val locked: Boolean,
    val sealed: Boolean,
    val glyph: String,
    val colorHex: String?
) {
    fun entity(): FolderEntity = record ?: FolderEntity(name = name)
}

@Composable
fun NotesPane(state: AppState) {
    var query by remember { mutableStateOf("") }
    var showArchive by remember { mutableStateOf(false) }
    var sortIndex by remember { mutableStateOf(0) }
    var openFolderKey by remember { mutableStateOf<String?>(null) }
    var selectMode by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf(setOf<Int>()) }
    var selectedId by remember { mutableStateOf<Int?>(null) }

    var passcodeAsk by remember { mutableStateOf<FolderRowData?>(null) }
    var curtainAsk by remember { mutableStateOf<FolderRowData?>(null) }
    var lockDialogFor by remember { mutableStateOf<FolderRowData?>(null) }
    var settingsFor by remember { mutableStateOf<FolderRowData?>(null) }
    var showNewFolder by remember { mutableStateOf(false) }
    var showMovePicker by remember { mutableStateOf(false) }
    var confirmBulkDelete by remember { mutableStateOf(false) }

    val notes = state.backup.prophecies
    val unlockedFolders = state.unlockedFolders

    val folders = remember(notes, state.backup.noteFolders, unlockedFolders) {
        val active = notes.filter { !it.isArchived }
        val countByKey = active.mapNotNull { folderKey(it.folder) }.groupingBy { it }.eachCount()
        val views = LinkedHashMap<String, FolderRowData>()
        state.backup.noteFolders.forEach { rec ->
            val key = folderKey(rec.name) ?: return@forEach
            views[key] = FolderRowData(
                rec, rec.name, key, countByKey[key] ?: 0,
                rec.isLocked, rec.isLocked && key !in unlockedFolders,
                rec.displayGlyph(), rec.colorHex
            )
        }
        active.forEach { note ->
            val name = note.folderName() ?: return@forEach
            val key = folderKey(name) ?: return@forEach
            if (key !in views) {
                views[key] = FolderRowData(null, name, key, countByKey[key] ?: 0, false, false, NoteFolders.DEFAULT_GLYPH, null)
            }
        }
        views.values.sortedBy { it.name.lowercase() }
    }
    val sealedKeys = remember(folders) { folders.filter { it.sealed }.map { it.key }.toSet() }
    val openFolder = folders.firstOrNull { it.key == openFolderKey }
    LaunchedEffect(folders.map { it.key }) {
        if (openFolderKey != null && openFolder == null) openFolderKey = null
    }
    LaunchedEffect(openFolderKey, showArchive) { selection = setOf() }

    fun sort(list: List<ProphecyEntity>): List<ProphecyEntity> {
        val base = when (sortModes[sortIndex]) {
            "TITLE" -> list.sortedBy { it.title.lowercase() }
            "SIGIL" -> list.sortedWith(compareBy({ it.sigil }, { -it.timestamp }))
            else -> list.sortedByDescending { it.timestamp }
        }
        // Pinned float to the top whatever the sort — that is what a pin is.
        return base.sortedByDescending { it.isPinned }
    }

    val searching = query.isNotBlank()
    val listed: List<ProphecyEntity> = remember(notes, sealedKeys, query, showArchive, openFolderKey, sortIndex) {
        val visible = notes.filter {
            it.isArchived == showArchive && folderKey(it.folder) !in sealedKeys
        }
        sort(
            when {
                openFolder != null -> notes.filter {
                    !it.isArchived && folderKey(it.folder) == openFolder.key && it.matchesQuery(query)
                }
                searching || showArchive -> visible.filter { it.matchesQuery(query) }
                // Home: pinned from anywhere unsealed, plus the unfiled notes.
                else -> visible.filter { it.isPinned || it.folderName() == null }
            }
        )
    }

    // Sealed folders whose hidden notes match the current search — surfaced as one
    // row each, so the seal hides the contents without lying about them.
    val sealedMatches = remember(folders, notes, query, showArchive, openFolderKey) {
        if (openFolderKey != null || (!searching && !showArchive)) emptyList()
        else folders.filter { it.sealed }.mapNotNull { view ->
            val hidden = notes.count {
                it.isArchived == showArchive && folderKey(it.folder) == view.key && it.matchesQuery(query)
            }
            if (hidden > 0) view to hidden else null
        }
    }

    val selected = listed.firstOrNull { it.id == selectedId }
        ?: listed.firstOrNull().also { selectedId = it?.id }

    fun unseal(view: FolderRowData, then: () -> Unit) {
        if (view.record?.hasPasscode() == true) passcodeAsk = view else curtainAsk = view
        // The dialogs call markFolderUnlocked and re-run this intent themselves.
        pendingUnseal = then
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            PaneHeader(
                "Notes",
                "${notes.count { !it.isArchived }} entries · ${folders.size} folders" +
                    (notes.count { it.isArchived }.takeIf { it > 0 }?.let { " · $it archived" } ?: "")
            ) { TransferActions(state, Section.NOTES) }

            Row(Modifier.fillMaxSize()) {
                Column(
                    Modifier.width(330.dp).fillMaxHeight()
                        .background(BackgroundVoid.copy(0.5f))
                        .padding(horizontal = 18.dp)
                ) {
                    SearchField(query, { query = it }, "Search notes, steps and folders")
                    Spacer(Modifier.height(8.dp))

                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        RailChip(
                            if (notes.any { it.isArchived }) "ARCHIVE" else "ARCHIVE",
                            showArchive, PurpleMystic
                        ) { showArchive = !showArchive; openFolderKey = null }
                        RailChip("SELECT", selectMode, CyanGlow) {
                            selectMode = !selectMode
                            if (!selectMode) selection = setOf()
                        }
                        Spacer(Modifier.weight(1f))
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

                    Spacer(Modifier.height(8.dp))

                    val showFolderSection = openFolder == null && !showArchive && !searching

                    if (openFolder != null) {
                        // ── Open folder banner ─────────────────────────────
                        val accent = parseNoteColor(openFolder.colorHex) ?: GoldTarnished
                        Row(
                            Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(accent.copy(0.10f))
                                .border(1.dp, accent.copy(0.35f), RoundedCornerShape(12.dp))
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "←", color = TextMuted, fontSize = 14.sp,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(7.dp))
                                    .clickable { openFolderKey = null }
                                    .padding(horizontal = 7.dp, vertical = 3.dp)
                            )
                            Spacer(Modifier.width(7.dp))
                            Text(openFolder.glyph, color = accent, fontSize = 15.sp)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                openFolder.name,
                                color = accent, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                if (openFolder.locked) "🔒" else "🔓", fontSize = 11.sp,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(7.dp))
                                    .clickable { lockDialogFor = openFolder }
                                    .padding(4.dp)
                            )
                            Text(
                                "⚙", color = TextMuted, fontSize = 12.sp,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(7.dp))
                                    .clickable { settingsFor = openFolder }
                                    .padding(4.dp)
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                    }

                    if (listed.isEmpty() && sealedMatches.isEmpty() && !(showFolderSection && folders.isNotEmpty())) {
                        EmptyState(
                            "ᚱ",
                            when {
                                openFolder != null -> "EMPTY FOLDER"
                                showArchive && notes.none { it.isArchived } -> "ARCHIVE EMPTY"
                                notes.isEmpty() -> "NO NOTES"
                                else -> "NO MATCHES"
                            },
                            when {
                                openFolder != null -> "Move notes here, or create one with +"
                                showArchive && notes.none { it.isArchived } -> "Archived notes rest here"
                                notes.isEmpty() -> "Create one, or import a Migration Seal"
                                else -> "Try a different search"
                            }
                        )
                    } else {
                        val pinnedCount = listed.count { it.isPinned }
                        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                            if (showFolderSection) {
                                item(key = "tag-folders") { ListSectionTag("FOLDERS", GoldTarnished) }
                                items(folders, key = { "folder:${it.key}" }) { view ->
                                    FolderRow(
                                        view = view,
                                        onOpen = {
                                            if (view.sealed) unseal(view) { openFolderKey = view.key }
                                            else openFolderKey = view.key
                                        },
                                        onSettings = {
                                            if (view.sealed) unseal(view) { settingsFor = view }
                                            else settingsFor = view
                                        }
                                    )
                                }
                                item(key = "folder-new") {
                                    Row(
                                        Modifier.fillMaxWidth().padding(vertical = 3.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .border(1.dp, GoldDark.copy(0.3f), RoundedCornerShape(12.dp))
                                            .clickable { showNewFolder = true }
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("+", color = GoldTarnished.copy(0.8f), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                                        Spacer(Modifier.width(10.dp))
                                        Text("NEW FOLDER", color = GoldTarnished.copy(0.8f), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                                    }
                                }
                            }

                            sealedMatches.forEach { (view, hidden) ->
                                item(key = "sealed:${view.key}") {
                                    Row(
                                        Modifier.fillMaxWidth().padding(vertical = 3.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(SurfaceGem)
                                            .border(1.dp, AmberWarn.copy(0.3f), RoundedCornerShape(12.dp))
                                            .clickable { unseal(view) {} }
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("🔒", fontSize = 11.sp)
                                        Spacer(Modifier.width(8.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(view.name, color = TextParchment, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                            Text(
                                                "$hidden sealed match${if (hidden == 1) "" else "es"} — click to unseal",
                                                color = TextMuted, fontSize = 10.sp
                                            )
                                        }
                                    }
                                }
                            }

                            itemsIndexed(listed, key = { _, n -> n.id }) { index, note ->
                                if (openFolder == null && !showArchive && !searching) {
                                    if (pinnedCount > 0 && index == 0) ListSectionTag("ᛘ PINNED", GoldBright)
                                    if (index == pinnedCount) {
                                        ListSectionTag(if (pinnedCount > 0) "LOOSE NOTES" else "NOTES", TextMuted)
                                    }
                                }
                                NoteRow(
                                    note = note,
                                    selected = note.id == selectedId,
                                    marked = if (selectMode) note.id in selection else null,
                                    showFolder = openFolder == null
                                ) {
                                    if (selectMode) {
                                        selection = if (note.id in selection) selection - note.id else selection + note.id
                                    } else {
                                        selectedId = note.id
                                    }
                                }
                            }
                            item { Spacer(Modifier.height(70.dp)) }
                        }

                        // ── Bulk bar ───────────────────────────────────────
                        if (selectMode) {
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 8.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(SurfaceGem)
                                    .border(1.dp, CyanGlow.copy(0.35f), RoundedCornerShape(12.dp))
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "${selection.size}",
                                    color = CyanGlow, fontSize = 12.sp, fontWeight = FontWeight.Black
                                )
                                Spacer(Modifier.width(8.dp))
                                BulkAction("MOVE", GoldTarnished, selection.isNotEmpty()) { showMovePicker = true }
                                BulkAction("PIN", GoldBright, selection.isNotEmpty()) {
                                    val allPinned = selection.all { id -> notes.firstOrNull { it.id == id }?.isPinned == true }
                                    state.pinNotes(selection, !allPinned); selection = setOf()
                                }
                                BulkAction(
                                    if (showArchive) "RESTORE" else "ARCHIVE", PurpleMystic, selection.isNotEmpty()
                                ) {
                                    state.archiveNotes(selection, !showArchive); selection = setOf()
                                }
                                BulkAction("DELETE", ExpenseRed, selection.isNotEmpty()) { confirmBulkDelete = true }
                            }
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
            AddButton(onClick = {
                // A note created inside an open folder starts filed in it.
                state.panels.open(PanelRequest.Note(null, prefillFolder = openFolder?.name))
            })
        }

        // ── Dialogs ────────────────────────────────────────────────────────
        passcodeAsk?.let { view ->
            FolderPasscodeDialog(
                state = state,
                folder = view,
                onUnsealed = {
                    state.markFolderUnlocked(view.name)
                    passcodeAsk = null
                    pendingUnseal?.invoke(); pendingUnseal = null
                },
                onDismiss = { passcodeAsk = null; pendingUnseal = null }
            )
        }

        curtainAsk?.let { view ->
            CurtainDialog(
                folder = view,
                onReveal = {
                    state.markFolderUnlocked(view.name)
                    curtainAsk = null
                    pendingUnseal?.invoke(); pendingUnseal = null
                },
                onDismiss = { curtainAsk = null; pendingUnseal = null }
            )
        }

        lockDialogFor?.let { view ->
            FolderLockDialog(
                folder = view,
                onApply = { locked, passcode ->
                    state.setFolderLock(view.entity(), locked, passcode)
                    if (locked) state.markFolderUnlocked(view.name)
                    lockDialogFor = null
                },
                onDismiss = { lockDialogFor = null }
            )
        }

        settingsFor?.let { view ->
            FolderSettingsDialog(
                folder = view,
                takenNames = folders.filter { it.key != view.key }.map { it.name },
                onApply = { newName, glyph, colorHex ->
                    val styled = view.entity().copy(glyph = glyph, colorHex = colorHex)
                    if (newName.trim() != view.name && newName.isNotBlank()) {
                        state.renameFolder(styled, newName)
                        if (openFolderKey == view.key) openFolderKey = folderKey(newName)
                    } else {
                        state.saveFolder(styled)
                    }
                    settingsFor = null
                },
                onDelete = {
                    state.deleteFolder(view.entity())
                    if (openFolderKey == view.key) openFolderKey = null
                    settingsFor = null
                },
                onDismiss = { settingsFor = null }
            )
        }

        if (showNewFolder) {
            NewFolderDialog(
                takenNames = folders.map { it.name },
                onCreate = { name, glyph, colorHex ->
                    state.saveFolder(FolderEntity(name = name, glyph = glyph, colorHex = colorHex))
                    showNewFolder = false
                },
                onDismiss = { showNewFolder = false }
            )
        }

        if (showMovePicker) {
            MoveNotesDialog(
                folders = folders,
                count = selection.size,
                onPick = { folderName ->
                    state.moveNotesToFolder(selection, folderName)
                    selection = setOf()
                    showMovePicker = false
                },
                onDismiss = { showMovePicker = false }
            )
        }

        if (confirmBulkDelete) {
            ConfirmDelete(
                itemName = "${selection.size} note${if (selection.size == 1) "" else "s"}",
                onConfirm = {
                    state.deleteNotes(selection)
                    selection = setOf()
                    confirmBulkDelete = false
                },
                onDismiss = { confirmBulkDelete = false }
            )
        }
    }
}

/**
 * What to do once a sealed folder is opened. Module state rather than remember:
 * the passcode dialog outlives the click that queued the intent.
 */
private var pendingUnseal: (() -> Unit)? = null

// ─────────────────────────────────────────────────────────────────────────────
//  Rail rows & chips
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun RailChip(label: String, selected: Boolean, color: Color, onClick: () -> Unit) {
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
            fontSize = 10.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            letterSpacing = 1.sp
        )
    }
}

@Composable
private fun BulkAction(label: String, color: Color, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.padding(start = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (enabled) color.copy(0.12f) else SurfaceStone.copy(0.5f))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 9.dp, vertical = 6.dp)
    ) {
        Text(
            label,
            color = if (enabled) color else TextMuted.copy(0.5f),
            fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp
        )
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
private fun FolderRow(view: FolderRowData, onOpen: () -> Unit, onSettings: () -> Unit) {
    val accent = parseNoteColor(view.colorHex) ?: GoldTarnished
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceGem)
            .background(accent.copy(0.06f))
            .border(1.dp, accent.copy(0.28f), RoundedCornerShape(12.dp))
            .clickable { onOpen() }
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(30.dp).clip(RoundedCornerShape(9.dp))
                .background(accent.copy(0.14f))
                .border(1.dp, accent.copy(0.4f), RoundedCornerShape(9.dp)),
            Alignment.Center
        ) {
            Text(view.glyph, color = accent, fontSize = 14.sp)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                view.name,
                color = TextParchment, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(
                if (view.sealed) "SEALED · ${view.count}" else "${view.count} note${if (view.count == 1) "" else "s"}",
                color = if (view.sealed) AmberWarn.copy(0.8f) else TextMuted,
                fontSize = 9.sp, letterSpacing = 1.sp
            )
        }
        if (view.locked) {
            Text("🔒", fontSize = 10.sp)
            Spacer(Modifier.width(6.dp))
        }
        Text(
            "⚙", color = TextMuted.copy(0.7f), fontSize = 12.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable { onSettings() }
                .padding(4.dp)
        )
    }
}

@Composable
private fun NoteRow(
    note: ProphecyEntity,
    selected: Boolean,
    /** Null = not in select mode; true/false = marked state. */
    marked: Boolean?,
    showFolder: Boolean,
    onClick: () -> Unit
) {
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
            if (marked != null) {
                Box(
                    Modifier.size(16.dp)
                        .clip(CircleShape)
                        .background(if (marked) CyanGlow else SurfaceStone)
                        .border(1.dp, if (marked) CyanGlow else TextMuted.copy(0.5f), CircleShape),
                    Alignment.Center
                ) {
                    if (marked) Text("✓", color = BackgroundVoid, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(8.dp))
            }
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

// ─────────────────────────────────────────────────────────────────────────────
//  Folder dialogs
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun DialogShell(
    title: String,
    tint: Color = GoldTarnished,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.width(400.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(BackgroundDeep)
                .border(1.dp, tint.copy(0.35f), RoundedCornerShape(18.dp))
                .padding(horizontal = 22.dp, vertical = 20.dp)
        ) {
            Text(title, color = tint, fontSize = 13.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
            Spacer(Modifier.height(14.dp))
            content()
        }
    }
}

@Composable
private fun ShellField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    accent: Color = GoldTarnished,
    masked: Boolean = false
) {
    Row(
        Modifier.fillMaxWidth().height(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(SurfaceGem)
            .border(1.dp, accent.copy(0.25f), RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(color = TextParchment, fontSize = 13.sp),
            cursorBrush = SolidColor(accent),
            visualTransformation = if (masked) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                if (value.isEmpty()) Text(placeholder, color = TextMuted, fontSize = 12.sp)
                inner()
            }
        )
    }
}

@Composable
private fun ShellActions(
    confirmLabel: String,
    confirmEnabled: Boolean = true,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onDismiss) { Text("CANCEL", color = TextMuted, fontSize = 11.sp) }
        Spacer(Modifier.width(6.dp))
        Box(
            Modifier.clip(RoundedCornerShape(9.dp))
                .background(
                    if (confirmEnabled) androidx.compose.ui.graphics.Brush.linearGradient(listOf(GoldBright, GoldTarnished))
                    else androidx.compose.ui.graphics.Brush.linearGradient(listOf(SurfaceGem, SurfaceStone))
                )
                .clickable(enabled = confirmEnabled) { onConfirm() }
                .padding(horizontal = 18.dp, vertical = 9.dp)
        ) {
            Text(
                confirmLabel,
                color = if (confirmEnabled) BackgroundVoid else TextMuted,
                fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp
            )
        }
    }
}

@Composable
private fun GlyphRow(selected: String, accent: Color, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth()) {
        NoteFolders.GLYPHS.forEach { glyph ->
            val on = glyph == selected
            Box(
                Modifier.padding(end = 8.dp).size(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (on) accent.copy(0.16f) else SurfaceStone)
                    .border(1.dp, if (on) accent.copy(0.5f) else GoldDark.copy(0.2f), RoundedCornerShape(10.dp))
                    .clickable { onSelect(glyph) },
                Alignment.Center
            ) {
                Text(glyph, color = if (on) accent else TextMuted, fontSize = 15.sp)
            }
        }
    }
}

@Composable
private fun ColorRow(selected: String?, onSelect: (String?) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        noteColorChoices.forEach { choice ->
            val swatch = parseNoteColor(choice)
            val on = selected == choice
            Box(
                Modifier.padding(end = 8.dp).size(26.dp)
                    .clip(CircleShape)
                    .background(swatch?.copy(0.85f) ?: SurfaceStone)
                    .border(if (on) 2.dp else 1.dp, if (on) GoldIce else GoldDark.copy(0.25f), CircleShape)
                    .clickable { onSelect(choice) },
                Alignment.Center
            ) {
                if (swatch == null) Text("⌀", color = TextMuted, fontSize = 11.sp)
                else if (on) Text("✓", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun NewFolderDialog(
    takenNames: List<String>,
    onCreate: (name: String, glyph: String, colorHex: String?) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var glyph by remember { mutableStateOf(NoteFolders.DEFAULT_GLYPH) }
    var colorHex by remember { mutableStateOf<String?>(null) }
    val clash = takenNames.any { it.trim().equals(name.trim(), true) } && name.isNotBlank()

    DialogShell("NEW FOLDER", onDismiss = onDismiss) {
        ShellField(name, { name = it }, "Folder name…")
        if (clash) {
            Spacer(Modifier.height(6.dp))
            Text("A folder with this name already exists.", color = ExpenseRed, fontSize = 11.sp)
        }
        Spacer(Modifier.height(12.dp))
        GlyphRow(glyph, parseNoteColor(colorHex) ?: GoldTarnished) { glyph = it }
        Spacer(Modifier.height(10.dp))
        ColorRow(colorHex) { colorHex = it }
        Spacer(Modifier.height(16.dp))
        ShellActions("CREATE", name.isNotBlank() && !clash, { onCreate(name.trim(), glyph, colorHex) }, onDismiss)
    }
}

@Composable
private fun FolderSettingsDialog(
    folder: FolderRowData,
    takenNames: List<String>,
    onApply: (newName: String, glyph: String, colorHex: String?) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(folder.name) }
    var glyph by remember { mutableStateOf(folder.glyph) }
    var colorHex by remember { mutableStateOf(folder.colorHex) }
    var confirmDelete by remember { mutableStateOf(false) }
    val clash = takenNames.any { it.trim().equals(name.trim(), true) } && name.isNotBlank()

    DialogShell("FOLDER SETTINGS", onDismiss = onDismiss) {
        ShellField(name, { name = it }, "Name")
        if (clash) {
            Spacer(Modifier.height(6.dp))
            Text("Another folder already bears this name.", color = ExpenseRed, fontSize = 11.sp)
        }
        Spacer(Modifier.height(12.dp))
        GlyphRow(glyph, parseNoteColor(colorHex) ?: GoldTarnished) { glyph = it }
        Spacer(Modifier.height(10.dp))
        ColorRow(colorHex) { colorHex = it }
        Spacer(Modifier.height(14.dp))
        Text(
            "DELETE FOLDER — its notes go loose, nothing is deleted",
            color = ExpenseRed.copy(0.8f), fontSize = 10.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { confirmDelete = true }
                .padding(vertical = 6.dp)
        )
        Spacer(Modifier.height(10.dp))
        ShellActions("APPLY", name.isNotBlank() && !clash, { onApply(name.trim(), glyph, colorHex) }, onDismiss)
    }

    if (confirmDelete) {
        ConfirmDelete(
            itemName = "the folder \"${folder.name}\" (notes survive)",
            onConfirm = { confirmDelete = false; onDelete() },
            onDismiss = { confirmDelete = false }
        )
    }
}

@Composable
private fun FolderLockDialog(
    folder: FolderRowData,
    onApply: (locked: Boolean, passcode: String?) -> Unit,
    onDismiss: () -> Unit
) {
    var locked by remember { mutableStateOf(folder.locked) }
    var passcode by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val hasExistingPasscode = folder.record?.hasPasscode() == true

    val passcodeError = when {
        !locked || passcode.isEmpty() -> null
        passcode.length < NoteFolders.MIN_PASSCODE -> "At least ${NoteFolders.MIN_PASSCODE} characters."
        passcode != confirm -> "The two entries differ."
        else -> null
    }

    DialogShell("SEAL THIS FOLDER", tint = AmberWarn, onDismiss = onDismiss) {
        Row(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(if (locked) AmberWarn.copy(0.12f) else SurfaceGem)
                .border(1.dp, if (locked) AmberWarn.copy(0.45f) else GoldDark.copy(0.2f), RoundedCornerShape(10.dp))
                .clickable { locked = !locked }
                .padding(horizontal = 13.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(if (locked) "🔒" else "🔓", fontSize = 13.sp)
            Spacer(Modifier.width(10.dp))
            Text(
                if (locked) "SEALED" else "UNSEALED",
                color = if (locked) AmberWarn else TextMuted,
                fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp
            )
        }
        if (locked) {
            Spacer(Modifier.height(12.dp))
            Text(
                if (hasExistingPasscode) "PASSCODE — leave blank to keep the current one"
                else "PASSCODE — optional but recommended here",
                color = AmberWarn.copy(0.8f), fontSize = 9.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            ShellField(passcode, { passcode = it }, "Passcode", AmberWarn, masked = true)
            Spacer(Modifier.height(8.dp))
            ShellField(confirm, { confirm = it }, "Confirm passcode", AmberWarn, masked = true)
            if (passcodeError != null) {
                Spacer(Modifier.height(6.dp))
                Text(passcodeError, color = ExpenseRed, fontSize = 11.sp)
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "A passcode seals the folder on every device. Without one, this desktop can " +
                    "only draw a curtain — it has no fingerprint reader — while the phone still " +
                    "demands biometrics. Forgotten passcodes are recovered with the vault master " +
                    "password here, or a fingerprint on the phone. The seal guards the screen; " +
                    "the vault itself is already encrypted.",
                color = TextMuted, fontSize = 10.sp, lineHeight = 15.sp
            )
        }
        Spacer(Modifier.height(14.dp))
        ShellActions(
            if (locked) "SEAL" else "UNSEAL",
            passcodeError == null,
            // Blank passcode means "keep whatever is set".
            { onApply(locked, passcode.takeIf { it.isNotEmpty() }) },
            onDismiss
        )
    }
}

@Composable
private fun FolderPasscodeDialog(
    state: AppState,
    folder: FolderRowData,
    onUnsealed: () -> Unit,
    onDismiss: () -> Unit
) {
    var entry by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun attempt() {
        val record = folder.record ?: return
        if (record.verifyPasscode(entry)) {
            onUnsealed()
            return
        }
        // Recovery path: the vault master password proves ownership. Argon2id takes
        // about a second, so it runs off the UI thread behind a "checking" state.
        checking = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) { state.verifyMasterPassword(entry.toCharArray()) }
            checking = false
            if (ok) onUnsealed() else wrong = true
        }
    }

    DialogShell("SEALED FOLDER", tint = AmberWarn, onDismiss = onDismiss) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(folder.glyph, color = parseNoteColor(folder.colorHex) ?: AmberWarn, fontSize = 17.sp)
            Spacer(Modifier.width(9.dp))
            Text(folder.name, color = TextParchment, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(12.dp))
        ShellField(entry, { entry = it; wrong = false }, "Passcode — or the vault master password", AmberWarn, masked = true)
        if (wrong) {
            Spacer(Modifier.height(6.dp))
            Text("Neither the passcode nor the master password.", color = ExpenseRed, fontSize = 11.sp)
        }
        if (checking) {
            Spacer(Modifier.height(6.dp))
            Text("Checking…", color = TextMuted, fontSize = 11.sp)
        }
        Spacer(Modifier.height(14.dp))
        ShellActions("UNSEAL", entry.isNotEmpty() && !checking, { attempt() }, onDismiss)
    }
}

@Composable
private fun CurtainDialog(folder: FolderRowData, onReveal: () -> Unit, onDismiss: () -> Unit) {
    DialogShell("SEALED FOLDER", tint = AmberWarn, onDismiss = onDismiss) {
        Text(
            "\"${folder.name}\" is sealed without a passcode. On the phone that means a " +
                "fingerprint; this desktop has no biometrics, and your vault password already " +
                "proved ownership — so here the seal is a curtain. Add a passcode (🔒 in the " +
                "folder banner) to enforce it on this machine too.",
            color = TextSubtle, fontSize = 12.sp, lineHeight = 18.sp
        )
        Spacer(Modifier.height(16.dp))
        ShellActions("DRAW BACK THE CURTAIN", true, onReveal, onDismiss)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Move dialog
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun MoveNotesDialog(
    folders: List<FolderRowData>,
    count: Int,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    var newName by remember { mutableStateOf("") }

    DialogShell("MOVE $count NOTE${if (count == 1) "" else "S"}", onDismiss = onDismiss) {
        Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
            Row(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(9.dp))
                    .clickable { onPick(null) }
                    .padding(horizontal = 6.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("⌀", color = TextMuted, fontSize = 13.sp, modifier = Modifier.width(22.dp))
                Text("No folder — loose notes", color = TextSubtle, fontSize = 12.sp)
            }
            folders.forEach { view ->
                val accent = parseNoteColor(view.colorHex) ?: GoldTarnished
                Row(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(9.dp))
                        .clickable { onPick(view.name) }
                        .padding(horizontal = 6.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(view.glyph, color = accent, fontSize = 13.sp, modifier = Modifier.width(22.dp))
                    Text(view.name, color = TextParchment, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 1)
                    if (view.locked) {
                        Text("🔒", fontSize = 9.sp)
                        Spacer(Modifier.width(6.dp))
                    }
                    Text("${view.count}", color = TextMuted, fontSize = 10.sp)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = GoldDark.copy(0.15f))
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                ShellField(newName, { newName = it }, "…or a new folder")
            }
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.clip(RoundedCornerShape(9.dp))
                    .background(
                        if (newName.isNotBlank()) androidx.compose.ui.graphics.Brush.linearGradient(listOf(GoldBright, GoldTarnished))
                        else androidx.compose.ui.graphics.Brush.linearGradient(listOf(SurfaceGem, SurfaceStone))
                    )
                    .clickable(enabled = newName.isNotBlank()) { onPick(newName.trim()) }
                    .padding(horizontal = 13.dp, vertical = 10.dp)
            ) {
                Text(
                    "MOVE",
                    color = if (newName.isNotBlank()) BackgroundVoid else TextMuted,
                    fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Reader (unchanged behaviour: live checklist, curtain for locked notes)
// ─────────────────────────────────────────────────────────────────────────────

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
