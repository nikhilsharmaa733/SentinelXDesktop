package com.nikhil.sentinelx.desktop.ui.panes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nikhil.sentinelx.desktop.core.format.CashBookExport
import com.nikhil.sentinelx.desktop.core.format.CashEntryEntity
import com.nikhil.sentinelx.desktop.core.format.slipFilenames
import com.nikhil.sentinelx.desktop.ui.AppState
import com.nikhil.sentinelx.desktop.ui.theme.*
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

private enum class ExportFormat(
    val label: String,
    val hint: String,
    val extension: String
) {
    STATEMENT("Printable statement", "HTML · opens in a browser, print to PDF from there", "html"),
    SPREADSHEET("Spreadsheet", "CSV · debit and credit columns any spreadsheet can total", "csv")
}

/**
 * Writes the balance sheet out, saying plainly that the file is not encrypted.
 *
 * Two formats, because the month-end statement and the spreadsheet are different jobs.
 * The statement can carry the slip photographs — which is what makes it evidence rather
 * than a summary — at the cost of a much larger file, so that is opt-in.
 */
@Composable
fun CashBookExportDialog(
    state: AppState,
    entries: List<CashEntryEntity>,
    title: String,
    onClose: () -> Unit
) {
    var format by remember { mutableStateOf(ExportFormat.STATEMENT) }
    var includeSlips by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf<File?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }

    val slipCount = remember(entries) { entries.sumOf { it.slipFilenames().size } }

    AlertDialog(
        onDismissRequest = onClose,
        containerColor = BackgroundDeep,
        shape = RoundedCornerShape(18.dp),
        title = {
            Text(
                if (done == null) "EXPORT BALANCE SHEET" else "EXPORTED",
                color = GoldTarnished, fontWeight = FontWeight.Bold, fontSize = 14.sp, letterSpacing = 2.sp
            )
        },
        text = {
            Column(Modifier.width(460.dp)) {
                if (done != null) {
                    Text("Saved to:", color = TextSubtle, fontSize = 12.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(done!!.absolutePath, color = GoldIce, fontSize = 11.sp)
                    if (format == ExportFormat.STATEMENT) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Open it in a browser and use Print → Save as PDF to get a PDF.",
                            color = TextMuted, fontSize = 11.sp, lineHeight = 16.sp
                        )
                    }
                } else {
                    Text(
                        "$title — ${entries.size} entr${if (entries.size == 1) "y" else "ies"}.",
                        color = TextSubtle, fontSize = 12.sp
                    )
                    Spacer(Modifier.height(14.dp))

                    ExportFormat.entries.forEach { option ->
                        FormatOption(option, format == option) { format = option }
                    }

                    if (format == ExportFormat.STATEMENT && slipCount > 0) {
                        Spacer(Modifier.height(6.dp))
                        Row(
                            Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(9.dp))
                                .clickable { includeSlips = !includeSlips }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                Modifier.size(16.dp).clip(RoundedCornerShape(4.dp))
                                    .background(if (includeSlips) CyanGlow else Color.Transparent)
                                    .border(
                                        1.dp,
                                        if (includeSlips) CyanGlow else TextMuted,
                                        RoundedCornerShape(4.dp)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                if (includeSlips) {
                                    Text("✓", color = BackgroundVoid, fontSize = 11.sp, fontWeight = FontWeight.Black)
                                }
                            }
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(
                                    "Embed the $slipCount slip photo${if (slipCount == 1) "" else "s"}",
                                    color = TextSubtle, fontSize = 12.sp
                                )
                                Text(
                                    "Makes the file self-contained, and much larger",
                                    color = TextMuted, fontSize = 10.sp
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Text(
                        "⚠ This file is NOT encrypted. Anyone who opens it can read every " +
                            "amount, name and note in the range" +
                            if (includeSlips) ", and see the slip photographs." else ".",
                        color = AmberWarn, fontSize = 11.sp, lineHeight = 16.sp
                    )
                    failure?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, color = ExpenseRed, fontSize = 11.sp)
                    }
                }
            }
        },
        confirmButton = {
            if (done != null) {
                Row {
                    TextButton(onClick = { runCatching { Desktop.getDesktop().open(done) } }) {
                        Text("OPEN", color = TextSubtle, fontWeight = FontWeight.Bold)
                    }
                    TextButton(onClick = onClose) {
                        Text("DONE", color = CyanGlow, fontWeight = FontWeight.Bold)
                    }
                }
            } else {
                TextButton(onClick = {
                    val dialog = FileDialog(null as Frame?, "Save balance sheet", FileDialog.SAVE)
                    dialog.file = "${defaultName(title)}.${format.extension}"
                    dialog.isVisible = true
                    val dir = dialog.directory
                    val name = dialog.file
                    if (dir != null && name != null) {
                        val target = File(
                            dir,
                            if (name.endsWith(".${format.extension}")) name else "$name.${format.extension}"
                        )
                        runCatching {
                            when (format) {
                                ExportFormat.SPREADSHEET -> CashBookExport.csv(target, entries)
                                ExportFormat.STATEMENT -> CashBookExport.html(
                                    file = target,
                                    entries = entries,
                                    title = title,
                                    images = if (!includeSlips) emptyMap() else entries
                                        .flatMap { it.slipFilenames() }
                                        .distinct()
                                        .mapNotNull { slip -> state.readImage(slip)?.let { slip to it } }
                                        .toMap()
                                )
                            }
                        }
                            .onSuccess { done = target }
                            .onFailure { failure = it.message ?: "Could not write the file." }
                    }
                }) {
                    Text("CHOOSE LOCATION…", color = CyanGlow, fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            if (done == null) TextButton(onClick = onClose) { Text("CANCEL", color = TextMuted) }
        }
    )
}

@Composable
private fun FormatOption(option: ExportFormat, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) CyanGlow.copy(0.07f) else SurfaceStone.copy(0.4f))
            .border(
                1.dp,
                if (selected) CyanGlow.copy(0.4f) else GoldDark.copy(0.15f),
                RoundedCornerShape(10.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(14.dp).clip(RoundedCornerShape(7.dp))
                .background(if (selected) CyanGlow else Color.Transparent)
                .border(1.dp, if (selected) CyanGlow else TextMuted, RoundedCornerShape(7.dp))
        )
        Spacer(Modifier.width(11.dp))
        Column {
            Text(
                option.label,
                color = if (selected) GoldIce else TextSubtle,
                fontSize = 12.sp, fontWeight = FontWeight.Bold
            )
            Text(option.hint, color = TextMuted, fontSize = 10.sp)
        }
    }
}

/** "cash_book_august_2026" — recognisable months later, in a folder full of exports. */
private fun defaultName(title: String): String =
    title.lowercase()
        .replace("[^a-z0-9]+".toRegex(), "_")
        .trim('_')
        .ifBlank { "cash_book" }
