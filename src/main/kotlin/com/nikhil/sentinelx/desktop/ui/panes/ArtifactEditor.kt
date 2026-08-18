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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nikhil.sentinelx.desktop.core.format.ArtifactEntity
import com.nikhil.sentinelx.desktop.ui.AppState
import com.nikhil.sentinelx.desktop.ui.components.*
import com.nikhil.sentinelx.desktop.ui.theme.*

/** Identity document types, in the order the phone's dropdown shows them. */
private val identityTypes = listOf(
    "AADHAR", "PAN", "PASSPORT", "DRIVING LICENCE", "VOTER ID", "HEALTH CARD", "RC"
)

private val bloodGroups = listOf("A+", "A-", "B+", "B-", "AB+", "AB-", "O+", "O-")
private val genders = listOf("MALE", "FEMALE", "OTHER")

/**
 * Values already in the vault, offered back as one-tap chips. The same person's
 * date of birth appears on every one of their documents, so after the first
 * card the rest should never need it typed again — the request that prompted
 * this. Names and banks recur for the same reason.
 */
private class FieldSuggestions(artifacts: List<ArtifactEntity>, excludeId: Int?) {
    val dobs = LinkedHashSet<String>()
    val names = LinkedHashSet<String>()
    val fathers = LinkedHashSet<String>()
    val banks = LinkedHashSet<String>()

    init {
        artifacts.filter { it.id != excludeId }.forEach { a ->
            val captions = labelCaptionsFor(a.type)
            val values = listOf(
                a.label1, a.label2, a.label3,
                a.label4.orEmpty(), a.label5.orEmpty(), a.label6.orEmpty()
            )
            captions.zip(values).forEach { (caption, value) ->
                val v = value.trim()
                if (v.isEmpty()) return@forEach
                when {
                    caption.contains("Date of Birth", true) -> dobs += v
                    caption.contains("Full Name", true) ||
                        caption.contains("Owner Name", true) ||
                        caption.contains("Holder Name", true) -> names += v
                    caption.contains("Father", true) -> fathers += v
                    caption.contains("Bank Name", true) -> banks += v
                }
            }
        }
    }

    fun forCaption(caption: String): List<String> = when {
        caption.contains("Date of Birth", true) -> dobs.toList()
        caption.contains("Full Name", true) ||
            caption.contains("Owner Name", true) ||
            caption.contains("Holder Name", true) -> names.toList()
        caption.contains("Father", true) -> fathers.toList()
        caption.contains("Bank Name", true) -> banks.toList()
        caption.contains("Blood Group", true) -> bloodGroups
        else -> emptyList()
    }
}

/**
 * Add / edit a card or identity document — rebuilt to mirror the phone's smart
 * form instead of six captioned slots in a flat list: a BANK / IDENTITY split,
 * type chips, per-type field pairs, chips for gender and blood group, format
 * hints on dates, and suggestion chips fed from the rest of the vault.
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

    val isBank = type == "BANK"
    val captions = remember(type) { labelCaptionsFor(type) }
    val suggestions = remember(state.backup.artifacts, existing?.id) {
        FieldSuggestions(state.backup.artifacts, existing?.id)
    }

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
        // ── BANK | IDENTITY ──────────────────────────────────────────────────
        Row(
            Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(SurfaceGem)
                .border(1.dp, GoldDark.copy(0.2f), RoundedCornerShape(12.dp))
                .padding(4.dp)
        ) {
            listOf("BANK", "IDENTITY").forEach { mode ->
                val active = (mode == "BANK") == isBank
                Box(
                    Modifier
                        .clip(RoundedCornerShape(9.dp))
                        .then(
                            if (active) Modifier.background(Brush.linearGradient(listOf(GoldBright, GoldTarnished)))
                            else Modifier
                        )
                        .clickable {
                            if (mode == "BANK" && !isBank) type = "BANK"
                            if (mode == "IDENTITY" && isBank) type = "AADHAR"
                        }
                        .padding(horizontal = 22.dp, vertical = 8.dp)
                ) {
                    Text(
                        mode,
                        color = if (active) BackgroundVoid else TextSubtle,
                        fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp
                    )
                }
            }
        }
        Spacer(Modifier.height(14.dp))

        // ── Identity type chips ──────────────────────────────────────────────
        if (!isBank) {
            Text("DOCUMENT TYPE", color = GoldTarnished, fontSize = 8.sp, letterSpacing = 1.5.sp,
                modifier = Modifier.padding(start = 2.dp, bottom = 5.dp))
            ChipFlow(identityTypes, selected = type) { picked ->
                if (picked != type) {
                    type = picked
                    // Slots change meaning between types; only the person's name
                    // survives the switch (every identity type starts with one).
                    l2 = ""; l3 = ""; l4 = ""; l5 = ""; l6 = ""
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // ── Fields ───────────────────────────────────────────────────────────
        val values = listOf(l1, l2, l3, l4, l5, l6)
        val setters = listOf<(String) -> Unit>({ l1 = it }, { l2 = it }, { l3 = it }, { l4 = it }, { l5 = it }, { l6 = it })
        val slots = captions.withIndex().filter { it.value.isNotBlank() }

        // Two per row, the phone's pairing, so a wide panel isn't one long column.
        slots.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEach { (i, caption) ->
                    Box(Modifier.weight(1f)) {
                        SmartField(caption, values[i], setters[i], suggestions, isBank)
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
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

        // Secret: a CVV on a bank card, a free slot on documents.
        EditorField(
            secret,
            { typed -> secret = if (isBank) typed.filter { it.isDigit() }.take(4) else typed },
            if (isBank) "CVV" else "PIN / note (optional)",
            accent = ExpenseRed,
            placeholder = if (isBank) "***" else ""
        )

        // ── Scans ────────────────────────────────────────────────────────────
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

/**
 * One field, rendered by what its caption means: chips for gender, a numeric
 * filter for card numbers, format hints for dates, and suggestion chips
 * wherever the vault already knows likely values.
 */
