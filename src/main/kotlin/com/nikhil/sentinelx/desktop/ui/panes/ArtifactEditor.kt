package com.nikhil.sentinelx.desktop.ui.panes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nikhil.sentinelx.desktop.core.audit.ExpiryScan
import com.nikhil.sentinelx.desktop.core.format.ArtifactEntity
import com.nikhil.sentinelx.desktop.ui.AppState
import com.nikhil.sentinelx.desktop.ui.components.*
import com.nikhil.sentinelx.desktop.ui.theme.*

private val artifactTypes = listOf(
    "BANK", "AADHAR", "PAN", "PASSPORT", "DRIVING LICENCE", "VOTER ID", "HEALTH CARD"
)

/**
 * Add / edit a card or identity document.
 *
 * Field captions change with the document type, exactly as the phone's smart forms
 * do, so the same record looks the same on both. Images attach from disk rather than
 * a camera.
 */
@Composable
fun ArtifactEditor(state: AppState, existing: ArtifactEntity?, onClose: () -> Unit) {
    var type by remember { mutableStateOf(existing?.type ?: "BANK") }
    var l1 by remember { mutableStateOf(existing?.label1 ?: "") }
    var l2 by remember { mutableStateOf(existing?.label2 ?: "") }
    var l3 by remember { mutableStateOf(existing?.label3 ?: "") }
    var l4 by remember { mutableStateOf(existing?.label4 ?: "") }
    var l5 by remember { mutableStateOf(existing?.label5 ?: "") }
    var l6 by remember { mutableStateOf(existing?.label6 ?: "") }
    var secret by remember { mutableStateOf(existing?.secret ?: "") }
    var front by remember { mutableStateOf(existing?.frontImageUri) }
    var back by remember { mutableStateOf(existing?.backImageUri) }
    var confirmDelete by remember { mutableStateOf(false) }
    var typeMenu by remember { mutableStateOf(false) }

    val captions = remember(type) { labelCaptionsFor(type) }

    // The phone's artifacts table is UNIQUE on (label1, label2) with REPLACE, so a
    // colliding pair would silently destroy the other record on restore.
    val clash = remember(l1, l2, state.backup.artifacts) {
        l1.isNotBlank() && state.backup.artifacts.any {
            it.label1.equals(l1.trim(), true) && it.label2.equals(l2.trim(), true) && it.id != existing?.id
        }
    }

    EditorDialog(
        title = if (existing == null) "New Artifact" else "Edit Artifact",
        canSave = l1.isNotBlank() && !clash,
        onSave = {
            state.upsertArtifact(
                ArtifactEntity(
                    id = existing?.id ?: 0,
                    type = type,
                    label1 = l1.trim(), label2 = l2.trim(), label3 = l3.trim(),
                    label4 = l4.trim().ifBlank { null },
                    label5 = l5.trim().ifBlank { null },
                    label6 = l6.trim().ifBlank { null },
                    secret = secret,
                    frontImageUri = front,
                    backImageUri = back,
                    timestamp = existing?.timestamp ?: 0L
                )
            )
            onClose()
        },
        onCancel = onClose,
        onDelete = existing?.let { { confirmDelete = true } },
        width = 620
    ) {
        // Type selector
        Text("TYPE", color = GoldTarnished, fontSize = 8.sp, letterSpacing = 1.5.sp,
            modifier = Modifier.padding(start = 2.dp, bottom = 5.dp))
        Box(Modifier.padding(bottom = 14.dp)) {
            Row(
                Modifier.clip(RoundedCornerShape(10.dp)).background(SurfaceGem)
                    .border(1.dp, GoldDark.copy(0.25f), RoundedCornerShape(10.dp))
                    .clickable { typeMenu = true }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(type, color = TextParchment, fontSize = 13.sp)
                Spacer(Modifier.width(10.dp))
                Text("▾", color = TextMuted, fontSize = 11.sp)
            }
            DropdownMenu(typeMenu, onDismissRequest = { typeMenu = false }) {
                artifactTypes.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option, fontSize = 12.sp) },
                        onClick = { type = option; typeMenu = false }
                    )
                }
            }
        }

        // Fields, captioned per type. Blank captions mean the type has no such slot.
        val setters = listOf<(String) -> Unit>({ l1 = it }, { l2 = it }, { l3 = it }, { l4 = it }, { l5 = it }, { l6 = it })
        val values = listOf(l1, l2, l3, l4, l5, l6)
        captions.forEachIndexed { i, caption ->
            if (caption.isNotBlank()) {
                EditorField(values[i], setters[i], caption, placeholder = hintFor(caption))
            }
        }

        if (clash) {
            Text(
                "⚠ Another artifact already has this name and number. The phone keys " +
                    "cards on that pair, so restoring would overwrite one of them.",
                color = ExpenseRed, fontSize = 11.sp, lineHeight = 16.sp,
                modifier = Modifier.padding(bottom = 10.dp)
            )
        }

        EditorField(secret, { secret = it }, "Secret (CVV / PIN)", accent = ExpenseRed)

        // Images
        Text("SCANS", color = GoldTarnished, fontSize = 8.sp, letterSpacing = 1.5.sp,
            modifier = Modifier.padding(start = 2.dp, bottom = 6.dp))
        Row(Modifier.fillMaxWidth().height(120.dp)) {
            ImageSlot("FRONT", front, state, Modifier.weight(1f)) { front = it }
            Spacer(Modifier.width(12.dp))
            ImageSlot("BACK", back, state, Modifier.weight(1f)) { back = it }
        }
    }

    if (confirmDelete && existing != null) {
        ConfirmDelete(
            itemName = existing.label1.ifBlank { existing.type },
            onConfirm = { state.deleteArtifact(existing.id); confirmDelete = false; onClose() },
            onDismiss = { confirmDelete = false }
        )
    }
}

