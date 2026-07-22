package com.nikhil.sentinelx.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.nikhil.sentinelx.desktop.core.format.MasterBackup
import com.nikhil.sentinelx.desktop.ui.Section
import com.nikhil.sentinelx.desktop.ui.theme.*

/** One searchable thing from anywhere in the vault. */
data class PaletteEntry(
    val title: String,
    val subtitle: String,
    val section: Section,
    val glyph: String,
    val accent: Color,
    /** Copied straight to the clipboard on Enter, when there is an obvious secret. */
    val quickCopy: String? = null,
    val quickCopyLabel: String = "value"
)

/**
 * Builds the search index across every data type.
 *
 * The point is that one keystroke searches *everything* — a login, a card number, a
 * note, a document, a transaction — without first choosing a category. On a phone
 * you must navigate to the right screen before you can search it at all.
 */
fun buildIndex(backup: MasterBackup): List<PaletteEntry> = buildList {
    backup.logins.forEach {
        add(PaletteEntry(it.siteName, it.username, Section.LOGINS, "ᛗ", accentFor(it.siteName), it.password, "password"))
    }
    backup.artifacts.forEach {
        add(PaletteEntry(it.label1.ifBlank { it.type }, "${it.type} · ${it.label2}", Section.CARDS, "ᚠ", GoldTarnished, it.label2, "number"))
    }
    backup.prophecies.forEach {
        add(PaletteEntry(it.title.ifBlank { "Untitled" }, it.content.replace('\n', ' ').take(60), Section.NOTES, "ᚱ", PurpleMystic))
    }
    backup.chronicles.forEach {
        add(PaletteEntry(it.title.ifBlank { "Untitled" }, listOf(it.authority, it.year).filter { s -> s.isNotBlank() }.joinToString(" · "), Section.CHRONICLES, "ᛀ", GoldBright))
    }
    backup.accounts.forEach {
        add(PaletteEntry(it.name, "Account", Section.LEDGER, "ᚢ", IncomeGreen))
    }
    backup.ledger.forEach {
        add(PaletteEntry(it.title.ifBlank { "Untitled" }, "${it.category} · ${if (it.isIncoming) "+" else "−"}${it.amount}", Section.LEDGER, "ᚢ", if (it.isIncoming) IncomeGreen else ExpenseRed))
    }
}

/**
 * Ranks matches so the useful ones surface first: a title that starts with the query
 * beats one that merely contains it, which beats a subtitle match. Without this,
 * searching "gi" puts a random transaction above Github.
 */
private fun score(entry: PaletteEntry, query: String): Int {
    val q = query.lowercase()
    val title = entry.title.lowercase()
    val sub = entry.subtitle.lowercase()
    return when {
        title == q -> 0
        title.startsWith(q) -> 1
        title.contains(q) -> 2
        sub.startsWith(q) -> 3
        sub.contains(q) -> 4
        else -> Int.MAX_VALUE
    }
}

@Composable
fun CommandPalette(
    index: List<PaletteEntry>,
    onDismiss: () -> Unit,
    onNavigate: (Section) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var cursor by remember { mutableStateOf(0) }
    val focus = remember { FocusRequester() }
    val listState = rememberLazyListState()

    val results = remember(index, query) {
        if (query.isBlank()) index.take(12)
        else index.map { it to score(it, query) }
            .filter { it.second != Int.MAX_VALUE }
            .sortedBy { it.second }
            .map { it.first }
            .take(40)
    }

    LaunchedEffect(query) { cursor = 0 }
    LaunchedEffect(cursor) { if (results.isNotEmpty()) listState.animateScrollToItem(cursor) }
    LaunchedEffect(Unit) { focus.requestFocus() }

    fun choose(entry: PaletteEntry?) {
        entry ?: return
        entry.quickCopy?.takeIf { it.isNotBlank() }
            ?.let { SecureClipboard.copySensitive(entry.quickCopyLabel, it) }
        onNavigate(entry.section)
        onDismiss()
    }

    Popup(
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true)
    ) {
        Box(
            Modifier.fillMaxSize().background(BackgroundVoid.copy(0.72f)).clickable { onDismiss() },
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                Modifier
                    .padding(top = 110.dp)
                    .width(640.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(BackgroundDeep)
                    .border(1.dp, GoldDark.copy(0.4f), RoundedCornerShape(16.dp))
                    // Swallow clicks so tapping the panel doesn't dismiss it.
                    .clickable(enabled = false) {}
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus)
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            when (event.key) {
                                Key.DirectionDown -> {
                                    if (results.isNotEmpty()) cursor = (cursor + 1) % results.size; true
                                }
                                Key.DirectionUp -> {
                                    if (results.isNotEmpty()) cursor = (cursor - 1 + results.size) % results.size; true
                                }
                                Key.Enter, Key.NumPadEnter -> { choose(results.getOrNull(cursor)); true }
                                Key.Escape -> { onDismiss(); true }
                                else -> false
                            }
                        },
                    placeholder = { Text("Search everything…", color = TextMuted, fontSize = 15.sp) },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 15.sp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        focusedTextColor = TextParchment,
                        unfocusedTextColor = TextParchment,
                        cursorColor = CyanGlow,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent
                    )
                )

                HorizontalDivider(color = GoldDark.copy(0.2f))

                if (results.isEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(30.dp), contentAlignment = Alignment.Center) {
                        Text("No matches", color = TextMuted, fontSize = 13.sp)
                    }
                } else {
                    LazyColumn(state = listState, modifier = Modifier.heightIn(max = 380.dp)) {
                        itemsIndexed(results) { i, entry ->
                            PaletteRow(entry, i == cursor) { choose(entry) }
                        }
                    }
                }

                HorizontalDivider(color = GoldDark.copy(0.2f))
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
                    Text("↑↓ navigate · ⏎ open & copy · esc close", color = TextMuted, fontSize = 9.sp)
                    Spacer(Modifier.weight(1f))
                    Text("${results.size} results", color = TextMuted, fontSize = 9.sp)
                }
            }
        }
    }
}

@Composable
private fun PaletteRow(entry: PaletteEntry, active: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .background(if (active) GoldDim.copy(0.35f) else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(entry.glyph, color = entry.accent, fontSize = 14.sp, modifier = Modifier.width(26.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.title,
                color = if (active) GoldIce else TextParchment,
                fontSize = 13.sp,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1
            )
            Text(entry.subtitle, color = TextMuted, fontSize = 10.sp, maxLines = 1)
        }
        Text(entry.section.label.uppercase(), color = TextMuted, fontSize = 8.sp, letterSpacing = 1.sp)
    }
}
