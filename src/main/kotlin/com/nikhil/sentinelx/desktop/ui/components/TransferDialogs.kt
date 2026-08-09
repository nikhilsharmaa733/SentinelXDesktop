package com.nikhil.sentinelx.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nikhil.sentinelx.desktop.core.format.*
import com.nikhil.sentinelx.desktop.ui.AppState
import com.nikhil.sentinelx.desktop.ui.Section
import com.nikhil.sentinelx.desktop.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/**
 * Moving records in and out of the vault, whole or a section at a time.
 *
 * Both dialogs take a [scope]: null for the sidebar's whole-vault commands, or a
 * [Section] when a pane opens them, in which case they only ever touch that pane's
 * records. Scoping the *import* matters as much as scoping the export — opening a full
 * backup from the Logins pane should bring in logins and leave the ledger alone, which
 * is what somebody clicking "Import" inside a pane plainly means.
 */

// ── Import ────────────────────────────────────────────────────────────────────

/**
 * Three steps: choose the file, decide how it lands, commit.
 *
 * The middle step is the one that matters. Import used to replace the vault wholesale
 * with no alternative, so carrying an archive between two machines that were both in
 * use meant destroying whichever side you imported into. The counts shown there are
 * computed by [VaultMerge.preview] and are independent of the policy, so they can be
 * read before the choice rather than after it.
 */