@Composable
internal fun ImageSlot(
    label: String,
    fileName: String?,
    state: AppState,
    modifier: Modifier = Modifier,
    onChange: (String?) -> Unit
) {
    Box(modifier.fillMaxHeight()) {
        Box(
            Modifier.fillMaxSize().clickable {
                ImagePicker.pick().firstOrNull()?.let { picked ->
                    onChange(state.addImage(picked.bytes, picked.extension))
                }
            }
        ) {
            if (fileName.isNullOrBlank()) {
                Box(
                    Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp))
                        .background(SurfaceStone)
                        .border(1.dp, GoldDark.copy(0.22f), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("+", color = GoldDark, fontSize = 22.sp)
                        Text(label, color = TextMuted, fontSize = 8.sp, letterSpacing = 1.5.sp)
                    }
                }
            } else {
                VaultImage(
                    fileName = fileName,
                    loader = { state.readImage(it) },
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
        }
        if (!fileName.isNullOrBlank()) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(6.dp).size(20.dp)
                    .clip(CircleShape).background(BackgroundVoid.copy(0.8f))
                    .clickable { onChange(null) },
                contentAlignment = Alignment.Center
            ) {
                Text("×", color = ExpenseRed, fontSize = 13.sp)
            }
        }
    }
}

/** Captions per type — must stay aligned with `labelsFor` in CardsPane. */
internal fun labelCaptionsFor(type: String): List<String> = when (type.uppercase()) {
    "BANK" -> listOf("Bank Name", "Card Number", "Holder Name", "Expiry", "", "")
    "AADHAR" -> listOf("Full Name", "Aadhar Number", "Date of Birth", "", "", "")
    "PAN" -> listOf("Full Name", "PAN Number", "Father's Name", "Date of Birth", "", "")
    "PASSPORT" -> listOf("Full Name", "Passport No.", "Nationality", "Date of Birth", "Expiry Date", "Place of Issue")
    "DRIVING LICENCE" -> listOf("Full Name", "License No.", "Validity", "Date of Birth", "Vehicle Class", "Blood Group")
    "VOTER ID" -> listOf("Full Name", "EPIC Number", "Gender", "Date of Birth", "Assembly", "Constituency")
    "HEALTH CARD" -> listOf("Full Name", "ABHA Number", "Date of Birth", "", "", "")
    else -> listOf("Field 1", "Field 2", "Field 3", "Field 4", "Field 5", "Field 6")
}

/**
 * Date fields get a format hint. The expiry dashboard parses these, and dd/MM/yyyy
 * is the form it reads most reliably — a nudge here beats a missed reminder later.
 */
private fun hintFor(caption: String): String = when {
    caption.contains("Date", true) || caption.contains("Expiry", true) ||
        caption.contains("Validity", true) -> "dd/MM/yyyy"
    else -> ""
}
