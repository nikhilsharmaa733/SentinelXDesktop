package com.nikhil.sentinelx.desktop.ui.panes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nikhil.sentinelx.desktop.core.format.ArtifactEntity
import com.nikhil.sentinelx.desktop.ui.AppState
import com.nikhil.sentinelx.desktop.ui.components.*
import com.nikhil.sentinelx.desktop.ui.theme.*

/**
 * Cards and identity documents.
 *
 * The phone shows one card at a time as a holographic stack, which suits a small
 * screen. Here the scans are large enough to actually read, and both sides sit
 * side by side — the main practical reason to open this on a desktop at all.
 */
@Composable
fun CardsPane(state: AppState) {
    var query by remember { mutableStateOf("") }
    var selectedId by remember { mutableStateOf<Int?>(null) }

    val artifacts = state.backup.artifacts
    val filtered = remember(artifacts, query) {
        if (query.isBlank()) artifacts
        else artifacts.filter { a ->
            listOf(a.type, a.label1, a.label2, a.label3, a.label4.orEmpty(), a.label5.orEmpty())
                .any { it.contains(query, true) }
        }
    }.sortedWith(compareBy({ it.type }, { it.label1.lowercase() }))

    val selected = filtered.firstOrNull { it.id == selectedId }
        ?: filtered.firstOrNull().also { selectedId = it?.id }

    var editing by remember { mutableStateOf<ArtifactEntity?>(null) }
    var creating by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        PaneHeader("Cards", "${artifacts.size} artifacts")

        Row(Modifier.fillMaxSize()) {
            Column(
                Modifier.width(320.dp).fillMaxHeight()
                    .background(BackgroundVoid.copy(0.5f))
                    .padding(horizontal = 18.dp)
            ) {
                SearchField(query, { query = it }, "Search name, number or type")
                Spacer(Modifier.height(12.dp))

                if (filtered.isEmpty()) {
                    EmptyState(
                        "ᚠ",
                        if (artifacts.isEmpty()) "NO ARTIFACTS" else "NO MATCHES",
                        if (artifacts.isEmpty()) "Import a Migration Seal to begin" else "Try a different search"
                    )
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(filtered, key = { it.id }) { artifact ->
                            ArtifactRow(artifact, artifact.id == selectedId) { selectedId = artifact.id }
                        }
                        item { Spacer(Modifier.height(20.dp)) }
                    }
                }
            }

            Box(Modifier.weight(1f).fillMaxHeight().padding(28.dp)) {
                if (selected == null) EmptyState("ᚠ", "NOTHING SELECTED", "Choose an artifact")
                else ArtifactDetail(selected, state) { editing = selected }
            }
        }
    }

        Box(Modifier.align(Alignment.BottomEnd).padding(28.dp)) {
            AddButton(onClick = { creating = true })
        }
    }

    if (creating) ArtifactEditor(state, null) { creating = false }
    editing?.let { t -> ArtifactEditor(state, t) { editing = null } }
}

/** Field captions per document type, mirroring the phone's idRunes map. */
private fun labelsFor(type: String): List<String> = when (type.uppercase()) {
    "BANK" -> listOf("BANK NAME", "CARD NUMBER", "HOLDER NAME", "EXPIRY", "", "")
    "AADHAR" -> listOf("FULL NAME", "AADHAR NUMBER", "DATE OF BIRTH", "", "", "")
    "PAN" -> listOf("FULL NAME", "PAN NUMBER", "FATHER'S NAME", "DATE OF BIRTH", "", "")
    "PASSPORT" -> listOf("FULL NAME", "PASSPORT NO.", "NATIONALITY", "DATE OF BIRTH", "EXPIRY DATE", "PLACE OF ISSUE")
    "DRIVING LICENCE" -> listOf("FULL NAME", "LICENSE NO.", "VALIDITY", "DATE OF BIRTH", "VEHICLE CLASS", "BLOOD GROUP")
    "VOTER ID" -> listOf("FULL NAME", "EPIC NUMBER", "GENDER", "DATE OF BIRTH", "ASSEMBLY", "CONSTITUENCY")
    "HEALTH CARD" -> listOf("FULL NAME", "ABHA NUMBER", "DATE OF BIRTH", "", "", "")
    else -> listOf("FIELD 1", "FIELD 2", "FIELD 3", "FIELD 4", "FIELD 5", "FIELD 6")
}