@Composable
private fun SmartField(
    caption: String,
    value: String,
    onChange: (String) -> Unit,
    suggestions: FieldSuggestions,
    isBank: Boolean
) {
    if (caption.contains("Gender", true)) {
        Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            Text(caption.uppercase(), color = GoldTarnished, fontSize = 8.sp, letterSpacing = 1.5.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 2.dp, bottom = 5.dp))
            ChipFlow(genders, selected = value.uppercase()) { onChange(it) }
        }
        return
    }

    val isCardNumber = isBank && caption.contains("Card Number", true)
    val isDate = listOf("Date", "Expiry", "Valid").any { caption.contains(it, true) }
    val placeholder = when {
        isCardNumber -> "**** **** **** ****"
        isBank && caption.contains("Expiry", true) -> "MM/YY"
        caption.contains("Expiry", true) || caption.contains("Date", true) ||
            caption.contains("Valid", true) -> "dd/MM/yyyy"
        else -> ""
    }

    Column {
        EditorField(
            value,
            { typed -> onChange(if (isCardNumber) typed.filter { it.isDigit() || it == ' ' } else typed) },
            caption,
            placeholder = placeholder
        )
        val offered = suggestions.forCaption(caption)
            .filter { !it.equals(value.trim(), true) }
            .take(5)
        if (offered.isNotEmpty() && (value.isBlank() || isDate)) {
            // Pull the chips up under the field they belong to.
            Row(Modifier.padding(start = 2.dp, bottom = 10.dp).offset(y = (-6).dp)) {
                SuggestionChips(offered, onChange)
            }
        }
    }
}

@Composable
private fun SuggestionChips(options: List<String>, onPick: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { option ->
            Text(
                option,
                color = CyanSoft, fontSize = 10.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(CyanGlow.copy(0.08f))
                    .border(1.dp, CyanGlow.copy(0.25f), RoundedCornerShape(8.dp))
                    .clickable { onPick(option) }
                    .padding(horizontal = 9.dp, vertical = 4.dp),
                maxLines = 1
            )
        }
    }
}

/** A wrapping row of selectable pills. */
@Composable
private fun ChipFlow(options: List<String>, selected: String, onPick: (String) -> Unit) {
    // Manual wrap: chunk to rows that fit comfortably in the 620dp editor.
    options.chunked(4).forEach { row ->
        Row(Modifier.padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            row.forEach { option ->
                val active = option.equals(selected, true)
                Text(
                    option,
                    color = if (active) BackgroundVoid else TextSubtle,
                    fontSize = 10.sp,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                    letterSpacing = 0.5.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(9.dp))
                        .then(
                            if (active) Modifier.background(Brush.linearGradient(listOf(GoldBright, GoldTarnished)))
                            else Modifier.background(SurfaceGem)
                        )
                        .border(1.dp, if (active) GoldBright.copy(0.5f) else GoldDark.copy(0.25f), RoundedCornerShape(9.dp))
                        .clickable { onPick(option) }
                        .padding(horizontal = 11.dp, vertical = 6.dp)
                )
            }
        }
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

/** Captions per type — must stay aligned with `labelsFor` in CardsPane and the phone's idRunes. */
internal fun labelCaptionsFor(type: String): List<String> = when (type.uppercase()) {
    "BANK" -> listOf("Bank Name", "Card Number", "Holder Name", "Expiry", "", "")
    "AADHAR" -> listOf("Full Name", "Aadhar Number", "Date of Birth", "", "", "")
    "PAN" -> listOf("Full Name", "PAN Number", "Father's Name", "Date of Birth", "", "")
    "PASSPORT" -> listOf("Full Name", "Passport No.", "Nationality", "Date of Birth", "Expiry Date", "Place of Issue")
    "DRIVING LICENCE" -> listOf("Full Name", "License No.", "Validity", "Date of Birth", "Vehicle Class", "Blood Group")
    "VOTER ID" -> listOf("Full Name", "EPIC Number", "Gender", "Date of Birth", "Assembly", "Constituency")
    "HEALTH CARD" -> listOf("Full Name", "ABHA Number", "Date of Birth", "", "", "")
    "RC" -> listOf("Owner Name", "Registration No.", "Vehicle (Maker & Model)", "Registration Date", "Valid Upto", "Chassis No.")
    else -> listOf("Field 1", "Field 2", "Field 3", "Field 4", "Field 5", "Field 6")
}
