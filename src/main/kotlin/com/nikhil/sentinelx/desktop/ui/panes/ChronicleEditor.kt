package com.nikhil.sentinelx.desktop.ui.panes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nikhil.sentinelx.desktop.core.format.ChronicleEntity
import com.nikhil.sentinelx.desktop.core.format.pageFilenames
import com.nikhil.sentinelx.desktop.ui.AppState
import com.nikhil.sentinelx.desktop.ui.components.*
import com.nikhil.sentinelx.desktop.ui.theme.*

/**
 * Add / edit a multi-page scanned document.
 *
 * Pages can be added several at a time, which is the real advantage over the phone:
 * scanning a ten-page document there means ten separate camera captures, whereas
 * here it is one multi-select from a folder of scans.
 */
@Composable
fun ChronicleEditor(state: AppState, existing: ChronicleEntity?, onClose: () -> Unit) {
    var title by remember { mutableStateOf(existing?.title ?: "") }
    var year by remember { mutableStateOf(existing?.year ?: "") }
    var authority by remember { mutableStateOf(existing?.authority ?: "") }
    // Immutable list, replaced wholesale on change. Mutating a MutableList in place
    // would not trigger recomposition, so the thumbnails would silently go stale.
    var pages by remember { mutableStateOf<List<String>>(existing?.pageFilenames() ?: emptyList()) }
    var confirmDelete by remember { mutableStateOf(false) }

    // The phone's chronicles table has a UNIQUE index on title.
    val titleClash = remember(title, state.backup.chronicles) {
        title.isNotBlank() && state.backup.chronicles.any {
            it.title.equals(title.trim(), true) && it.id != existing?.id
        }
    }

    fun addPages() {
        val picked = ImagePicker.pick(multiple = true)
        if (picked.isEmpty()) return
        val added = picked.map { state.addImage(it.bytes, it.extension) }
        pages = pages + added
    }

    EditorDialog(
        title = if (existing == null) "New Chronicle" else "Edit Chronicle",
        canSave = title.isNotBlank() && !titleClash,
        onSave = {
            state.upsertChronicle(
                ChronicleEntity(
                    id = existing?.id ?: 0,
                    title = title.trim(),
                    year = year.trim(),
                    authority = authority.trim(),
                    // The phone joins pages with '|', so the same convention has to
                    // be written back or it reads the whole list as one filename.
                    pages = pages.joinToString("|"),
                    frontImageUri = pages.firstOrNull(),
                    timestamp = existing?.timestamp ?: 0L
                )
            )
            onClose()
        },
        onCancel = onClose,
        onDelete = existing?.let { { confirmDelete = true } },
        width = 640
    ) {
        EditorField(title, { title = it }, "Title", placeholder = "Graduation Degree")

        if (titleClash) {
            Text(
                "⚠ A chronicle with this title already exists. The phone keys documents " +
                    "by title, so restoring would overwrite the other one.",
                color = ExpenseRed, fontSize = 11.sp, lineHeight = 16.sp,
                modifier = Modifier.padding(bottom = 10.dp)
            )
        }

        Row {
            Box(Modifier.weight(1f).padding(end = 10.dp)) {
                EditorField(year, { year = it }, "Year", placeholder = "2023")
            }
            Box(Modifier.weight(1.6f)) {
                EditorField(authority, { authority = it }, "Authority", placeholder = "University")
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
            Text("PAGES", color = GoldTarnished, fontSize = 8.sp, letterSpacing = 1.5.sp)
            Spacer(Modifier.width(8.dp))
            Text("${pages.size}", color = TextMuted, fontSize = 9.sp)
            Spacer(Modifier.weight(1f))
            Text(
                "+ ADD PAGES",
                color = CyanGlow, fontSize = 10.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                letterSpacing = 1.sp,
                modifier = Modifier.clickable { addPages() }
            )
        }

        if (pages.isEmpty()) {
            Box(
                Modifier.fillMaxWidth().height(96.dp)
                    .clip(RoundedCornerShape(12.dp)).background(SurfaceStone)
                    .border(1.dp, GoldDark.copy(0.22f), RoundedCornerShape(12.dp))
                    .clickable { addPages() },
                contentAlignment = Alignment.Center
            ) {
                Text("Select one or more scans", color = TextMuted, fontSize = 11.sp)
            }
        } else {
            LazyRow(Modifier.fillMaxWidth().height(110.dp)) {
                itemsIndexed(pages) { i, name ->
                    Box(Modifier.padding(end = 10.dp).width(78.dp).fillMaxHeight()) {
                        VaultImage(
                            fileName = name,
                            loader = { state.readImage(it) },
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                        // Page number, so ordering is obvious before saving.
                        Box(
                            Modifier.align(Alignment.BottomStart).padding(4.dp)
                                .clip(CircleShape).background(BackgroundVoid.copy(0.85f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text("${i + 1}", color = GoldIce, fontSize = 9.sp)
                        }
                        Box(
                            Modifier.align(Alignment.TopEnd).padding(4.dp).size(18.dp)
                                .clip(CircleShape).background(BackgroundVoid.copy(0.85f))
                                .clickable {
                                    pages = pages.filterIndexed { idx, _ -> idx != i }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("×", color = ExpenseRed, fontSize = 12.sp)
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "The first page becomes the cover.",
                color = TextMuted, fontSize = 9.sp
            )
        }
    }

    if (confirmDelete && existing != null) {
        ConfirmDelete(
            itemName = existing.title.ifBlank { "Untitled" },
            onConfirm = { state.deleteChronicle(existing.id); confirmDelete = false; onClose() },
            onDismiss = { confirmDelete = false }
        )
    }
}
