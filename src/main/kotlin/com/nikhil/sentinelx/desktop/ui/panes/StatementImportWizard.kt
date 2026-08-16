package com.nikhil.sentinelx.desktop.ui.panes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.nikhil.sentinelx.desktop.core.format.BankTxnEntity
import com.nikhil.sentinelx.desktop.core.format.bankBooks
import com.nikhil.sentinelx.desktop.core.statement.StatementGrid
import com.nikhil.sentinelx.desktop.core.statement.StatementParse
import com.nikhil.sentinelx.desktop.core.statement.StatementPasswordRequired
import com.nikhil.sentinelx.desktop.core.statement.StatementReadException
import com.nikhil.sentinelx.desktop.core.statement.StatementReader
import com.nikhil.sentinelx.desktop.ui.AppState
import com.nikhil.sentinelx.desktop.ui.components.Pill
import com.nikhil.sentinelx.desktop.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The statement import flow: FILE → COLUMNS → FIELDS → REVIEW → done.
 *
 * Every step exists to let the user see and steer what lands in the vault:
 * the column mapping is auto-detected but overridable, the narration fields
 * are extracted but each one is a toggle ("what do I want to take from it"),
 * and the review list shows every row exactly as it will be saved — with
 * duplicates already unticked and balance mismatches flagged in amber.
 */
