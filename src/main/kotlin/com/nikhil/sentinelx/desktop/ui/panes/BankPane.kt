package com.nikhil.sentinelx.desktop.ui.panes

import androidx.compose.foundation.Canvas
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nikhil.sentinelx.desktop.core.format.BankTxnEntity
import com.nikhil.sentinelx.desktop.core.format.bankBooks
import com.nikhil.sentinelx.desktop.core.format.businessDateOf
import com.nikhil.sentinelx.desktop.core.format.displayParty
import com.nikhil.sentinelx.desktop.core.format.isCredit
import com.nikhil.sentinelx.desktop.core.format.signedAmount
import com.nikhil.sentinelx.desktop.core.format.totalIn
import com.nikhil.sentinelx.desktop.core.format.totalOut
import com.nikhil.sentinelx.desktop.ui.AppState
import com.nikhil.sentinelx.desktop.ui.Section
import com.nikhil.sentinelx.desktop.ui.components.*
import com.nikhil.sentinelx.desktop.ui.theme.*
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The Bank Book — statements imported, understood and analysed.
 *
 * Left rail lists the books (one per bank account); the content side carries
 * the month filter, in/out/net tiles, the true balance trend (the statement's
 * own running balance, not a synthetic one), category breakdown and the
 * transaction list. Rows open an editor for the human fields — category,
 * party, remark — while the narration stays read-only: it is the record.
 */
