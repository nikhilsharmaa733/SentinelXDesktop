package com.nikhil.sentinelx.desktop.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nikhil.sentinelx.desktop.core.format.SxvArchive
import com.nikhil.sentinelx.desktop.core.format.folderKey
import com.nikhil.sentinelx.desktop.core.audit.ExpiryScan
import com.nikhil.sentinelx.desktop.core.audit.PasswordAudit
import com.nikhil.sentinelx.desktop.ui.components.*
import com.nikhil.sentinelx.desktop.ui.panes.BankPane
import com.nikhil.sentinelx.desktop.ui.panes.BillsPane
import com.nikhil.sentinelx.desktop.ui.panes.CardsPane
import com.nikhil.sentinelx.desktop.ui.panes.CashBookPane
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
    var paletteOpen by remember { mutableStateOf(false) }
    val shellFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { shellFocus.requestWhenReady() }

    // Ctrl+K anywhere opens global search; Escape dismisses the topmost floating
    // editor. Handled at the shell so both work no matter which pane holds focus.
    Box(
        Modifier.fillMaxSize().background(BackgroundDeep)
            .focusRequester(shellFocus)
            .focusable()
            .onPreviewKeyEvent { e ->
                when {
                    e.type != KeyEventType.KeyDown -> false
                    e.isCtrlPressed && e.key == Key.K -> { paletteOpen = true; true }
                    e.key == Key.Escape -> state.panels.closeTop()
                    else -> false
                }
            }
    ) {
        Row(Modifier.fillMaxSize()) {
            Sidebar(state)
            Box(Modifier.weight(1f).fillMaxHeight()) {
                SentinelBackground {
                // Crossfade rather than an instant swap: switching sections is a
                // deliberate act and a hard cut reads as a flicker.
                Crossfade(
                    targetState = state.section,
                    animationSpec = tween(260),
                    label = "section"
                ) { current ->
                when (current) {
                    Section.OVERVIEW -> OverviewPane(state)
                    Section.LOGINS -> LoginsPane(state)
                    Section.CARDS -> CardsPane(state)
                    Section.NOTES -> NotesPane(state)
                    Section.CHRONICLES -> ChroniclesPane(state)
                    Section.LEDGER -> LedgerPane(state)
                    Section.CASHBOOK -> CashBookPane(state)
                    Section.BANK -> BankPane(state)
                    Section.BILLS -> BillsPane(state)
                }
                }
                }
            }
        }

        // Above the entire frame, sidebar included — a panel dragged over the
        // navigation should not disappear behind it. Outside the panels themselves
        // this layer is transparent to the pointer, so everything underneath keeps
        // working while an editor is open.
        PanelHost(state)
    }

    if (paletteOpen) {
        // Sealed folders' notes stay out of the index entirely; unsealing one
        // (state.unlockedFolders) folds them back in.
        val index = remember(state.backup, state.unlockedFolders) {
            val sealed = state.backup.noteFolders
                .filter { it.isLocked }
                .mapNotNull { folderKey(it.name) }
                .filterNot { it in state.unlockedFolders }
                .toSet()
            buildIndex(state.backup, sealed)
        }
        CommandPalette(
            index = index,
            onDismiss = { paletteOpen = false },
            onNavigate = { state.section = it }
        )
    }
}

