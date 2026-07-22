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
import com.nikhil.sentinelx.desktop.core.format.TransactionEntity
import com.nikhil.sentinelx.desktop.ui.AppState
import com.nikhil.sentinelx.desktop.ui.components.*
import com.nikhil.sentinelx.desktop.ui.theme.*
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

    Column(Modifier.fillMaxSize()) {
        PaneHeader("Ledger", "${allTx.size} transactions across ${accounts.size} accounts")

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
                            accent = account.color()
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
                            items(transactions, key = { it.id }) { TransactionRow(it) }
                            item { Spacer(Modifier.height(20.dp)) }
                        }
                    }
                }
            }
        }
    }
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
    onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) GoldDim.copy(0.3f) else Color.Transparent)
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
private fun TransactionRow(tx: TransactionEntity) {
    val accent = if (tx.isIncoming) IncomeGreen else ExpenseRed
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(SurfaceStone.copy(0.5f))
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