@Composable
fun BankPane(state: AppState) {
    val all = state.backup.bankTxns

    var selectedBook by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var month by remember { mutableStateOf<YearMonth?>(null) }
    var direction by remember { mutableStateOf<String?>(null) }  // null · "C" · "D"
    var wizardOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<BankTxnEntity?>(null) }
    var renamingBook by remember { mutableStateOf<String?>(null) }
    var deletingBook by remember { mutableStateOf<String?>(null) }

    val books = remember(all) { all.bankBooks() }
    // A deleted or renamed book must not leave a stale filter behind.
    LaunchedEffect(books) {
        if (selectedBook != null && books.none { it.equals(selectedBook, true) }) selectedBook = null
    }

    val inBook = remember(all, selectedBook) {
        val book = selectedBook
        if (book == null) all else all.filter { it.book.equals(book, true) }
    }
    val months = remember(inBook) {
        inBook.map { YearMonth.from(businessDateOf(it.txnDate)) }.distinct().sortedDescending()
    }
    LaunchedEffect(months) { if (month != null && month !in months) month = null }

    val filtered = remember(inBook, month, direction, query) {
        val q = query.trim()
        inBook.asSequence()
            .filter { month == null || YearMonth.from(businessDateOf(it.txnDate)) == month }
            .filter {
                when (direction) {
                    "C" -> it.isCredit()
                    "D" -> !it.isCredit()
                    else -> true
                }
            }
            .filter {
                q.isEmpty() || it.narration.contains(q, true) ||
                    (it.party?.contains(q, true) ?: false) ||
                    (it.reference?.contains(q, true) ?: false) ||
                    it.category.contains(q, true) ||
                    (it.mode?.contains(q, true) ?: false) ||
                    (it.bankName?.contains(q, true) ?: false)
            }
            .sortedWith(compareByDescending<BankTxnEntity> { it.txnDate }.thenByDescending { it.id })
            .toList()
    }

    Column(Modifier.fillMaxSize()) {
        PaneHeader(
            "Bank Book",
            if (all.isEmpty()) "STATEMENTS, UNDERSTOOD"
            else "${all.size} TRANSACTIONS · ${books.size} ${if (books.size == 1) "BOOK" else "BOOKS"}"
        ) {
            TransferActions(state, Section.BANK)
            Spacer(Modifier.width(10.dp))
            ImportStatementButton { wizardOpen = true }
        }

        if (all.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                EmptyState(
                    "ᛒ", "NO STATEMENTS YET",
                    "IMPORT STATEMENT reads Excel, CSV, PDF and more — every row lands sealed in the vault"
                )
            }
        } else {
            Row(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 32.dp)) {
                // ── Book rail ────────────────────────────────────────────────
                Column(Modifier.width(212.dp).fillMaxHeight()) {
                    BookRow("All books", all.size, selectedBook == null) { selectedBook = null }
                    Spacer(Modifier.height(4.dp))
                    LazyColumn(Modifier.weight(1f)) {
                        items(books, key = { it }) { book ->
                            val count = all.count { it.book.equals(book, true) }
                            BookRow(book, count, selectedBook?.equals(book, true) == true) {
                                selectedBook = book
                            }
                        }
                    }
                    selectedBook?.let { book ->
                        HorizontalDivider(color = GoldDark.copy(0.15f))
                        Row(Modifier.padding(vertical = 6.dp)) {
                            RailAction("RENAME") { renamingBook = book }
                            Spacer(Modifier.width(14.dp))
                            RailAction("DELETE", ExpenseRed) { deletingBook = book }
                        }
                    }
                }

                Spacer(Modifier.width(22.dp))

                // ── Content ──────────────────────────────────────────────────
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    SummaryTiles(filtered)
                    Spacer(Modifier.height(12.dp))
                    BankBalanceGraph(filtered)

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SearchField(query, { query = it }, "Search narration, payee, reference…", Modifier.weight(1f))
                        Spacer(Modifier.width(10.dp))
                        MonthPicker(months, month) { month = it }
                        Spacer(Modifier.width(10.dp))
                        DirectionChips(direction) { direction = it }
                    }
                    Spacer(Modifier.height(8.dp))
                    CategoryStrip(filtered)
                    Spacer(Modifier.height(6.dp))

                    if (filtered.isEmpty()) {
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text("Nothing matches the filter", color = TextMuted, fontSize = 12.sp)
                        }
                    } else {
                        LazyColumn(Modifier.weight(1f)) {
                            items(filtered, key = { it.id }) { txn ->
                                TxnRow(txn) { editing = txn }
                            }
                            item { Spacer(Modifier.height(20.dp)) }
                        }
                    }
                }
            }
        }
    }

    if (wizardOpen) {
        StatementImportWizard(
            state = state,
            defaultBook = selectedBook,
            onClose = { wizardOpen = false }
        )
    }
    editing?.let { txn ->
        BankTxnEditor(
            txn = txn,
            books = books,
            onSave = { state.upsertBankTxn(it); editing = null },
            onDelete = { state.deleteBankTxn(txn.id); editing = null },
            onCancel = { editing = null }
        )
    }
    renamingBook?.let { book ->
        RenameBookDialog(
            current = book,
            onRename = { newName ->
                state.renameBankBook(book, newName)
                if (selectedBook?.equals(book, true) == true) selectedBook = newName.trim()
                renamingBook = null
            },
            onDismiss = { renamingBook = null }
        )
    }
    deletingBook?.let { book ->
        ConfirmDelete(
            itemName = "$book — every transaction in this book",
            onConfirm = {
                state.deleteBankBook(book)
                deletingBook = null
            },
            onDismiss = { deletingBook = null }
        )
    }
}

// ── Pieces ───────────────────────────────────────────────────────────────────

@Composable
fun ImportStatementButton(onClick: () -> Unit) {
    Text(
        "IMPORT STATEMENT",
        color = BackgroundVoid, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Brush.linearGradient(listOf(GoldBright, GoldTarnished)))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    )
}

@Composable
private fun BookRow(name: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .rowSurface(selected)
            .clickable { onClick() }
            .padding(horizontal = 11.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(26.dp).clip(RoundedCornerShape(8.dp))
                .background(accentFor(name).copy(0.16f))
                .border(1.dp, accentFor(name).copy(0.4f), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text("ᛒ", color = accentFor(name), fontSize = 12.sp)
        }
        Spacer(Modifier.width(9.dp))
        Text(
            name,
            color = if (selected) GoldIce else TextParchment,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            modifier = Modifier.weight(1f)
        )
        Text("$count", color = TextMuted, fontSize = 10.sp)
    }
}