@Composable
private fun Sidebar(state: AppState) {
    Column(
        Modifier
            .width(212.dp)
            .fillMaxHeight()
            .background(Brush.verticalGradient(listOf(BackgroundVoid, Color(0xFF0A0A10), BackgroundVoid)))
            .drawBehind {
                // Hairline on the trailing edge only. A full border boxes the
                // sidebar in and makes the window feel like two documents.
                drawRect(
                    brush = Brush.verticalGradient(
                        listOf(Color.Transparent, GoldDark.copy(0.35f), Color.Transparent)
                    ),
                    topLeft = Offset(size.width - 1f, 0f),
                    size = androidx.compose.ui.geometry.Size(1f, size.height)
                )
            }
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

        SidebarAction("Password Generator") { PasswordGeneratorDialog(onClose = it) }
        // scope = null: the whole vault. Each pane offers the same pair scoped to its
        // own records, via TransferActions in its header.
        SidebarAction("Import Migration Seal") { VaultImportDialog(state, onClose = it) }
        SidebarAction("Export Migration Seal") { VaultExportDialog(state, onClose = it) }
        SidebarAction("Version History") { HistoryDialog(state, onClose = it) }
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
    // Record count, like every other section. The count of entries still awaiting
    // verification is the more actionable number, but a bare digit in the sidebar
    // can't say which of the two it is — that one gets a labelled chip in the pane.
    Section.CASHBOOK -> state.backup.cashBook.size
    Section.BANK -> state.backup.bankTxns.size
    Section.BILLS -> state.backup.bills.size
}

@Composable
private fun SidebarItem(section: Section, selected: Boolean, count: Int?, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .rowSurface(selected)
            .clickable { onClick() }
            .padding(start = 9.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Accent bar marks the active section without relying on fill alone,
        // which is easy to miss at a glance across a tall sidebar.
        Box(
            Modifier.width(3.dp).height(if (selected) 18.dp else 0.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Brush.verticalGradient(listOf(GoldBright, GoldTarnished)))
        )
        Spacer(Modifier.width(if (selected) 7.dp else 10.dp))
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

/**
 * Sidebar entry that opens a dialog.
 *
 * The lambda receives a close trigger which the dialog MUST wire to its dismissal.
 * Callers that ignore it produce a dialog nothing can close — which happened to both
 * Import and Export, because their `onClose` carried a default `= {}` that let the
 * mistake compile. Never give a dismissal callback a default.
 */
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

// ── Panes ─────────────────────────────────────────────────────────────────────

@Composable
private fun OverviewPane(state: AppState) {
    val b = state.backup
    val expiries = remember(b.artifacts) { ExpiryScan.scan(b.artifacts) }
    val health = remember(b.logins) { PasswordAudit.score(b.logins) }
    val findings = remember(b.logins) { PasswordAudit.run(b.logins) }

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
            Triple("Ledger rows", b.ledger.size, AmberWarn),
            Triple("Cash entries", b.cashBook.size, GoldIce),
            Triple("Bank txns", b.bankTxns.size, CyanGlow)
        )
        tiles.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
                row.forEach { (label, count, accent) ->
                    StatTile(label, count, accent, Modifier.weight(1f).padding(end = 14.dp))
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }

        // ── Expiry dashboard ─────────────────────────────────────────────────
        // Reads the free-text date fields already being filled in on the phone.
        // Nothing surfaces this there, so a passport quietly lapses.
        if (expiries.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            GemCard(accent = AmberWarn, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "EXPIRING SOON",
                    color = AmberWarn, fontSize = 10.sp,
                    letterSpacing = 2.sp, fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(12.dp))
                expiries.take(6).forEach { item ->
                    val tone = when {
                        item.expired -> ExpenseRed
                        item.urgent -> AmberWarn
                        else -> TextSubtle
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            item.artifact.label1.ifBlank { item.artifact.type },
                            color = TextParchment, fontSize = 13.sp,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            item.artifact.type,
                            color = TextMuted, fontSize = 10.sp,
                            modifier = Modifier.padding(end = 12.dp)
                        )
                        Pill(
                            when {
                                item.expired -> "EXPIRED ${-item.daysRemaining}d AGO"
                                item.daysRemaining == 0L -> "EXPIRES TODAY"
                                else -> "${item.daysRemaining}d LEFT"
                            },
                            tone
                        )
                    }
                }
            }
        }

        // ── Password health ──────────────────────────────────────────────────
        if (b.logins.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            val tone = when {
                health >= 85 -> IncomeGreen
                health >= 60 -> AmberWarn
                else -> ExpenseRed
            }
            GemCard(accent = tone, modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "PASSWORD HEALTH",
                        color = tone, fontSize = 10.sp,
                        letterSpacing = 2.sp, fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.weight(1f))
                    Text("$health%", color = tone, fontSize = 22.sp, fontWeight = FontWeight.Black)
                }
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier.fillMaxWidth().height(6.dp)
                        .clip(RoundedCornerShape(3.dp)).background(SurfaceElevated)
                ) {
                    Box(
                        Modifier.fillMaxWidth(health / 100f).fillMaxHeight()
                            .clip(RoundedCornerShape(3.dp)).background(tone)
                    )
                }
                if (findings.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "${findings.size} of ${b.logins.size} logins need attention — " +
                            "${findings.count { f -> f.sharedWith.isNotEmpty() }} reuse a password.",
                        color = TextSubtle, fontSize = 12.sp
                    )
                }
            }
        }

        // ── Browser bridge ───────────────────────────────────────────────────
        Spacer(Modifier.height(14.dp))
        BrowserBridgeCard(state)

        if (b.logins.isEmpty() && b.artifacts.isEmpty() && b.prophecies.isEmpty()) {
            Spacer(Modifier.height(20.dp))
            Text(
                "This vault is empty. Use \u201CImport Migration Seal\u201D in the sidebar to bring in " +
                    "an archive exported from the Android app.",
                color = TextSubtle,
                fontSize = 13.sp,
                lineHeight = 20.sp
            )
        }

        Spacer(Modifier.height(24.dp))
        Text("Press Ctrl+K to search everything", color = TextMuted, fontSize = 10.sp)
        Spacer(Modifier.height(20.dp))
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

