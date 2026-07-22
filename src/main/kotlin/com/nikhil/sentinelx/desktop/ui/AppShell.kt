package com.nikhil.sentinelx.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nikhil.sentinelx.desktop.core.format.SxvArchive
import com.nikhil.sentinelx.desktop.ui.panes.CardsPane
import com.nikhil.sentinelx.desktop.ui.panes.ChroniclesPane
import com.nikhil.sentinelx.desktop.ui.panes.LedgerPane
import com.nikhil.sentinelx.desktop.ui.panes.NotesPane
import com.nikhil.sentinelx.desktop.ui.panes.LoginsPane
import com.nikhil.sentinelx.desktop.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/**
 * The unlocked application frame: persistent sidebar on the left, content on the
 * right. Deliberately not the phone's drill-down navigation — a desktop has room
 * to keep navigation visible at all times.
 */
@Composable
fun AppShell(state: AppState) {
    Row(Modifier.fillMaxSize().background(BackgroundDeep)) {
        Sidebar(state)
        Box(Modifier.weight(1f).fillMaxHeight()) {
            when (state.section) {
                Section.OVERVIEW -> OverviewPane(state)
                Section.LOGINS -> LoginsPane(state)
                Section.CARDS -> CardsPane(state)
                Section.NOTES -> NotesPane(state)
                Section.CHRONICLES -> ChroniclesPane(state)
                Section.LEDGER -> LedgerPane(state)
            }
        }
    }
}

@Composable
private fun Sidebar(state: AppState) {
    Column(
        Modifier
            .width(212.dp)
            .fillMaxHeight()
            .background(BackgroundVoid)
            .border(width = 1.dp, color = GoldDark.copy(0.12f), shape = RoundedCornerShape(0.dp))
            .padding(vertical = 20.dp)
    ) {
        Row(
            Modifier.padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("ᚠ", color = GoldTarnished, fontSize = 22.sp)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    "SENTINEL X",
                    color = GoldTarnished,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Serif,
                    letterSpacing = 2.sp
                )
                Text("DESKTOP", color = TextMuted, fontSize = 8.sp, letterSpacing = 3.sp)
            }
        }

        Spacer(Modifier.height(26.dp))

        Section.entries.forEach { entry ->
            SidebarItem(
                section = entry,
                selected = state.section == entry,
                count = entry.countIn(state),
                onClick = { state.section = entry }
            )
        }

        Spacer(Modifier.weight(1f))

        HorizontalDivider(color = GoldDark.copy(0.15f), modifier = Modifier.padding(horizontal = 16.dp))
        Spacer(Modifier.height(12.dp))

        SidebarAction("Import Migration Seal") { ImportDialogHost(state) }
        SidebarTextButton("Lock Vault") { state.lock() }
    }
}

/** Per-section record counts, so the sidebar doubles as an at-a-glance summary. */
private fun Section.countIn(state: AppState): Int? = when (this) {
    Section.OVERVIEW -> null
    Section.LOGINS -> state.backup.logins.size
    Section.CARDS -> state.backup.artifacts.size
    Section.NOTES -> state.backup.prophecies.size
    Section.CHRONICLES -> state.backup.chronicles.size
    Section.LEDGER -> state.backup.ledger.size
}

