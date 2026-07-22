package com.nikhil.sentinelx.desktop.ui.panes

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nikhil.sentinelx.desktop.core.format.AccountEntity
import com.nikhil.sentinelx.desktop.core.format.CsvExport
import com.nikhil.sentinelx.desktop.core.format.TransactionEntity
import com.nikhil.sentinelx.desktop.ui.AppState
import com.nikhil.sentinelx.desktop.ui.components.*
import com.nikhil.sentinelx.desktop.ui.theme.*
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

private val money: NumberFormat = NumberFormat.getNumberInstance(Locale.forLanguageTag("en-IN")).apply {
    maximumFractionDigits = 2
    minimumFractionDigits = 2
}
private val shortDate = SimpleDateFormat("d MMM", Locale.getDefault())

fun formatMoney(amount: Double): String = "₹${money.format(abs(amount))}"

/**
 * Finance. Accounts down the left, transactions and analytics on the right.
 *
 * The phone shows one account's ledger at a time behind a navigation step. Here the
 * account list, running balance, category breakdown and full history are on screen
 * together, which is the difference between recording spending and understanding it.
 */
@Composable
fun LedgerPane(state: AppState) {
    var query by remember { mutableStateOf("") }
    var accountId by remember { mutableStateOf<Long?>(null) }

    val accounts = state.backup.accounts
    val allTx = state.backup.ledger

    // Null means "all accounts" — a view the phone cannot show at all.
    val active = accountId
    val transactions = remember(allTx, active, query) {
        allTx.filter { tx ->
            (active == null || tx.accountId == active) &&
                (query.isBlank() || tx.title.contains(query, true) || tx.category.contains(query, true))
        }.sortedByDescending { it.timestamp }
    }

    var editingTx by remember { mutableStateOf<TransactionEntity?>(null) }
    var creatingTx by remember { mutableStateOf(false) }
    var editingAccount by remember { mutableStateOf<AccountEntity?>(null) }
    var creatingAccount by remember { mutableStateOf(false) }
    var exportCsv by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        PaneHeader("Ledger", "${allTx.size} transactions across ${accounts.size} accounts") {
            if (transactions.isNotEmpty()) {
                TextButton(onClick = { exportCsv = true }) {
                    Text("EXPORT CSV", color = TextSubtle, fontSize = 11.sp, letterSpacing = 1.sp)
                }
            }
            TextButton(onClick = { creatingAccount = true }) {
                Text("+ ACCOUNT", color = CyanGlow, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
        }

        Row(Modifier.fillMaxSize()) {
            Column(
                Modifier.width(276.dp).fillMaxHeight()
                    .background(BackgroundVoid.copy(0.5f))
                    .padding(horizontal = 16.dp)
            ) {
                AccountRow(
                    name = "All Accounts",
                    balance = allTx.netBalance(),
                    selected = active == null,
                    accent = GoldTarnished
                ) { accountId = null }

                Spacer(Modifier.height(6.dp))
                HorizontalDivider(color = GoldDark.copy(0.15f))
                Spacer(Modifier.height(6.dp))

                LazyColumn(Modifier.fillMaxSize()) {
                    items(accounts, key = { it.id }) { account ->
                        AccountRow(
                            name = account.name,
                            balance = allTx.filter { it.accountId == account.id }.netBalance(),
                            selected = active == account.id,
                            accent = account.color(),
                            onEdit = { editingAccount = account }
                        ) { accountId = account.id }
                    }
                    item { Spacer(Modifier.height(20.dp)) }
                }
            }

            Column(Modifier.weight(1f).fillMaxHeight().padding(24.dp)) {
                if (allTx.isEmpty()) {
                    EmptyState("ᚢ", "NO TRANSACTIONS", "Import a Migration Seal to begin")
                } else {
                    LedgerSummary(transactions)
                    Spacer(Modifier.height(16.dp))
                    SearchField(query, { query = it }, "Search transactions")
                    Spacer(Modifier.height(12.dp))
                    if (transactions.isEmpty()) {
                        EmptyState("ᚢ", "NO MATCHES", "Try a different search")
                    } else {
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(transactions, key = { it.id }) { tx ->
                                TransactionRow(tx) { editingTx = tx }
                            }
                            item { Spacer(Modifier.height(20.dp)) }
                        }
                    }
                }
            }
        }
    }

        if (accounts.isNotEmpty()) {
            Box(Modifier.align(Alignment.BottomEnd).padding(28.dp)) {
                AddButton(onClick = { creatingTx = true })
            }
        }
    }

    if (creatingTx) TransactionEditor(state, null, active) { creatingTx = false }
    editingTx?.let { t -> TransactionEditor(state, t, active) { editingTx = null } }
    if (exportCsv) {
        CsvExportDialog(transactions, accounts) { exportCsv = false }
    }
    if (creatingAccount) AccountEditor(state, null) { creatingAccount = false }
    editingAccount?.let { a -> AccountEditor(state, a) { editingAccount = null } }
}