@Composable
private fun RailAction(label: String, color: Color = CyanGlow, onClick: () -> Unit) {
    Text(
        label, color = color, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
        modifier = Modifier.clip(RoundedCornerShape(7.dp)).clickable { onClick() }
            .padding(horizontal = 6.dp, vertical = 5.dp)
    )
}

@Composable
private fun SummaryTiles(txns: List<BankTxnEntity>) {
    val totalIn = txns.totalIn()
    val totalOut = txns.totalOut()
    val net = totalIn - totalOut
    Row(Modifier.fillMaxWidth()) {
        SummaryTile("MONEY IN", formatMoney(totalIn), IncomeGreen, Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        SummaryTile("MONEY OUT", formatMoney(totalOut), ExpenseRed, Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        SummaryTile(
            "NET FLOW",
            (if (net < 0) "−" else "+") + formatMoney(net),
            if (net < 0) ExpenseRed else CyanGlow,
            Modifier.weight(1f)
        )
    }
}

@Composable
private fun SummaryTile(label: String, value: String, accent: Color, modifier: Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Brush.verticalGradient(listOf(SurfaceGem, SurfaceStone)))
            .border(1.dp, accent.copy(0.25f), RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(label, color = accent, fontSize = 8.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(3.dp))
        Text(value, color = TextParchment, fontSize = 18.sp, fontWeight = FontWeight.Black)
    }
}

/**
 * The statement's own running balance, drawn over time. Unlike the ledger's
 * synthetic cumulative graph, these are the bank's true figures — so the graph
 * only draws when the imported rows actually carried a balance column.
 */
@Composable
private fun BankBalanceGraph(txns: List<BankTxnEntity>) {
    val points = remember(txns) {
        txns.filter { it.balance != null }
            .sortedWith(compareBy({ it.txnDate }, { it.id }))
            .map { it.txnDate to it.balance!! }
    }
    if (points.size < 2) return

    val minBalance = points.minOf { it.second }
    val maxBalance = points.maxOf { it.second }
    val tMin = points.first().first
    val tMax = points.last().first

    Column(
        Modifier.fillMaxWidth()
            .padding(bottom = 12.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Brush.verticalGradient(listOf(SurfaceGem, SurfaceStone)))
            .border(1.dp, CyanGlow.copy(0.18f), RoundedCornerShape(14.dp))
            .padding(14.dp)
    ) {
        Row {
            Text("BALANCE", color = CyanGlow, fontSize = 8.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text(
                "${formatMoney(minBalance)} – ${formatMoney(maxBalance)}",
                color = TextMuted, fontSize = 9.sp
            )
        }
        Spacer(Modifier.height(8.dp))
        Canvas(Modifier.fillMaxWidth().height(72.dp)) {
            val span = (tMax - tMin).toDouble()
            val range = (maxBalance - minBalance).takeIf { it > 0.0 } ?: 1.0
            fun xOf(t: Long, i: Int): Float =
                if (span > 0) ((t - tMin) / span * size.width).toFloat()
                else i.toFloat() / (points.size - 1) * size.width
            fun yOf(b: Double): Float =
                (size.height - ((b - minBalance) / range * size.height * 0.92 + size.height * 0.04)).toFloat()

            val path = Path()
            points.forEachIndexed { i, (t, b) ->
                val x = xOf(t, i)
                val y = yOf(b)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            // Zero line, when the balance dips through it.
            if (minBalance < 0 && maxBalance > 0) {
                val zeroY = yOf(0.0)
                drawLine(
                    ExpenseRed.copy(0.35f), Offset(0f, zeroY), Offset(size.width, zeroY),
                    strokeWidth = 1f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
                )
            }
            drawPath(path, CyanGlow, style = Stroke(width = 2f))
        }
    }
}

@Composable
private fun MonthPicker(months: List<YearMonth>, selected: YearMonth?, onSelect: (YearMonth?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(SurfaceStone)
                .border(1.dp, GoldDark.copy(0.25f), RoundedCornerShape(10.dp))
                .clickable { open = true }
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                selected?.format(monthLabel)?.uppercase() ?: "ALL MONTHS",
                color = if (selected != null) GoldIce else TextSubtle,
                fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp
            )
            Spacer(Modifier.width(6.dp))
            Text("▾", color = TextMuted, fontSize = 9.sp)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("All months", fontSize = 12.sp) },
                onClick = { onSelect(null); open = false }
            )
            months.forEach { m ->
                DropdownMenuItem(
                    text = { Text(m.format(monthLabel), fontSize = 12.sp) },
                    onClick = { onSelect(m); open = false }
                )
            }
        }
    }
}

