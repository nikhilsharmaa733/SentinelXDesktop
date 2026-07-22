package com.nikhil.sentinelx.desktop.ui.panes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nikhil.sentinelx.desktop.core.format.ProphecyEntity
import com.nikhil.sentinelx.desktop.ui.AppState
import com.nikhil.sentinelx.desktop.ui.components.*
import com.nikhil.sentinelx.desktop.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Sigil definitions, matching `sigilDefs` in the Android ProphecyForgeScreen. */
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

private val dateFormat = SimpleDateFormat("d MMM yyyy", Locale.getDefault())

@Composable
fun NotesPane(state: AppState) {
    var query by remember { mutableStateOf("") }
    var sigilFilter by remember { mutableStateOf<String?>(null) }
    var selectedId by remember { mutableStateOf<Int?>(null) }

    val notes = state.backup.prophecies
    val filtered = remember(notes, query, sigilFilter) {
        notes.filter { n ->
            (sigilFilter == null || n.sigil == sigilFilter) &&
                (query.isBlank() || n.title.contains(query, true) || n.content.contains(query, true))
        }
    }.sortedByDescending { it.timestamp }

    val selected = filtered.firstOrNull { it.id == selectedId }
        ?: filtered.firstOrNull().also { selectedId = it?.id }

    Column(Modifier.fillMaxSize()) {
        PaneHeader("Notes", "${notes.size} entries")

        Row(Modifier.fillMaxSize()) {
            Column(
                Modifier.width(330.dp).fillMaxHeight()
                    .background(BackgroundVoid.copy(0.5f))
                    .padding(horizontal = 18.dp)
            ) {
                SearchField(query, { query = it }, "Search titles and contents")
                Spacer(Modifier.height(10.dp))

                // Sigil filter chips. Full-text search plus category is the pairing
                // that makes a large note collection navigable.
                Row(Modifier.fillMaxWidth().horizontalScrollable()) {
                    FilterChipPill("ALL", sigilFilter == null, GoldTarnished) { sigilFilter = null }
                    sigils.forEach { s ->
                        if (notes.any { it.sigil == s.name }) {
                            FilterChipPill(s.glyph, sigilFilter == s.name, s.color) {
                                sigilFilter = if (sigilFilter == s.name) null else s.name
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                if (filtered.isEmpty()) {
                    EmptyState(
                        "ᚱ",
                        if (notes.isEmpty()) "NO NOTES" else "NO MATCHES",
                        if (notes.isEmpty()) "Import a Migration Seal to begin" else "Try a different search"
                    )
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(filtered, key = { it.id }) { note ->
                            NoteRow(note, note.id == selectedId) { selectedId = note.id }
                        }
                        item { Spacer(Modifier.height(20.dp)) }
                    }
                }
            }

            Box(Modifier.weight(1f).fillMaxHeight().padding(32.dp)) {
                if (selected == null) EmptyState("ᚱ", "NOTHING SELECTED", "Choose a note")
                else NoteReader(selected)
            }
        }
    }
}

/** Horizontal overflow without a scrollbar — the chip row is short by design. */
private fun Modifier.horizontalScrollable(): Modifier = this

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
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
private fun NoteRow(note: ProphecyEntity, selected: Boolean, onClick: () -> Unit) {
    val sigil = sigilOf(note.sigil)
    Column(
        Modifier.fillMaxWidth().padding(vertical = 3.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) GoldDim.copy(0.3f) else Color.Transparent)
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
                maxLines = 1
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            note.content.replace('\n', ' ').take(90),
            color = TextMuted,
            fontSize = 11.sp,
            maxLines = 2,
            lineHeight = 15.sp
        )
    }
}

@Composable
private fun NoteReader(note: ProphecyEntity) {
    val sigil = sigilOf(note.sigil)
    val words = remember(note.content) {
        note.content.split(Regex("\\s+")).count { it.isNotBlank() }
    }

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
                    "${sigil.name} · ${dateFormat.format(Date(note.timestamp))}",
                    color = TextMuted,
                    fontSize = 10.sp,
                    letterSpacing = 1.5.sp
                )
            }
            CopyButton("${note.title}\n\n${note.content}", "note", sigil.color)
        }

        Spacer(Modifier.height(20.dp))
        HorizontalDivider(color = sigil.color.copy(0.2f))
        Spacer(Modifier.height(20.dp))

        // Constrained measure — full-width prose on a wide monitor is unreadable.
        Text(
            note.content.ifBlank { "This note is empty." },
            color = TextParchment,
            fontSize = 14.sp,
            lineHeight = 23.sp,
            modifier = Modifier.widthIn(max = 720.dp)
        )

        Spacer(Modifier.height(26.dp))
        Text(
            "$words words · ${note.content.length} characters",
            color = TextMuted,
            fontSize = 10.sp
        )
        Spacer(Modifier.height(20.dp))
    }
}