@Composable
fun StatementImportWizard(state: AppState, defaultBook: String?, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()

    var fileName by remember { mutableStateOf<String?>(null) }
    var fileBytes by remember { mutableStateOf<ByteArray?>(null) }
    var needsPassword by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var reading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var grid by remember { mutableStateOf<StatementGrid?>(null) }

    var step by remember { mutableStateOf(0) }  // 0 file · 1 columns · 2 fields · 3 review · 4 done
    var book by remember { mutableStateOf(defaultBook ?: "") }
    var columns by remember { mutableStateOf<Map<Int, StatementParse.Col>>(emptyMap()) }
    var headerRow by remember { mutableStateOf(-1) }
    var dayFirst by remember { mutableStateOf(true) }
    var ambiguousDates by remember { mutableStateOf(false) }
    var extraction by remember { mutableStateOf(StatementParse.Extraction()) }
    var excluded by remember { mutableStateOf<Set<String>>(emptySet()) }
    var report by remember { mutableStateOf<Triple<Int, Int, Int>?>(null) }

    fun parseFile(bytes: ByteArray, name: String, pw: String?) {
        reading = true
        error = null
        scope.launch {
            val result = withContext(Dispatchers.Default) {
                runCatching { StatementReader.read(bytes, name, pw) }
            }
            reading = false
            result.fold(
                onSuccess = { g ->
                    grid = g
                    needsPassword = false
                    val mapping = StatementParse.detectMapping(g)
                    columns = mapping.columns
                    headerRow = mapping.headerRow
                    dayFirst = mapping.dayFirst
                    ambiguousDates = mapping.ambiguousDateOrder
                    step = 1
                },
                onFailure = { e ->
                    when (e) {
                        is StatementPasswordRequired -> {
                            needsPassword = true
                            error = if (pw.isNullOrEmpty()) null else e.message
                        }
                        is StatementReadException -> error = e.message
                        else -> error = e.message ?: "Could not read the file."
                    }
                }
            )
        }
    }

    fun pickFile() {
        val dialog = FileDialog(null as Frame?, "Choose a bank statement", FileDialog.LOAD)
        dialog.isVisible = true
        val file = dialog.file?.let { File(dialog.directory, it) } ?: return
        runCatching { file.readBytes() }.fold(
            onSuccess = { bytes ->
                fileName = file.name
                fileBytes = bytes
                password = ""
                needsPassword = false
                parseFile(bytes, file.name, null)
            },
            onFailure = { error = "Could not read ${file.name}." }
        )
    }

    // Re-parse rows whenever the grid, mapping or toggles change — pure and fast.
    val mapping = remember(columns, headerRow, dayFirst) {
        StatementParse.Mapping(headerRow, columns, dayFirst, ambiguousDates)
    }
    val outcome = remember(grid, mapping, extraction) {
        grid?.let { runCatching { StatementParse.parse(it, mapping, extraction) }.getOrNull() }
    }
    val existingFingerprints = remember(state.backup.bankTxns, book) {
        val key = book.trim().lowercase()
        state.backup.bankTxns.asSequence()
            .filter { it.book.trim().lowercase() == key }
            .map { it.fingerprint }
            .toHashSet()
    }
    // Duplicates start unticked; everything else starts ticked.
    LaunchedEffect(outcome, existingFingerprints) {
        excluded = outcome?.rows
            ?.filter { it.fingerprint in existingFingerprints }
            ?.map { it.fingerprint }?.toSet() ?: emptySet()
    }
    LaunchedEffect(outcome?.suggestedBook) {
        if (book.isBlank()) outcome?.suggestedBook?.let { book = it }
    }

    Dialog(onDismissRequest = { if (!reading) onClose() }) {
        Column(
            Modifier
                .width(880.dp)
                .heightIn(max = 640.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(BackgroundDeep)
                .border(1.dp, GoldDark.copy(0.4f), RoundedCornerShape(20.dp))
                .padding(26.dp)
        ) {
            // ── Title + stepper ──────────────────────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "IMPORT STATEMENT",
                    color = GoldTarnished, fontSize = 17.sp, fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Serif, letterSpacing = 2.sp
                )
                Spacer(Modifier.weight(1f))
                listOf("FILE", "COLUMNS", "FIELDS", "REVIEW").forEachIndexed { i, label ->
                    StepChip(label, i == step, i < step)
                    if (i < 3) {
                        Box(
                            Modifier.width(14.dp).height(1.dp)
                                .background(if (i < step) GoldTarnished.copy(0.5f) else GoldDark.copy(0.25f))
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            fileName?.let {
                Text(
                    "$it · ${grid?.format ?: ""}".trim(' ', '·'),
                    color = TextMuted, fontSize = 10.sp
                )
            }
            Spacer(Modifier.height(14.dp))
            HorizontalDivider(color = GoldDark.copy(0.15f))
            Spacer(Modifier.height(14.dp))

            when (step) {
                0 -> FileStep(
                    reading = reading,
                    needsPassword = needsPassword,
                    password = password,
                    onPassword = { password = it },
                    error = error,
                    onPick = { pickFile() },
                    onUnlock = {
                        val bytes = fileBytes
                        val name = fileName
                        if (bytes != null && name != null) parseFile(bytes, name, password)
                    }
                )
                1 -> MappingStep(
                    grid = grid,
                    columns = columns,
                    headerRow = headerRow,
                    onColumnChange = { index, col -> columns = columns.toMutableMap().apply { put(index, col) } },
                    book = book,
                    onBook = { book = it },
                    knownBooks = state.backup.bankTxns.bankBooks(),
                    ambiguousDates = ambiguousDates,
                    dayFirst = dayFirst,
                    onDayFirst = { dayFirst = it },
                    parsedCount = outcome?.rows?.size ?: 0,
                    onBack = { step = 0 },
                    onNext = { step = 2 }
                )
                2 -> FieldsStep(
                    extraction = extraction,
                    onExtraction = { extraction = it },
                    sample = outcome?.rows.orEmpty().take(3),
                    onBack = { step = 1 },
                    onNext = { step = 3 }
                )
                3 -> ReviewStep(
                    outcome = outcome,
                    excluded = excluded,
                    duplicates = existingFingerprints,
                    onToggle = { fp ->
                        excluded = if (fp in excluded) excluded - fp else excluded + fp
                    },
                    onBack = { step = 2 },
                    canImport = book.trim().isNotEmpty() &&
                        (outcome?.rows.orEmpty().any { it.fingerprint !in excluded }),
                    bookMissing = book.trim().isEmpty(),
                    onImport = {
                        val rows = outcome?.rows.orEmpty().filter { it.fingerprint !in excluded }
                        val entities = rows.map { row ->
                            BankTxnEntity(
                                book = book.trim(),
                                txnDate = row.dateMillis,
                                narration = row.narration,
                                amount = row.amount,
                                direction = row.direction,
                                balance = row.balance,
                                mode = row.mode,
                                channel = row.channel,
                                reference = row.reference,
                                party = row.party,
                                remark = row.remark,
                                bankName = row.bankName,
                                category = row.category,
                                fingerprint = row.fingerprint
                            )
                        }
                        val (imported, duplicates) = state.importBankTxns(book, entities)
                        report = Triple(imported, duplicates, (outcome?.rows?.size ?: 0) - rows.size)
                        step = 4
                    }
                )
                4 -> DoneStep(
                    report = report,
                    fileName = fileName,
                    onClose = onClose
                )
            }
        }
    }
}

// ── Steps ────────────────────────────────────────────────────────────────────

@Composable
private fun StepChip(label: String, active: Boolean, done: Boolean) {
    Box(
        Modifier
            .clip(CircleShape)
            .background(
                when {
                    active -> GoldTarnished.copy(0.18f)
                    done -> IncomeGreen.copy(0.1f)
                    else -> SurfaceStone
                }
            )
            .border(
                1.dp,
                when {
                    active -> GoldTarnished.copy(0.6f)
                    done -> IncomeGreen.copy(0.4f)
                    else -> GoldDark.copy(0.2f)
                },
                CircleShape
            )
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(
            if (done) "✓ $label" else label,
            color = when {
                active -> GoldIce
                done -> IncomeGreen
                else -> TextMuted
            },
            fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp
        )
    }
}

@Composable
private fun FileStep(
    reading: Boolean,
    needsPassword: Boolean,
    password: String,
    onPassword: (String) -> Unit,
    error: String?,
    onPick: () -> Unit,
    onUnlock: () -> Unit
) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(18.dp))
        Text("ᛒ", color = GoldDark.copy(0.5f), fontSize = 44.sp)
        Spacer(Modifier.height(12.dp))
        Text(
            "Choose the statement your bank gave you",
            color = TextParchment, fontSize = 14.sp, fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Excel (.xlsx, .xls), CSV, PDF — even password-protected PDFs and the fake " +
                "\"Excel\" files that are secretly web pages. The reader checks what the file " +
                "actually is, not what it is called.",
            color = TextMuted, fontSize = 11.sp, lineHeight = 17.sp,
            modifier = Modifier.width(460.dp)
        )
        Spacer(Modifier.height(20.dp))

        if (needsPassword) {
            Text(
                "THIS PDF IS LOCKED — ENTER ITS PASSWORD",
                color = AmberWarn, fontSize = 9.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = password,
                onValueChange = onPassword,
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                placeholder = { Text("Statement password", color = TextMuted, fontSize = 12.sp) },
                modifier = Modifier.width(320.dp),
                shape = RoundedCornerShape(11.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = CyanGlow.copy(0.6f),
                    unfocusedBorderColor = GoldDark.copy(0.25f),
                    focusedTextColor = TextParchment,
                    unfocusedTextColor = TextParchment,
                    cursorColor = CyanGlow,
                    focusedContainerColor = SurfaceGem,
                    unfocusedContainerColor = SurfaceStone
                )
            )
            Spacer(Modifier.height(12.dp))
            WizardButton(if (reading) "UNLOCKING…" else "UNLOCK", enabled = !reading && password.isNotEmpty(), onClick = onUnlock)
            Spacer(Modifier.height(10.dp))
            Text("Choose a different file", color = CyanGlow, fontSize = 11.sp,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = !reading) { onPick() }.padding(6.dp))
        } else {
            WizardButton(if (reading) "READING…" else "CHOOSE FILE", enabled = !reading, onClick = onPick)
        }

        error?.let {
            Spacer(Modifier.height(14.dp))
            Text(it, color = ExpenseRed, fontSize = 11.sp, modifier = Modifier.width(460.dp))
        }
        Spacer(Modifier.height(18.dp))
    }
}