/**
 * Overview's control panel for the browser bridge — the desktop equivalent of
 * the phone's "ENABLE AUTOFILL" tile. Turning it on starts the local socket;
 * "Install browser link" lays down the native-messaging host + per-browser
 * manifests and reveals where to load the unpacked extension. Everything here
 * is local — the card says so, because a browser extension that fills passwords
 * is exactly the kind of thing a user should be able to reason about.
 */
@Composable
private fun BrowserBridgeCard(state: AppState) {
    val bridge = state.bridge
    var installReport by remember { mutableStateOf<com.nikhil.sentinelx.desktop.core.bridge.BridgeInstaller.Report?>(null) }
    var installedAlready by remember { mutableStateOf(com.nikhil.sentinelx.desktop.core.bridge.BridgeInstaller.isInstalled()) }

    val accent = if (bridge.running) IncomeGreen else CyanGlow
    GemCard(accent = accent, modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "BROWSER BRIDGE",
                color = accent, fontSize = 10.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.weight(1f))
            // The on/off switch — a pill that fills when running.
            Row(
                Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (bridge.enabled) IncomeGreen.copy(0.16f) else SurfaceElevated)
                    .border(1.dp, if (bridge.enabled) IncomeGreen.copy(0.5f) else GoldDark.copy(0.2f), RoundedCornerShape(20.dp))
                    .clickable { state.setBridgeEnabled(!bridge.enabled) }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(8.dp).clip(RoundedCornerShape(4.dp))
                        .background(if (bridge.running) IncomeGreen else TextMuted)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (bridge.enabled) (if (bridge.running) "ON" else "ON (locked)") else "OFF",
                    color = if (bridge.enabled) IncomeGreen else TextSubtle,
                    fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        Text(
            "Fill sealed logins into your browser and capture new ones back — over a " +
                "local socket, never a network port. While the vault is locked the browser " +
                "only learns “locked”; passwords move only after you approve each " +
                "fill here, with the vault open.",
            color = TextSubtle, fontSize = 12.sp, lineHeight = 17.sp
        )

        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            SmallActionButton(
                if (installedAlready) "Re-install browser link" else "Install browser link",
                CyanGlow
            ) {
                installReport = com.nikhil.sentinelx.desktop.core.bridge.BridgeInstaller.install()
                installedAlready = true
            }
            Spacer(Modifier.width(10.dp))
            // Optional hardening: master password at each fill, not just a click.
            Row(
                Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .clickable { bridge.requireMasterConfirm = !bridge.requireMasterConfirm }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(15.dp).clip(RoundedCornerShape(4.dp))
                        .background(if (bridge.requireMasterConfirm) CyanGlow else SurfaceElevated)
                        .border(1.dp, if (bridge.requireMasterConfirm) CyanGlow else GoldDark.copy(0.3f), RoundedCornerShape(4.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (bridge.requireMasterConfirm) Text("✓", color = BackgroundDeep, fontSize = 10.sp, fontWeight = FontWeight.Black)
                }
                Spacer(Modifier.width(7.dp))
                Text("Require master password per fill", color = TextSubtle, fontSize = 11.sp)
            }
        }

        installReport?.let { report ->
            Spacer(Modifier.height(14.dp))
            Column(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(SurfaceStone)
                    .border(1.dp, GoldDark.copy(0.2f), RoundedCornerShape(12.dp))
                    .padding(14.dp)
            ) {
                Text(
                    if (report.installed.isEmpty()) "No supported browser found."
                    else "Linked: ${report.installed.joinToString(", ")}",
                    color = if (report.installed.isEmpty()) AmberWarn else IncomeGreen,
                    fontSize = 12.sp, fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                Text("Now load the extension in your browser:", color = TextSubtle, fontSize = 11.sp)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Chrome / Brave / Edge: open the Extensions page, turn on Developer " +
                        "mode, choose \"Load unpacked\", and pick the folder below. Firefox: " +
                        "about:debugging → This Firefox → Load Temporary Add-on → pick manifest.json.",
                    color = TextMuted, fontSize = 11.sp, lineHeight = 16.sp
                )
                Spacer(Modifier.height(8.dp))
                SelectionContainer {
                    Text(report.extensionDir.absolutePath, color = CyanGlow, fontSize = 11.sp)
                }
                if (report.skipped.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text("Skipped: ${report.skipped.joinToString(", ")}", color = TextMuted, fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
private fun SmallActionButton(label: String, color: Color, onClick: () -> Unit) {
    Text(
        label,
        color = BackgroundDeep, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(color)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp)
    )
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