private fun accentForType(type: String): Color = when (type.uppercase()) {
    "BANK" -> GoldTarnished
    "PASSPORT" -> CyanGlow
    "AADHAR" -> IncomeGreen
    "PAN" -> AmberWarn
    "DRIVING LICENCE" -> PurpleMystic
    "VOTER ID" -> CyanSoft
    else -> GoldBright
}

@Composable
private fun ArtifactRow(artifact: ArtifactEntity, selected: Boolean, onClick: () -> Unit) {
    val accent = accentForType(artifact.type)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp)
            .clip(RoundedCornerShape(12.dp))
            .rowSurface(selected)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.width(4.dp).height(34.dp)
                .clip(RoundedCornerShape(2.dp)).background(accent.copy(0.8f))
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                artifact.label1.ifBlank { "Untitled" },
                color = if (selected) GoldIce else TextParchment,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1
            )
            Text(artifact.type, color = TextMuted, fontSize = 10.sp, letterSpacing = 1.sp)
        }
    }
}

@Composable
private fun ArtifactDetail(artifact: ArtifactEntity, state: AppState, onEdit: () -> Unit) {
    var revealed by remember(artifact.id) { mutableStateOf(false) }
    val accent = accentForType(artifact.type)
    val captions = labelsFor(artifact.type)
    val values = listOf(
        artifact.label1, artifact.label2, artifact.label3,
        artifact.label4.orEmpty(), artifact.label5.orEmpty(), artifact.label6.orEmpty()
    )

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text(
            artifact.label1.ifBlank { "Untitled" },
            color = GoldIce,
            fontSize = 24.sp,
            fontWeight = FontWeight.Black,
            fontFamily = FontFamily.Serif
        )
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Pill(artifact.type.uppercase(), accent)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onEdit) {
                Text("EDIT", color = CyanGlow, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
        }

        Spacer(Modifier.height(24.dp))

        // Both scans side by side — the phone can only show one at a time.
        if (!artifact.frontImageUri.isNullOrBlank() || !artifact.backImageUri.isNullOrBlank()) {
            Row(Modifier.fillMaxWidth().height(210.dp)) {
                VaultImage(
                    fileName = artifact.frontImageUri,
                    loader = { state.readImage(it) },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    contentScale = ContentScale.Fit
                )
                Spacer(Modifier.width(14.dp))
                VaultImage(
                    fileName = artifact.backImageUri,
                    loader = { state.readImage(it) },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    contentScale = ContentScale.Fit
                )
            }
            Spacer(Modifier.height(20.dp))
        }

        // Two columns of fields — a wide window would otherwise waste half its width.
        val populated = captions.zip(values).filter { (caption, value) ->
            caption.isNotBlank() && value.isNotBlank()
        }
        populated.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                pair.forEach { (caption, value) ->
                    Box(Modifier.weight(1f).padding(end = 12.dp)) {
                        DetailField(caption, value, accent)
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }

        if (artifact.secret.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            GemCard(accent = ExpenseRed, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "SECRET · CVV / PIN",
                    color = ExpenseRed,
                    fontSize = 9.sp,
                    letterSpacing = 2.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (revealed) artifact.secret else "•".repeat(artifact.secret.length.coerceAtMost(12)),
                        color = TextParchment,
                        fontSize = 16.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { revealed = !revealed }) {
                        Icon(
                            if (revealed) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (revealed) "Hide" else "Reveal",
                            tint = TextSubtle,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                    CopyButton(artifact.secret, "secret", ExpenseRed)
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun DetailField(caption: String, value: String, accent: Color) {
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Brush.verticalGradient(listOf(SurfaceGem, SurfaceStone)))
            .border(1.dp, accent.copy(0.16f), RoundedCornerShape(12.dp))
            .padding(14.dp)
    ) {
        Text(caption, color = accent.copy(0.9f), fontSize = 8.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(value, color = TextParchment, fontSize = 14.sp, modifier = Modifier.weight(1f))
            CopyButton(value, caption.lowercase(), accent)
        }
    }
}