@Composable
private fun SidebarItem(section: Section, selected: Boolean, count: Int?, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) GoldDim.copy(0.35f) else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            section.glyph,
            color = if (selected) GoldBright else TextMuted,
            fontSize = 14.sp,
            modifier = Modifier.width(22.dp)
        )
        Text(
            section.label,
            color = if (selected) GoldIce else TextSubtle,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
        )
        Spacer(Modifier.weight(1f))
        if (count != null && count > 0) {
            Text("$count", color = TextMuted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun SidebarAction(label: String, content: @Composable (trigger: () -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    SidebarTextButton(label) { open = true }
    if (open) {
        content { open = false }
    }
}

@Composable
private fun SidebarTextButton(label: String, onClick: () -> Unit) {
    Text(
        label,
        color = TextSubtle,
        fontSize = 11.sp,
        letterSpacing = 1.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 24.dp, vertical = 9.dp)
    )
}

// ── Import ────────────────────────────────────────────────────────────────────

/**
 * Two-step import: pick the file and password, see what is inside, then confirm.
 *
 * Import replaces the vault wholesale, exactly like Migration Restore on the phone.
 * Showing the counts before committing is what stops a stale archive silently
 * replacing a fuller vault — the phone's version of this has no such check.
 */
@Composable
private fun ImportDialogHost(state: AppState, onClose: () -> Unit = {}) {
    var path by remember { mutableStateOf<File?>(null) }
    var password by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<SxvArchive.Payload?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onClose,
        containerColor = BackgroundDeep,
        shape = RoundedCornerShape(20.dp),
        title = {
            Text(
                if (preview == null) "IMPORT MIGRATION SEAL" else "CONFIRM IMPORT",
                color = GoldTarnished,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
                fontSize = 14.sp
            )
        },
        text = {
            Column(Modifier.width(420.dp)) {
                val found = preview
                if (found == null) {
                    Text(
                        path?.name ?: "No file chosen",
                        color = if (path == null) TextMuted else TextParchment,
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.height(10.dp))
                    TextButton(onClick = { path = chooseSxvFile() }) {
                        Text("Choose .sxv file…", color = CyanGlow, fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        singleLine = true,
                        label = { Text("Archive password", fontSize = 11.sp) },
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CyanGlow.copy(0.7f),
                            unfocusedBorderColor = GoldDark.copy(0.3f),
                            focusedTextColor = TextParchment,
                            unfocusedTextColor = TextParchment,
                            cursorColor = CyanGlow
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    val b = found.backup
                    Text(
                        "This will REPLACE everything currently in your vault.",
                        color = AmberWarn, fontSize = 12.sp
                    )
                    Spacer(Modifier.height(12.dp))
                    listOf(
                        "Logins" to b.logins.size,
                        "Cards" to b.artifacts.size,
                        "Notes" to b.prophecies.size,
                        "Chronicles" to b.chronicles.size,
                        "Ledger rows" to b.ledger.size,
                        "Accounts" to b.accounts.size,
                        "Images" to found.images.size
                    ).forEach { (name, n) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            Text(name, color = TextSubtle, fontSize = 12.sp)
                            Spacer(Modifier.weight(1f))
                            Text("$n", color = GoldIce, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    val missing = found.missingImages()
                    if (missing.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "⚠ ${missing.size} referenced image(s) are missing from this archive.",
                            color = AmberWarn, fontSize = 11.sp
                        )
                    }
                }

                message?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, color = ExpenseRed, fontSize = 11.sp)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = if (preview == null) path != null && password.isNotEmpty() else true,
                onClick = {
                    val found = preview
                    if (found == null) {
                        scope.launch {
                            message = null
                            val result = withContext(Dispatchers.Default) {
                                runCatching { state.previewArchive(path!!, password.toCharArray()) }
                            }
                            result.onSuccess { preview = it }
                                .onFailure { message = it.message ?: "Could not read archive." }
                        }
                    } else {
                        scope.launch {
                            withContext(Dispatchers.Default) { state.adoptArchive(found) }
                            password = ""
                            onClose()
                        }
                    }
                }
            ) {
                Text(
                    if (preview == null) "READ ARCHIVE" else "REPLACE VAULT",
                    color = if (preview == null) CyanGlow else ExpenseRed,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            TextButton(onClick = { password = ""; onClose() }) {
                Text("CANCEL", color = TextMuted)
            }
        }
    )
}

/**
 * AWT's native file dialog rather than Swing's JFileChooser — it uses the real
 * Windows/GTK picker, which looks like the rest of the OS instead of like Java.
 */
private fun chooseSxvFile(): File? {
    val dialog = FileDialog(null as Frame?, "Select Migration Seal", FileDialog.LOAD)
    dialog.setFilenameFilter { _, name -> name.endsWith(".sxv") }
    dialog.isVisible = true
    val dir = dialog.directory ?: return null
    val file = dialog.file ?: return null
    return File(dir, file)
}

// ── Panes ─────────────────────────────────────────────────────────────────────

@Composable
private fun OverviewPane(state: AppState) {
    val b = state.backup
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(36.dp)
    ) {
        Text(
            "OVERVIEW",
            color = GoldTarnished,
            fontSize = 26.sp,
            fontWeight = FontWeight.Black,
            fontFamily = FontFamily.Serif,
            letterSpacing = 3.sp
        )
        Text("VAULT CONTENTS", color = TextMuted, fontSize = 9.sp, letterSpacing = 3.sp)

        Spacer(Modifier.height(28.dp))

        val tiles = listOf(
            Triple("Logins", b.logins.size, CyanGlow),
            Triple("Cards", b.artifacts.size, GoldTarnished),
            Triple("Notes", b.prophecies.size, PurpleMystic),
            Triple("Chronicles", b.chronicles.size, GoldBright),
            Triple("Accounts", b.accounts.size, IncomeGreen),
            Triple("Ledger rows", b.ledger.size, AmberWarn)
        )

        tiles.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
                row.forEach { (label, count, accent) ->
                    StatTile(label, count, accent, Modifier.weight(1f).padding(end = 14.dp))
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }

        if (b.logins.isEmpty() && b.artifacts.isEmpty() && b.prophecies.isEmpty()) {
            Spacer(Modifier.height(20.dp))
            Text(
                "This vault is empty. Use “Import Migration Seal” in the sidebar to bring in " +
                    "an archive exported from the Android app.",
                color = TextSubtle,
                fontSize = 13.sp,
                lineHeight = 20.sp
            )
        }
    }
}

@Composable
private fun StatTile(label: String, count: Int, accent: Color, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.verticalGradient(listOf(SurfaceGem, SurfaceStone)))
            .border(1.dp, accent.copy(0.22f), RoundedCornerShape(16.dp))
            .padding(20.dp)
    ) {
        Text("$count", color = accent, fontSize = 30.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.height(2.dp))
        Text(label.uppercase(), color = TextMuted, fontSize = 9.sp, letterSpacing = 2.sp)
    }
}

@Composable
private fun PlaceholderPane(section: Section) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(section.glyph, color = GoldDark.copy(0.4f), fontSize = 54.sp)
            Spacer(Modifier.height(12.dp))
            Text(
                section.label.uppercase(),
                color = TextSubtle,
                fontSize = 16.sp,
                letterSpacing = 4.sp,
                fontFamily = FontFamily.Serif
            )
            Spacer(Modifier.height(6.dp))
            Text("Not built yet", color = TextMuted, fontSize = 11.sp)
        }
    }
}