/**
 * Confirms a CSV export, stating plainly that the file is NOT encrypted.
 *
 * Everything else this app writes is sealed. Letting a user drop their entire
 * financial history into Downloads as readable text without saying so would be a
 * failure of the software, not of the user.
 */
@Composable
private fun CsvExportDialog(
    transactions: List<TransactionEntity>,
    accounts: List<AccountEntity>,
    onClose: () -> Unit
) {
    var done by remember { mutableStateOf<String?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onClose,
        containerColor = BackgroundDeep,
        shape = RoundedCornerShape(18.dp),
        title = {
            Text(
                if (done == null) "EXPORT CSV" else "EXPORTED",
                color = GoldTarnished, fontWeight = FontWeight.Bold, fontSize = 14.sp, letterSpacing = 2.sp
            )
        },
        text = {
            Column(Modifier.width(400.dp)) {
                if (done != null) {
                    Text("Saved to:", color = TextSubtle, fontSize = 12.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(done!!, color = GoldIce, fontSize = 11.sp)
                } else {
                    Text(
                        "${transactions.size} transaction(s) will be written as plain text.",
                        color = TextSubtle, fontSize = 12.sp
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "\u26a0 This file is NOT encrypted. Anyone with access to it can read " +
                            "your full transaction history. Only the ledger is exportable \u2014 " +
                            "passwords and card secrets are never written to CSV.",
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
                TextButton(onClick = onClose) { Text("DONE", color = CyanGlow, fontWeight = FontWeight.Bold) }
            } else {
                TextButton(onClick = {
                    val dialog = FileDialog(null as Frame?, "Save CSV", FileDialog.SAVE)
                    dialog.file = "sentinelx_ledger_${System.currentTimeMillis()}.csv"
                    dialog.isVisible = true
                    val dir = dialog.directory
                    val name = dialog.file
                    if (dir != null && name != null) {
                        val target = File(dir, if (name.endsWith(".csv")) name else "$name.csv")
                        runCatching { CsvExport.ledger(target, transactions, accounts) }
                            .onSuccess { done = target.absolutePath }
                            .onFailure { failure = it.message ?: "Could not write the file." }
                    }
                }) {
                    Text("CHOOSE LOCATION\u2026", color = CyanGlow, fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            if (done == null) TextButton(onClick = onClose) { Text("CANCEL", color = TextMuted) }
        }
    )
}

private fun List<TransactionEntity>.netBalance(): Double =
    sumOf { if (it.isIncoming) it.amount else -it.amount }

private fun AccountEntity.color(): Color = runCatching {
    Color(colorHex.removePrefix("#").toLong(16) or 0xFF000000)
}.getOrDefault(GoldTarnished)

@Composable
private fun AccountRow(
    name: String,
    balance: Double,
    selected: Boolean,
    accent: Color,
    onEdit: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp)
            .clip(RoundedCornerShape(12.dp))
            .rowSurface(selected)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(accent))
        Spacer(Modifier.width(10.dp))
        Text(
            name,
            color = if (selected) GoldIce else TextParchment,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            modifier = Modifier.weight(1f),
            maxLines = 1
        )
        Text(
            formatMoney(balance),
            color = if (balance < 0) ExpenseRed else IncomeGreen,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
        if (onEdit != null && selected) {
            Spacer(Modifier.width(6.dp))
            Text(
                "✎",
                color = TextMuted,
                fontSize = 12.sp,
                modifier = Modifier.clickable { onEdit() }
            )
        }
    }
}

@Composable
private fun LedgerSummary(transactions: List<TransactionEntity>) {
    val income = transactions.filter { it.isIncoming }.sumOf { it.amount }
    val expense = transactions.filter { !it.isIncoming }.sumOf { it.amount }
    val net = income - expense

    Row(Modifier.fillMaxWidth()) {
        SummaryTile("NET", net, if (net < 0) ExpenseRed else IncomeGreen, Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        SummaryTile("IN", income, IncomeGreen, Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        SummaryTile("OUT", expense, ExpenseRed, Modifier.weight(1f))
    }

    // Category breakdown across the visible transactions — the phone's Wealth Vision
    // is cramped into a phone width; here the bars are actually comparable.
    val byCategory = transactions.filter { !it.isIncoming }
        .groupBy { it.category.ifBlank { "MISC" } }
        .mapValues { (_, txs) -> txs.sumOf { it.amount } }
        .entries.sortedByDescending { it.value }
        .take(6)

    if (byCategory.isNotEmpty() && expense > 0) {
        Spacer(Modifier.height(14.dp))
        GemCard(accent = AmberWarn, modifier = Modifier.fillMaxWidth()) {
            Text(
                "SPENDING BY CATEGORY",
                color = AmberWarn, fontSize = 9.sp,
                letterSpacing = 2.sp, fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(12.dp))
            byCategory.forEach { (category, amount) ->
                val fraction = (amount / expense).toFloat().coerceIn(0f, 1f)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 7.dp)) {
                    Text(category, color = TextSubtle, fontSize = 11.sp, modifier = Modifier.width(110.dp))
                    Box(
                        Modifier.weight(1f).height(7.dp)
                            .clip(RoundedCornerShape(4.dp)).background(SurfaceElevated)
                    ) {
                        Box(
                            Modifier.fillMaxWidth(fraction).fillMaxHeight()
                                .clip(RoundedCornerShape(4.dp))
                                .background(Brush.horizontalGradient(listOf(AmberWarn, GoldTarnished)))
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        formatMoney(amount),
                        color = TextParchment, fontSize = 11.sp,
                        modifier = Modifier.width(96.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SummaryTile(label: String, amount: Double, accent: Color, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(14.dp))
            .background(Brush.verticalGradient(listOf(SurfaceGem, SurfaceStone)))
            .padding(16.dp)
    ) {
        Text(label, color = TextMuted, fontSize = 8.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(5.dp))
        Text(formatMoney(amount), color = accent, fontSize = 19.sp, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun TransactionRow(tx: TransactionEntity, onEdit: () -> Unit) {
    val accent = if (tx.isIncoming) IncomeGreen else ExpenseRed
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp)
            .clip(RoundedCornerShape(10.dp))
            .rowSurface(false)
            .background(SurfaceStone.copy(0.35f))
            .clickable { onEdit() }
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(30.dp).clip(CircleShape).background(accent.copy(0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Text(if (tx.isIncoming) "+" else "−", color = accent, fontSize = 15.sp, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(tx.title.ifBlank { "Untitled" }, color = TextParchment, fontSize = 13.sp, maxLines = 1)
            Text(
                "${tx.category} · ${shortDate.format(Date(tx.timestamp))}",
                color = TextMuted, fontSize = 10.sp
            )
        }
        if (tx.isSettled) {
            Pill("SETTLED", CyanMuted)
            Spacer(Modifier.width(8.dp))
        }
        Text(
            (if (tx.isIncoming) "+" else "−") + formatMoney(tx.amount),
            color = accent, fontSize = 13.sp, fontWeight = FontWeight.Bold
        )
    }
}