private val monthLabel = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH)
private val rowDate = DateTimeFormatter.ofPattern("dd MMM", Locale.ENGLISH)
private val rowYear = DateTimeFormatter.ofPattern("yyyy", Locale.ENGLISH)

@Composable
private fun DirectionChips(selected: String?, onSelect: (String?) -> Unit) {
    Row {
        DirectionChip("ALL", selected == null, TextSubtle) { onSelect(null) }
        Spacer(Modifier.width(6.dp))
        DirectionChip("IN", selected == "C", IncomeGreen) { onSelect(if (selected == "C") null else "C") }
        Spacer(Modifier.width(6.dp))
        DirectionChip("OUT", selected == "D", ExpenseRed) { onSelect(if (selected == "D") null else "D") }
    }
}

@Composable
private fun DirectionChip(label: String, active: Boolean, color: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(CircleShape)
            .background(if (active) color.copy(0.16f) else SurfaceStone)
            .border(1.dp, if (active) color.copy(0.5f) else GoldDark.copy(0.2f), CircleShape)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Text(
            label, color = if (active) color else TextMuted,
            fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp
        )
    }
}

/** Where the money went this view: top categories by outflow, as tappable-free chips. */
@Composable
private fun CategoryStrip(txns: List<BankTxnEntity>) {
    val top = remember(txns) {
        txns.filterNot { it.isCredit() }
            .groupBy { it.category }
            .mapValues { (_, list) -> list.sumOf { it.amount } }
            .entries.sortedByDescending { it.value }
            .take(5)
    }
    if (top.isEmpty()) return
    val max = top.first().value.takeIf { it > 0 } ?: return
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        top.forEach { (category, spent) ->
            val weight = (spent / max).toFloat()
            Column(
                Modifier
                    .padding(end = 8.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(SurfaceStone)
                    .border(1.dp, accentFor(category).copy(0.3f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 11.dp, vertical = 7.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(category.uppercase(), color = accentFor(category), fontSize = 8.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(8.dp))
                    Text(formatMoney(spent), color = TextSubtle, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(4.dp))
                Box(Modifier.width(90.dp).height(3.dp).clip(CircleShape).background(SurfaceElevated)) {
                    Box(
                        Modifier.fillMaxWidth(weight.coerceIn(0.06f, 1f)).fillMaxHeight()
                            .clip(CircleShape).background(accentFor(category))
                    )
                }
            }
        }
    }
}

@Composable
private fun TxnRow(txn: BankTxnEntity, onClick: () -> Unit) {
    val tone = if (txn.isCredit()) IncomeGreen else ExpenseRed
    val date = businessDateOf(txn.txnDate)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .rowSurface(false)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(46.dp)) {
            Text(date.format(rowDate).uppercase(), color = TextSubtle, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            Text(date.format(rowYear), color = TextMuted, fontSize = 8.sp)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    txn.displayParty(),
                    color = TextParchment, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false)
                )
                txn.mode?.let {
                    Spacer(Modifier.width(8.dp))
                    Pill(it, CyanGlow)
                }
                if (txn.channel == "P2M") {
                    Spacer(Modifier.width(5.dp))
                    Pill("SHOP", GoldTarnished)
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                listOfNotNull(
                    txn.remark?.takeIf { it.isNotBlank() && !it.equals("NO REM", true) },
                    txn.narration.replace('\n', ' ')
                ).first().take(96),
                color = TextMuted, fontSize = 10.sp, maxLines = 1
            )
        }
        Spacer(Modifier.width(10.dp))
        Pill(txn.category.uppercase(), accentFor(txn.category))
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                (if (txn.isCredit()) "+" else "−") + formatMoney(txn.amount),
                color = tone, fontSize = 13.sp, fontWeight = FontWeight.Black
            )
            txn.balance?.let {
                Text("bal ${formatMoney(it)}", color = TextMuted, fontSize = 8.sp)
            }
        }
    }
}