@Composable
private fun MappingStep(
    grid: StatementGrid?,
    columns: Map<Int, StatementParse.Col>,
    headerRow: Int,
    onColumnChange: (Int, StatementParse.Col) -> Unit,
    book: String,
    onBook: (String) -> Unit,
    knownBooks: List<String>,
    ambiguousDates: Boolean,
    dayFirst: Boolean,
    onDayFirst: (Boolean) -> Unit,
    parsedCount: Int,
    onBack: () -> Unit,
    onNext: () -> Unit
) {
    grid ?: return
    val width = grid.rows.maxOfOrNull { it.size } ?: 0
    val previewRows = remember(grid, headerRow) {
        val start = (headerRow + 1).coerceAtLeast(0)
        grid.rows.drop(start).filter { row -> row.any { it.isNotBlank() } }.take(6)
    }

    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "WHICH ACCOUNT IS THIS?",
                    color = GoldTarnished, fontSize = 8.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(5.dp))
                OutlinedTextField(
                    value = book,
                    onValueChange = onBook,
                    singleLine = true,
                    placeholder = { Text("e.g. HDFC Savings", color = TextMuted, fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(11.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CyanGlow.copy(0.6f),
                        unfocusedBorderColor = GoldDark.copy(0.25f),
                        focusedTextColor = TextParchment,
                        unfocusedTextColor = TextParchment,
                        cursorColor = CyanGlow,
                        focusedContainerColor = SurfaceGem,
                        unfocusedContainerColor = SurfaceStone
                    )
                )
                if (knownBooks.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        knownBooks.take(6).forEach { known ->
                            Box(
                                Modifier.padding(end = 6.dp).clip(CircleShape)
                                    .background(if (known.equals(book, true)) CyanGlow.copy(0.16f) else SurfaceStone)
                                    .border(1.dp, CyanGlow.copy(0.35f), CircleShape)
                                    .clickable { onBook(known) }
                                    .padding(horizontal = 10.dp, vertical = 5.dp)
                            ) {
                                Text(known, color = CyanGlow, fontSize = 10.sp)
                            }
                        }
                    }
                }
            }
            if (ambiguousDates) {
                Spacer(Modifier.width(18.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        "DATE ORDER",
                        color = AmberWarn, fontSize = 8.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(5.dp))
                    Row {
                        ToggleChip("DD/MM", dayFirst) { onDayFirst(true) }
                        Spacer(Modifier.width(6.dp))
                        ToggleChip("MM/DD", !dayFirst) { onDayFirst(false) }
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        Text(
            "COLUMNS — auto-detected, click any label to correct it",
            color = TextMuted, fontSize = 9.sp, letterSpacing = 1.5.sp
        )
        Spacer(Modifier.height(8.dp))

        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            for (c in 0 until width) {
                Column(
                    Modifier.width(132.dp).padding(end = 6.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(SurfaceStone)
                        .border(1.dp, GoldDark.copy(0.18f), RoundedCornerShape(10.dp))
                ) {
                    ColumnChip(columns[c] ?: StatementParse.Col.IGNORE) { onColumnChange(c, it) }
                    HorizontalDivider(color = GoldDark.copy(0.12f))
                    if (headerRow >= 0) {
                        Text(
                            grid.rows[headerRow].getOrNull(c).orEmpty().ifBlank { "—" },
                            color = TextSubtle, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                        HorizontalDivider(color = GoldDark.copy(0.08f))
                    }
                    previewRows.forEach { row ->
                        Text(
                            row.getOrNull(c).orEmpty().ifBlank { " " },
                            color = TextMuted, fontSize = 9.sp, maxLines = 1,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (parsedCount > 0) "$parsedCount transactions recognised"
                else "No transactions recognised yet — check the Date and amount columns",
                color = if (parsedCount > 0) IncomeGreen else AmberWarn,
                fontSize = 11.sp, fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.weight(1f))
            BackButton(onBack)
            Spacer(Modifier.width(8.dp))
            WizardButton("NEXT", enabled = parsedCount > 0, onClick = onNext)
        }
    }
}

@Composable
private fun ColumnChip(current: StatementParse.Col, onChange: (StatementParse.Col) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val tone = when (current) {
        StatementParse.Col.DATE, StatementParse.Col.VALUE_DATE -> CyanGlow
        StatementParse.Col.DEBIT -> ExpenseRed
        StatementParse.Col.CREDIT -> IncomeGreen
        StatementParse.Col.AMOUNT, StatementParse.Col.DRCR -> GoldTarnished
        StatementParse.Col.BALANCE -> PurpleMystic
        StatementParse.Col.NARRATION -> GoldBright
        StatementParse.Col.REFERENCE -> TextSubtle
        StatementParse.Col.IGNORE -> TextMuted
    }
    Box {
        Row(
            Modifier.fillMaxWidth().clickable { open = true }.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                current.label.uppercase(),
                color = tone, fontSize = 8.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp,
                modifier = Modifier.weight(1f), maxLines = 1
            )
            Text("▾", color = TextMuted, fontSize = 8.sp)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            StatementParse.Col.entries.forEach { col ->
                DropdownMenuItem(
                    text = { Text(col.label, fontSize = 12.sp) },
                    onClick = { onChange(col); open = false }
                )
            }
        }
    }
}

@Composable
private fun FieldsStep(
    extraction: StatementParse.Extraction,
    onExtraction: (StatementParse.Extraction) -> Unit,
    sample: List<StatementParse.ParsedRow>,
    onBack: () -> Unit,
    onNext: () -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            "The narration packs several facts into one line. Pick which ones to pull out " +
                "into their own fields — the full line is always kept either way.",
            color = TextSubtle, fontSize = 11.sp, lineHeight = 17.sp
        )
        Spacer(Modifier.height(14.dp))

        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.width(250.dp)) {
                FieldToggle("Payment mode", "UPI · NEFT · ATM…", extraction.mode) {
                    onExtraction(extraction.copy(mode = it))
                }
                FieldToggle("Shop or person", "P2M means a merchant, P2A a person", extraction.channel) {
                    onExtraction(extraction.copy(channel = it))
                }
                FieldToggle("Reference no.", "The UPI/UTR number that traces it", extraction.reference) {
                    onExtraction(extraction.copy(reference = it))
                }
                FieldToggle("Payee name", "Who the money went to or came from", extraction.party) {
                    onExtraction(extraction.copy(party = it))
                }
                FieldToggle("Remark", "The note typed with the payment", extraction.remark) {
                    onExtraction(extraction.copy(remark = it))
                }
                FieldToggle("Their bank", "The payee's bank, when named", extraction.bank) {
                    onExtraction(extraction.copy(bank = it))
                }
                FieldToggle("Auto-category", "Food, Travel, Bills… guessed for you", extraction.autoCategory) {
                    onExtraction(extraction.copy(autoCategory = it))
                }
            }

            Spacer(Modifier.width(18.dp))

            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Text(
                    "LIVE PREVIEW",
                    color = GoldTarnished, fontSize = 8.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                if (sample.isEmpty()) {
                    Text("No parsed rows to preview.", color = TextMuted, fontSize = 11.sp)
                }
                sample.forEach { row ->
                    Column(
                        Modifier.fillMaxWidth().padding(bottom = 8.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(SurfaceStone)
                            .border(1.dp, GoldDark.copy(0.18f), RoundedCornerShape(10.dp))
                            .padding(10.dp)
                    ) {
                        Text(row.narration.take(110), color = TextMuted, fontSize = 9.sp, maxLines = 2)
                        Spacer(Modifier.height(6.dp))
                        Row(Modifier.horizontalScroll(rememberScrollState())) {
                            row.mode?.let { PreviewTag("MODE", it, CyanGlow) }
                            row.channel?.let { PreviewTag("VIA", if (it == "P2M") "SHOP" else "PERSON", GoldTarnished) }
                            row.reference?.let { PreviewTag("REF", it.take(14), TextSubtle) }
                            row.party?.let { PreviewTag("WHO", it.take(22), IncomeGreen) }
                            row.remark?.let { PreviewTag("REMARK", it.take(16), PurpleMystic) }
                            row.bankName?.let { PreviewTag("BANK", it.take(18), GoldBright) }
                            PreviewTag("TAG", row.category.uppercase(), AmberWarn)
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Row {
            Spacer(Modifier.weight(1f))
            BackButton(onBack)
            Spacer(Modifier.width(8.dp))
            WizardButton("NEXT", enabled = true, onClick = onNext)
        }
    }
}

@Composable
private fun PreviewTag(label: String, value: String, tone: Color) {
    Row(
        Modifier.padding(end = 6.dp).clip(RoundedCornerShape(7.dp))
            .background(tone.copy(0.1f))
            .border(1.dp, tone.copy(0.3f), RoundedCornerShape(7.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = tone.copy(0.7f), fontSize = 7.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(4.dp))
        Text(value, color = tone, fontSize = 9.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun FieldToggle(title: String, hint: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable { onChange(!on) }
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(16.dp).clip(RoundedCornerShape(5.dp))
                .background(if (on) IncomeGreen else SurfaceElevated)
                .border(1.dp, if (on) IncomeGreen else GoldDark.copy(0.3f), RoundedCornerShape(5.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (on) Text("✓", color = BackgroundDeep, fontSize = 10.sp, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.width(9.dp))
        Column {
            Text(title, color = if (on) TextParchment else TextSubtle, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(hint, color = TextMuted, fontSize = 9.sp)
        }
    }
}

@Composable
private fun ReviewStep(
    outcome: StatementParse.ParseOutcome?,
    excluded: Set<String>,
    duplicates: Set<String>,
    onToggle: (String) -> Unit,
    onBack: () -> Unit,
    canImport: Boolean,
    bookMissing: Boolean,
    onImport: () -> Unit
) {
    outcome ?: return
    val included = outcome.rows.filter { it.fingerprint !in excluded }
    val totalIn = included.filter { it.isCredit }.sumOf { it.amount }
    val totalOut = included.filterNot { it.isCredit }.sumOf { it.amount }

    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${included.size} of ${outcome.rows.size} rows selected",
                color = TextParchment, fontSize = 12.sp, fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.width(14.dp))
            Text("+${formatMoney(totalIn)}", color = IncomeGreen, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            Text("−${formatMoney(totalOut)}", color = ExpenseRed, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            if (outcome.skipped > 0) {
                Text("${outcome.skipped} non-transaction rows ignored", color = TextMuted, fontSize = 9.sp)
            }
        }
        outcome.warnings.take(3).forEach {
            Spacer(Modifier.height(4.dp))
            Text("⚠ $it", color = AmberWarn, fontSize = 10.sp)
        }
        if (bookMissing) {
            Spacer(Modifier.height(4.dp))
            Text("⚠ Name the account on the COLUMNS step before importing.", color = ExpenseRed, fontSize = 10.sp)
        }
        Spacer(Modifier.height(10.dp))

        LazyColumn(
            Modifier.weight(1f, fill = false).heightIn(max = 330.dp).fillMaxWidth()
        ) {
            items(outcome.rows, key = { it.fingerprint }) { row ->
                ReviewRow(
                    row = row,
                    included = row.fingerprint !in excluded,
                    duplicate = row.fingerprint in duplicates,
                    onToggle = { onToggle(row.fingerprint) }
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Row {
            Spacer(Modifier.weight(1f))
            BackButton(onBack)
            Spacer(Modifier.width(8.dp))
            WizardButton("IMPORT ${included.size}", enabled = canImport, onClick = onImport)
        }
    }
}

@Composable
private fun ReviewRow(
    row: StatementParse.ParsedRow,
    included: Boolean,
    duplicate: Boolean,
    onToggle: () -> Unit
) {
    val tone = if (row.isCredit) IncomeGreen else ExpenseRed
    Row(
        Modifier.fillMaxWidth().padding(vertical = 1.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(if (included) Color.Transparent else SurfaceStone.copy(0.4f))
            .clickable { onToggle() }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(15.dp).clip(RoundedCornerShape(4.dp))
                .background(if (included) IncomeGreen else SurfaceElevated)
                .border(1.dp, if (included) IncomeGreen else GoldDark.copy(0.3f), RoundedCornerShape(4.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (included) Text("✓", color = BackgroundDeep, fontSize = 9.sp, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.width(9.dp))
        Text(
            LocalDate.parse(row.dateIso).format(reviewDate),
            color = TextSubtle, fontSize = 9.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.width(52.dp)
        )
        Column(Modifier.weight(1f)) {
            Text(
                row.party ?: row.narration.take(56),
                color = if (included) TextParchment else TextMuted,
                fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1
            )
            if (row.party != null) {
                Text(row.narration.take(80), color = TextMuted, fontSize = 8.sp, maxLines = 1)
            }
        }
        if (duplicate) {
            Spacer(Modifier.width(6.dp))
            Pill("IN VAULT", TextMuted)
        }
        if (row.balanceAgrees == false) {
            Spacer(Modifier.width(6.dp))
            Pill("BALANCE?", AmberWarn)
        }
        Spacer(Modifier.width(10.dp))
        Text(
            (if (row.isCredit) "+" else "−") + formatMoney(row.amount),
            color = if (included) tone else TextMuted,
            fontSize = 11.sp, fontWeight = FontWeight.Black,
            modifier = Modifier.width(94.dp),
            maxLines = 1
        )
    }
}

private val reviewDate = DateTimeFormatter.ofPattern("dd MMM", Locale.ENGLISH)

@Composable
private fun DoneStep(report: Triple<Int, Int, Int>?, fileName: String?, onClose: () -> Unit) {
    val (imported, duplicates, unticked) = report ?: Triple(0, 0, 0)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(16.dp))
        Text("✓", color = IncomeGreen, fontSize = 40.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.height(10.dp))
        Text(
            "$imported transaction${if (imported == 1) "" else "s"} sealed into the vault",
            color = TextParchment, fontSize = 15.sp, fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(6.dp))
        val detail = buildList {
            if (duplicates > 0) add("$duplicates already there (skipped)")
            if (unticked > 0) add("$unticked left unticked")
        }.joinToString(" · ")
        if (detail.isNotEmpty()) Text(detail, color = TextMuted, fontSize = 11.sp)

        Spacer(Modifier.height(16.dp))
        fileName?.let {
            Text(
                "\"$it\" itself is still where you downloaded it, unencrypted. The vault now " +
                    "holds every row sealed — delete the file if you don't want a plaintext copy around.",
                color = AmberWarn.copy(0.85f), fontSize = 10.sp, lineHeight = 15.sp,
                modifier = Modifier.width(430.dp)
            )
        }
        Spacer(Modifier.height(18.dp))
        WizardButton("DONE", enabled = true, onClick = onClose)
        Spacer(Modifier.height(10.dp))
    }
}

// ── Small shared bits ────────────────────────────────────────────────────────

@Composable
private fun WizardButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (enabled) Brush.linearGradient(listOf(GoldBright, GoldTarnished))
                else Brush.linearGradient(listOf(SurfaceGem, SurfaceStone))
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp)
    ) {
        Text(
            label,
            color = if (enabled) BackgroundVoid else TextMuted,
            fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp
        )
    }
}

@Composable
private fun BackButton(onClick: () -> Unit) {
    Text(
        "BACK", color = TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
        modifier = Modifier.clip(RoundedCornerShape(9.dp)).clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp)
    )
}

@Composable
private fun ToggleChip(label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(CircleShape)
            .background(if (active) AmberWarn.copy(0.16f) else SurfaceStone)
            .border(1.dp, if (active) AmberWarn.copy(0.5f) else GoldDark.copy(0.2f), CircleShape)
            .clickable { onClick() }
            .padding(horizontal = 11.dp, vertical = 6.dp)
    ) {
        Text(
            label, color = if (active) AmberWarn else TextMuted,
            fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp
        )
    }
}