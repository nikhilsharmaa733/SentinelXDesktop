package com.nikhil.sentinelx.desktop.ui.panes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nikhil.sentinelx.desktop.core.format.ChronicleEntity
import com.nikhil.sentinelx.desktop.core.format.pageFilenames
import com.nikhil.sentinelx.desktop.ui.AppState
import com.nikhil.sentinelx.desktop.ui.components.*
import com.nikhil.sentinelx.desktop.ui.theme.*

/**
 * Multi-page scanned documents.
 *
 * This is the pane that most justifies a desktop build: a degree certificate or a
 * multi-page contract is nearly unreadable on a phone, and genuinely comfortable on
 * a monitor. Pages advance with the arrow keys, so reading through a document never
 * requires touching the mouse.
 */
@Composable
fun ChroniclesPane(state: AppState) {
    var query by remember { mutableStateOf("") }
    var selectedId by remember { mutableStateOf<Int?>(null) }

    val chronicles = state.backup.chronicles
    val filtered = remember(chronicles, query) {
        if (query.isBlank()) chronicles
        else chronicles.filter {
            it.title.contains(query, true) ||
                it.authority.contains(query, true) ||
                it.year.contains(query, true)
        }
    }.sortedByDescending { it.timestamp }

    val selected = filtered.firstOrNull { it.id == selectedId }
        ?: filtered.firstOrNull().also { selectedId = it?.id }

    var editing by remember { mutableStateOf<ChronicleEntity?>(null) }
    var creating by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        PaneHeader("Chronicles", "${chronicles.size} documents")

        Row(Modifier.fillMaxSize()) {
            Column(
                Modifier.width(300.dp).fillMaxHeight()
                    .background(BackgroundVoid.copy(0.5f))
                    .padding(horizontal = 18.dp)
            ) {
                SearchField(query, { query = it }, "Search title, authority, year")
                Spacer(Modifier.height(12.dp))

                if (filtered.isEmpty()) {
                    EmptyState(
                        "ᛀ",
                        if (chronicles.isEmpty()) "NO DOCUMENTS" else "NO MATCHES",
                        if (chronicles.isEmpty()) "Import a Migration Seal to begin" else "Try a different search"
                    )
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(filtered, key = { it.id }) { doc ->
                            ChronicleRow(doc, doc.id == selectedId) { selectedId = doc.id }
                        }
                        item { Spacer(Modifier.height(20.dp)) }
                    }
                }
            }

            Box(Modifier.weight(1f).fillMaxHeight()) {
                if (selected == null) EmptyState("ᛀ", "NOTHING SELECTED", "Choose a document")
                else ChronicleReader(selected, state) { editing = selected }
            }
        }
    }

        Box(Modifier.align(Alignment.BottomEnd).padding(28.dp)) {
            AddButton(onClick = { creating = true })
        }
    }

    if (creating) ChronicleEditor(state, null) { creating = false }
    editing?.let { t -> ChronicleEditor(state, t) { editing = null } }
}

@Composable
private fun ChronicleRow(doc: ChronicleEntity, selected: Boolean, onClick: () -> Unit) {
    val pages = remember(doc.pages) { doc.pageFilenames().size }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp)
            .clip(RoundedCornerShape(12.dp))
            .rowSurface(selected)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("ᛀ", color = if (selected) GoldBright else GoldDark, fontSize = 15.sp, modifier = Modifier.width(22.dp))
        Column(Modifier.weight(1f)) {
            Text(
                doc.title.ifBlank { "Untitled" },
                color = if (selected) GoldIce else TextParchment,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1
            )
            Text(
                listOfNotNull(
                    doc.authority.takeIf { it.isNotBlank() },
                    doc.year.takeIf { it.isNotBlank() }
                ).joinToString(" · "),
                color = TextMuted, fontSize = 10.sp, maxLines = 1
            )
        }
        if (pages > 0) Text("$pages", color = TextMuted, fontSize = 11.sp)
    }
}

@Composable
private fun ChronicleReader(doc: ChronicleEntity, state: AppState, onEdit: () -> Unit) {
    // Front image first if it isn't already among the pages, so the cover leads.
    val pages = remember(doc.id, doc.pages, doc.frontImageUri) {
        val listed = doc.pageFilenames()
        val front = doc.frontImageUri?.takeIf { it.isNotBlank() }
        if (front != null && front !in listed) listOf(front) + listed else listed
    }

    var index by remember(doc.id) { mutableStateOf(0) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(doc.id) { index = 0; focus.requestFocus() }

    fun move(delta: Int) {
        if (pages.isNotEmpty()) index = (index + delta).coerceIn(0, pages.lastIndex)
    }

    Column(
        Modifier.fillMaxSize()
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { event ->
                // Arrow keys page through. Consumed on KeyDown only, or a single
                // press would advance twice (down and up both fire).
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionLeft, Key.PageUp -> { move(-1); true }
                    Key.DirectionRight, Key.PageDown, Key.Spacebar -> { move(1); true }
                    Key.Home -> { index = 0; true }
                    Key.MoveEnd -> { index = pages.lastIndex.coerceAtLeast(0); true }
                    else -> false
                }
            }
            .padding(horizontal = 28.dp, vertical = 22.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    doc.title.ifBlank { "Untitled" },
                    color = GoldIce,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Serif
                )
                Text(
                    listOfNotNull(
                        doc.authority.takeIf { it.isNotBlank() },
                        doc.year.takeIf { it.isNotBlank() },
                        "${pages.size} page${if (pages.size == 1) "" else "s"}"
                    ).joinToString(" · "),
                    color = TextMuted, fontSize = 10.sp, letterSpacing = 1.sp
                )
            }
            TextButton(onClick = onEdit) {
                Text("EDIT", color = CyanGlow, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
            if (pages.size > 1) {
                IconButton(onClick = { move(-1) }, enabled = index > 0) {
                    Icon(Icons.Default.ChevronLeft, "Previous page", tint = if (index > 0) GoldTarnished else TextMuted)
                }
                Text(
                    "${index + 1} / ${pages.size}",
                    color = TextSubtle, fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
                IconButton(onClick = { move(1) }, enabled = index < pages.lastIndex) {
                    Icon(
                        Icons.Default.ChevronRight, "Next page",
                        tint = if (index < pages.lastIndex) GoldTarnished else TextMuted
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        if (pages.isEmpty()) {
            EmptyState("ᛀ", "NO PAGES", "This document has no scanned pages")
        } else {
            VaultImage(
                fileName = pages.getOrNull(index),
                loader = { state.readImage(it) },
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentScale = ContentScale.Fit
            )

            if (pages.size > 1) {
                Spacer(Modifier.height(14.dp))
                LazyRow(Modifier.fillMaxWidth().height(74.dp)) {
                    items(pages.size) { i ->
                        Box(
                            Modifier.padding(end = 8.dp).width(56.dp).fillMaxHeight()
                                .clip(RoundedCornerShape(8.dp))
                                .border(
                                    width = if (i == index) 2.dp else 1.dp,
                                    color = if (i == index) GoldBright else GoldDark.copy(0.25f),
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .clickable { index = i }
                        ) {
                            VaultImage(
                                fileName = pages[i],
                                loader = { state.readImage(it) },
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "← → to page · Home / End to jump",
                    color = TextMuted, fontSize = 9.sp, letterSpacing = 1.sp
                )
            }
        }
    }
}