// ── Editor & dialogs ─────────────────────────────────────────────────────────

/**
 * Edits the human fields. The narration is shown but not editable — it is the
 * imported record; what the user curates are the labels laid over it.
 */
@Composable
private fun BankTxnEditor(
    txn: BankTxnEntity,
    books: List<String>,
    onSave: (BankTxnEntity) -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit
) {
    var party by remember { mutableStateOf(txn.party ?: "") }
    var category by remember { mutableStateOf(txn.category) }
    var remark by remember { mutableStateOf(txn.remark ?: "") }
    var reference by remember { mutableStateOf(txn.reference ?: "") }
    var confirmDelete by remember { mutableStateOf(false) }

    EditorDialog(
        title = "Transaction",
        canSave = true,
        onSave = {
            onSave(
                txn.copy(
                    party = party.trim().ifEmpty { null },
                    category = category.trim().ifEmpty { com.nikhil.sentinelx.desktop.core.format.BankBook.CAT_OTHER },
                    remark = remark.trim().ifEmpty { null },
                    reference = reference.trim().ifEmpty { null }
                )
            )
        },
        onCancel = onCancel,
        onDelete = { confirmDelete = true },
        width = 620
    ) {
        val tone = if (txn.isCredit()) IncomeGreen else ExpenseRed
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
            Text(
                (if (txn.isCredit()) "+" else "−") + formatMoney(txn.amount),
                color = tone, fontSize = 22.sp, fontWeight = FontWeight.Black
            )
            Spacer(Modifier.width(12.dp))
            Pill(if (txn.isCredit()) "MONEY IN" else "MONEY OUT", tone)
            Spacer(Modifier.weight(1f))
            Text(
                businessDateOf(txn.txnDate).format(DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH)),
                color = TextSubtle, fontSize = 12.sp, fontWeight = FontWeight.Bold
            )
        }

        // The record itself, untouchable.
        Column(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(SurfaceStone)
                .border(1.dp, GoldDark.copy(0.2f), RoundedCornerShape(10.dp))
                .padding(12.dp)
        ) {
            Text("STATEMENT NARRATION", color = TextMuted, fontSize = 7.sp, letterSpacing = 2.sp)
            Spacer(Modifier.height(4.dp))
            Text(txn.narration, color = TextSubtle, fontSize = 11.sp, lineHeight = 16.sp)
            txn.balance?.let {
                Spacer(Modifier.height(6.dp))
                Text("Balance after: ${formatMoney(it)}", color = TextMuted, fontSize = 10.sp)
            }
        }
        Spacer(Modifier.height(14.dp))

        EditorField(party, { party = it }, "Payee / Party", placeholder = "Who this was with")
        EditorField(category, { category = it }, "Category", placeholder = "Food, Travel, Rent…")
        EditorField(remark, { remark = it }, "Remark", placeholder = "Your note")
        EditorField(reference, { reference = it }, "Reference / UTR", accent = CyanGlow)
    }

    if (confirmDelete) {
        ConfirmDelete(
            itemName = txn.displayParty(),
            onConfirm = { confirmDelete = false; onDelete() },
            onDismiss = { confirmDelete = false }
        )
    }
}

@Composable
private fun RenameBookDialog(current: String, onRename: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(current) }
    EditorDialog(
        title = "Rename book",
        canSave = name.trim().isNotEmpty() && !name.trim().equals(current, true),
        onSave = { onRename(name) },
        onCancel = onDismiss,
        width = 430
    ) {
        Text(
            "Every transaction in \"$current\" moves with the name. Imported fingerprints stay valid.",
            color = TextMuted, fontSize = 11.sp, lineHeight = 16.sp,
            modifier = Modifier.padding(bottom = 12.dp)
        )
        EditorField(name, { name = it }, "Book name")
    }
}