@Composable
fun VaultImportDialog(state: AppState, scope: Section? = null, onClose: () -> Unit) {
    var path by remember { mutableStateOf<File?>(null) }
    var password by remember { mutableStateOf("") }
    var payload by remember { mutableStateOf<SxvArchive.Payload?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var mode by remember { mutableStateOf(VaultMerge.Mode.MERGE) }
    var policy by remember { mutableStateOf(VaultMerge.DuplicatePolicy.SKIP) }
    val coroutines = rememberCoroutineScope()

    // A pane's import only ever considers that pane's records, whatever else the file
    // happens to hold.
    val scoped = remember(payload, scope) {
        val found = payload ?: return@remember null
        if (scope?.wire == null) found
        else found.copy(backup = found.backup.scopedTo(listOf(scope.wire)))
    }
    val plan = remember(scoped) { scoped?.let { state.planImport(it) } }
    val carried = scoped?.backup?.carriedSections().orEmpty()
    val nothingToDo = scoped != null && carried.sumOf { scoped.backup.countIn(it) } == 0

    AlertDialog(
        onDismissRequest = onClose,
        containerColor = BackgroundDeep,
        shape = RoundedCornerShape(20.dp),
        title = {
            Text(
                when {
                    payload == null && scope != null -> "IMPORT ${scope.label.uppercase()}"
                    payload == null -> "IMPORT MIGRATION SEAL"
                    else -> "HOW SHOULD THIS LAND?"
                },
                color = GoldTarnished, fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp, fontSize = 14.sp
            )
        },
        text = {
            Column(Modifier.width(470.dp).heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
                val found = scoped
                if (found == null) {
                    Text(
                        path?.name ?: "No file chosen",
                        color = if (path == null) TextMuted else TextParchment, fontSize = 12.sp
                    )
                    Spacer(Modifier.height(10.dp))
                    TextButton(onClick = { path = chooseSxvFile() }) {
                        Text("Choose .sxv file…", color = CyanGlow, fontSize = 12.sp)
                    }
                    if (scope != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Only ${scope.label.lowercase()} will be read from it. Everything else " +
                                "in the file is ignored.",
                            color = TextMuted, fontSize = 11.sp, lineHeight = 16.sp
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        singleLine = true,
                        label = { Text("Archive password", fontSize = 11.sp) },
                        visualTransformation = PasswordVisualTransformation(),
                        colors = transferFieldColors(),
                        modifier = Modifier.fillMaxWidth()
                    )
                } else if (nothingToDo) {
                    Text(
                        "This archive holds no ${scope?.label?.lowercase() ?: "records"}.",
                        color = AmberWarn, fontSize = 12.sp
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "It carries: " + found.backup.carriedSections()
                            .filter { found.backup.countIn(it) > 0 }
                            .joinToString { "${VaultSection.label(it)} (${found.backup.countIn(it)})" }
                            .ifEmpty { "nothing" },
                        color = TextMuted, fontSize = 11.sp, lineHeight = 16.sp
                    )
                } else {
                    ArchiveContents(found, carried)
                    Spacer(Modifier.height(16.dp))

                    FieldLabel("HOW SHOULD IT LAND?")
                    ChoiceRow(
                        selected = mode == VaultMerge.Mode.MERGE,
                        title = "Merge",
                        detail = "Keep what is here and add what is new.",
                        onClick = { mode = VaultMerge.Mode.MERGE }
                    )
                    ChoiceRow(
                        selected = mode == VaultMerge.Mode.REPLACE,
                        title = "Replace",
                        detail = replaceWarning(carried),
                        accent = ExpenseRed,
                        onClick = { mode = VaultMerge.Mode.REPLACE }
                    )

                    if (mode == VaultMerge.Mode.MERGE) {
                        Spacer(Modifier.height(14.dp))
                        FieldLabel("IF A RECORD ALREADY EXISTS")
                        ChoiceRow(
                            selected = policy == VaultMerge.DuplicatePolicy.SKIP,
                            title = "Do not copy",
                            detail = "Keep the copy already in this vault.",
                            onClick = { policy = VaultMerge.DuplicatePolicy.SKIP }
                        )
                        ChoiceRow(
                            selected = policy == VaultMerge.DuplicatePolicy.OVERWRITE,
                            title = "Replace it",
                            detail = "Take the archive's copy instead.",
                            onClick = { policy = VaultMerge.DuplicatePolicy.OVERWRITE }
                        )
                        ChoiceRow(
                            selected = policy == VaultMerge.DuplicatePolicy.KEEP_BOTH,
                            title = "Keep both",
                            detail = "The incoming copy is marked ${VaultMerge.IMPORT_MARK}.",
                            onClick = { policy = VaultMerge.DuplicatePolicy.KEEP_BOTH }
                        )
                    }

                    if (plan != null) {
                        Spacer(Modifier.height(16.dp))
                        FieldLabel("WHAT WILL HAPPEN")
                        if (mode == VaultMerge.Mode.REPLACE) {
                            Text(
                                "Every record listed above is discarded and rewritten from the " +
                                    "archive. Version History can undo it.",
                                color = AmberWarn, fontSize = 11.sp, lineHeight = 16.sp
                            )
                        } else {
                            OutcomeTable(plan, policy)
                        }
                    }

                    val missing = found.missingImages()
                    if (missing.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
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
            val found = scoped
            TextButton(
                enabled = if (found == null) path != null && password.isNotEmpty() else !nothingToDo,
                onClick = {
                    if (found == null) {
                        coroutines.launch {
                            message = null
                            withContext(Dispatchers.Default) {
                                runCatching { state.previewArchive(path!!, password.toCharArray()) }
                            }
                                .onSuccess { payload = it }
                                .onFailure { message = it.message ?: "Could not read archive." }
                        }
                    } else {
                        coroutines.launch {
                            withContext(Dispatchers.Default) { state.adoptArchive(found, mode, policy) }
                            password = ""
                            onClose()
                        }
                    }
                }
            ) {
                Text(
                    when {
                        found == null -> "READ ARCHIVE"
                        nothingToDo -> "NOTHING TO IMPORT"
                        mode == VaultMerge.Mode.REPLACE -> "REPLACE"
                        else -> "MERGE"
                    },
                    color = when {
                        found == null -> CyanGlow
                        nothingToDo -> TextMuted
                        mode == VaultMerge.Mode.REPLACE -> ExpenseRed
                        else -> IncomeGreen
                    },
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            TextButton(onClick = { password = ""; onClose() }) { Text("CANCEL", color = TextMuted) }
        }
    )
}

private fun replaceWarning(carried: List<String>): String =
    if (carried.size >= VaultSection.ALL.size) "Discard everything in this vault and use the archive."
    else "Discard this vault's ${carried.joinToString { VaultSection.label(it).lowercase() }} " +
        "and use the archive's. Nothing else is touched."

@Composable
private fun ArchiveContents(payload: SxvArchive.Payload, carried: List<String>) {
    FieldLabel("WHAT IS IN THE ARCHIVE")
    carried.forEach { section ->
        CountRow(VaultSection.label(section), payload.backup.countIn(section))
    }
    CountRow("Images", payload.images.size, muted = true)
}

/** Per-section outcome, so nobody has to guess what "merge" means for their data. */
@Composable
private fun OutcomeTable(plan: VaultMerge.Plan, policy: VaultMerge.DuplicatePolicy) {
    val touched = plan.sections.filter { it.incoming > 0 }
    if (touched.isEmpty()) {
        Text("Nothing to add.", color = TextMuted, fontSize = 11.sp)
        return
    }
    touched.forEach { stats ->
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                VaultSection.label(stats.section),
                color = TextSubtle, fontSize = 11.sp, modifier = Modifier.width(130.dp)
            )
            Tally(stats.fresh, "new", IncomeGreen)
            Tally(stats.identical, "already here", TextMuted)
            Tally(stats.conflicting, conflictVerb(policy), AmberWarn)
        }
    }
    if (plan.conflicting > 0) {
        Spacer(Modifier.height(8.dp))
        Text(
            when (policy) {
                VaultMerge.DuplicatePolicy.SKIP ->
                    "${plan.conflicting} record(s) exist here with different contents and will be left as they are."
                VaultMerge.DuplicatePolicy.OVERWRITE ->
                    "${plan.conflicting} record(s) here will be overwritten by the archive's version."
                VaultMerge.DuplicatePolicy.KEEP_BOTH ->
                    "${plan.conflicting} record(s) will be added alongside the ones already here."
            },
            color = TextMuted, fontSize = 10.sp, lineHeight = 15.sp
        )
    }
}

private fun conflictVerb(policy: VaultMerge.DuplicatePolicy): String = when (policy) {
    VaultMerge.DuplicatePolicy.SKIP -> "skipped"
    VaultMerge.DuplicatePolicy.OVERWRITE -> "overwritten"
    VaultMerge.DuplicatePolicy.KEEP_BOTH -> "kept as copies"
}

@Composable
private fun RowScope.Tally(count: Int, label: String, tint: Color) {
    if (count == 0) {
        Spacer(Modifier.weight(1f))
        return
    }
    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
        Text("$count", color = tint, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(4.dp))
        Text(label, color = TextMuted, fontSize = 9.sp)
    }
}

// ── Export ────────────────────────────────────────────────────────────────────

/**
 * Writes a Migration Seal the phone can restore, closing the round trip.
 *
 * Always v2 (SXV2, 600,000 iterations), matching what the phone writes. Requires the
 * password twice — a mistyped export password produces an archive nobody can ever open,
 * and there is no way to detect that until you need it.
 */
@Composable
fun VaultExportDialog(state: AppState, scope: Section? = null, onClose: () -> Unit) {
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf<String?>(null) }
    val coroutines = rememberCoroutineScope()

    val sections = scope?.wire?.let { listOf(it) } ?: VaultSection.ALL
    val tooShort = password.isNotEmpty() && password.length < 8
    val mismatch = confirm.isNotEmpty() && password != confirm
    val ready = password.length >= 8 && password == confirm

    AlertDialog(
        onDismissRequest = onClose,
        containerColor = BackgroundDeep,
        shape = RoundedCornerShape(20.dp),
        title = {
            Text(
                when {
                    done != null -> "SEAL CREATED"
                    scope != null -> "EXPORT ${scope.label.uppercase()}"
                    else -> "EXPORT MIGRATION SEAL"
                },
                color = GoldTarnished, fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp, fontSize = 14.sp
            )
        },
        text = {
            Column(Modifier.width(430.dp)) {
                if (done != null) {
                    Text("Saved to:", color = TextSubtle, fontSize = 12.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(done!!, color = GoldIce, fontSize = 12.sp)
                    Spacer(Modifier.height(14.dp))
                    Text(
                        if (scope == null)
                            "On the phone: Vault → Migration Restore, and enter this password."
                        else
                            "On the phone: open ${scope.label}, then Import, and enter this " +
                                "password. Choose Merge there unless you mean to discard what " +
                                "the phone already holds.",
                        color = TextSubtle, fontSize = 11.sp, lineHeight = 17.sp
                    )
                } else {
                    Text(
                        if (scope == null)
                            "This archive can be restored on the Android app. Store the password " +
                                "somewhere safe — it cannot be recovered."
                        else
                            "Only ${scope.label.lowercase()} go into this file, along with any " +
                                "images they reference. The password cannot be recovered.",
                        color = TextSubtle, fontSize = 12.sp, lineHeight = 17.sp
                    )
                    Spacer(Modifier.height(14.dp))
                    sections.forEach { CountRow(VaultSection.label(it), state.backup.countIn(it)) }
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = password, onValueChange = { password = it }, singleLine = true,
                        label = { Text("Archive password", fontSize = 11.sp) },
                        visualTransformation = PasswordVisualTransformation(),
                        colors = transferFieldColors(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = confirm, onValueChange = { confirm = it }, singleLine = true,
                        label = { Text("Confirm password", fontSize = 11.sp) },
                        visualTransformation = PasswordVisualTransformation(),
                        colors = transferFieldColors(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    val warn = when {
                        message != null -> message
                        mismatch -> "Passwords do not match."
                        tooShort -> "At least 8 characters."
                        else -> null
                    }
                    warn?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, color = if (tooShort) AmberWarn else ExpenseRed, fontSize = 11.sp)
                    }
                }
            }
        },
        confirmButton = {
            if (done != null) {
                TextButton(onClick = onClose) {
                    Text("DONE", color = CyanGlow, fontWeight = FontWeight.Bold)
                }
            } else {
                TextButton(enabled = ready, onClick = {
                    val target = chooseSaveLocation(defaultArchiveName(scope)) ?: return@TextButton
                    coroutines.launch {
                        message = null
                        val ok = withContext(Dispatchers.Default) {
                            state.exportArchive(target, password.toCharArray(), sections)
                        }
                        password = ""; confirm = ""
                        if (ok) done = target.absolutePath else message = state.error ?: "Export failed."
                    }
                }) {
                    Text(
                        "CHOOSE LOCATION…",
                        color = if (ready) CyanGlow else TextMuted, fontWeight = FontWeight.Bold
                    )
                }
            }
        },
        dismissButton = {
            if (done == null) TextButton(onClick = { password = ""; onClose() }) {
                Text("CANCEL", color = TextMuted)
            }
        }
    )
}

/**
 * A scoped archive is named for what it holds.
 *
 * A pre-v8 build ignores the `sections` tag and would treat a logins-only file as a
 * whole-vault backup, so the filename is the last line of defence against restoring one
 * on an old install and wiping everything else.
 */
private fun defaultArchiveName(scope: Section?): String {
    val what = scope?.wire?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "Migration"
    return "Sentinel_${what}_${System.currentTimeMillis()}.sxv"
}

// ── Pane entry point ──────────────────────────────────────────────────────────

/**
 * The import/export pair for one pane, as a header action.
 *
 * Every pane gets the same control in the same place, so "where do I get my cards out
 * of this thing" has one answer rather than six.
 */
@Composable
fun TransferActions(state: AppState, scope: Section) {
    var importing by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }

    TextButton(onClick = { importing = true }) {
        Text("IMPORT", color = TextSubtle, fontSize = 11.sp, letterSpacing = 1.sp)
    }
    TextButton(onClick = { exporting = true }) {
        Text("EXPORT", color = TextSubtle, fontSize = 11.sp, letterSpacing = 1.sp)
    }

    if (importing) VaultImportDialog(state, scope) { importing = false }
    if (exporting) VaultExportDialog(state, scope) { exporting = false }
}

// ── Shared bits ───────────────────────────────────────────────────────────────

@Composable
private fun FieldLabel(text: String) {
    Text(
        text, color = GoldTarnished, fontSize = 9.sp,
        letterSpacing = 2.sp, fontWeight = FontWeight.Bold
    )
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun CountRow(label: String, count: Int, muted: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, color = if (muted) TextMuted else TextSubtle, fontSize = 12.sp)
        Spacer(Modifier.weight(1f))
        Text(
            "$count",
            color = if (count == 0) TextMuted else GoldIce,
            fontSize = 12.sp, fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun ChoiceRow(
    selected: Boolean,
    title: String,
    detail: String,
    accent: Color = CyanGlow,
    onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) accent.copy(0.09f) else Color.Transparent)
            .border(
                1.dp,
                if (selected) accent.copy(0.45f) else GoldDark.copy(0.12f),
                RoundedCornerShape(10.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 11.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(13.dp).clip(CircleShape)
                .border(1.dp, if (selected) accent else TextMuted, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (selected) Box(Modifier.size(7.dp).clip(CircleShape).background(accent))
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                title,
                color = if (selected) GoldIce else TextParchment,
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
            )
            Text(detail, color = TextMuted, fontSize = 10.sp, lineHeight = 14.sp)
        }
    }
}

@Composable
private fun transferFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = CyanGlow.copy(0.7f),
    unfocusedBorderColor = GoldDark.copy(0.3f),
    focusedTextColor = TextParchment,
    unfocusedTextColor = TextParchment,
    cursorColor = CyanGlow
)

/**
 * AWT's native file dialog rather than Swing's JFileChooser — it uses the real
 * Windows/GTK picker, which looks like the rest of the OS instead of like Java.
 */
fun chooseSxvFile(): File? {
    val dialog = FileDialog(null as Frame?, "Select Migration Seal", FileDialog.LOAD)
    dialog.setFilenameFilter { _, name -> name.endsWith(".sxv") }
    dialog.isVisible = true
    val dir = dialog.directory ?: return null
    val file = dialog.file ?: return null
    return File(dir, file)
}

fun chooseSaveLocation(defaultName: String): File? {
    val dialog = FileDialog(null as Frame?, "Save Migration Seal", FileDialog.SAVE)
    dialog.file = defaultName
    dialog.isVisible = true
    val dir = dialog.directory ?: return null
    val file = dialog.file ?: return null
    return File(dir, if (file.endsWith(".sxv")) file else "$file.sxv")
}
