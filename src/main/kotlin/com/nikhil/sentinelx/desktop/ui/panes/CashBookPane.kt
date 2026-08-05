package com.nikhil.sentinelx.desktop.ui.panes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nikhil.sentinelx.desktop.core.format.*
import com.nikhil.sentinelx.desktop.ui.AppState
import com.nikhil.sentinelx.desktop.ui.components.*
import com.nikhil.sentinelx.desktop.ui.theme.*
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

private val monthLabel = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)
private val monthShort = DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH)
private val dayLabel = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
private val weekday = DateTimeFormatter.ofPattern("EEEE", Locale.ENGLISH)

/**
 * The daily cash handover, and an ordinary balance sheet when that is all it needs to be.
 *
 * Months down the left, days on the right. A day is one card holding both halves of the
 * ritual side by side — what came home last night, what went back this morning — because
 * the pair is the unit that gets checked, not the individual entry. Anything else that
 * day (a mid-afternoon pickup, a plain ledger line) sits underneath.
 */
@Composable
fun CashBookPane(state: AppState) {
    val all = state.backup.cashBook

    val months = remember(all) {
        all.map { YearMonth.from(businessDateOf(it.entryDate)) }
            .distinct()
            .sortedDescending()
    }
    var month by remember(months) { mutableStateOf(months.firstOrNull()) }
    var query by remember { mutableStateOf("") }

    val scoped = remember(all, month) {
        if (month == null) all else all.filter { YearMonth.from(businessDateOf(it.entryDate)) == month }
    }
    val visible = remember(scoped, query) {
        if (query.isBlank()) scoped else scoped.filter { entry ->
            entry.particulars.contains(query, true) ||
                entry.countedBy.contains(query, true) ||
                entry.verifiedBy.contains(query, true) ||
                entry.notes.orEmpty().contains(query, true) ||
                businessDateOf(entry.entryDate).format(dayLabel).contains(query, true)
        }
    }

    val days = remember(visible) {
        visible.groupBy { it.entryDate }
            .toSortedMap(compareByDescending { it })
            .map { (date, entries) -> DayGroup(date, entries) }
    }

    // Opening balance for the scoped window: everything that happened before it. Without
    // this the running balance restarts at zero each month and reads as though the cash
    // appeared from nowhere on the first.
    val opening = remember(all, month, days) {
        val earliest = days.minOfOrNull { it.date }
        if (earliest == null) 0.0 else all.filter { it.entryDate < earliest }.netPosition()
    }

    var editing by remember { mutableStateOf<CashEntryEntity?>(null) }
    var creating by remember { mutableStateOf<NewEntrySeed?>(null) }
    var exporting by remember { mutableStateOf(false) }
    var viewingSlips by remember { mutableStateOf<List<String>?>(null) }

    val pending = scoped.count { !it.isVerified() }
    val mismatches = scoped.count { !it.isReconciled() }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            PaneHeader(
                "Cash Book",
                "${all.size} entries · ${months.size} month${if (months.size == 1) "" else "s"}"
            ) {
                if (scoped.isNotEmpty()) {
                    TextButton(onClick = { exporting = true }) {
                        Text("EXPORT", color = TextSubtle, fontSize = 11.sp, letterSpacing = 1.sp)
                    }
                }
                TextButton(onClick = { creating = NewEntrySeed(todayBusinessDate(), CashBook.SLOT_EVENING) }) {
                    Text(
                        "+ ENTRY", color = CyanGlow, fontSize = 11.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = 1.sp
                    )
                }
            }

            Row(Modifier.fillMaxSize()) {
                // ── Month rail ───────────────────────────────────────────────
                Column(
                    Modifier.width(276.dp).fillMaxHeight()
                        .background(BackgroundVoid.copy(0.5f))
                        .padding(horizontal = 16.dp)
                ) {
                    MonthRow(
                        label = "All time",
                        balance = all.netPosition(),
                        entries = all.size,
                        selected = month == null
                    ) { month = null }

                    Spacer(Modifier.height(6.dp))
                    HorizontalDivider(color = GoldDark.copy(0.15f))
                    Spacer(Modifier.height(6.dp))

                    LazyColumn(Modifier.fillMaxSize()) {
                        items(months, key = { it.toString() }) { candidate ->
                            val inMonth = all.filter {
                                YearMonth.from(businessDateOf(it.entryDate)) == candidate
                            }
                            MonthRow(
                                label = candidate.format(monthShort),
                                balance = inMonth.netPosition(),
                                entries = inMonth.size,
                                selected = month == candidate,
                                unverified = inMonth.count { !it.isVerified() }
                            ) { month = candidate }
                        }
                        item { Spacer(Modifier.height(20.dp)) }
                    }
                }

                // ── Days ─────────────────────────────────────────────────────
                Column(Modifier.weight(1f).fillMaxHeight().padding(24.dp)) {
                    if (all.isEmpty()) {
                        EmptyState(
                            "ᛃ",
                            "NO CASH ENTRIES",
                            "Record what came home tonight and what goes back tomorrow"
                        )
                    } else {
                        CashSummaryTiles(scoped, opening)
                        Spacer(Modifier.height(12.dp))

                        if (pending > 0 || mismatches > 0) {
                            AttentionStrip(pending, mismatches)
                            Spacer(Modifier.height(12.dp))
                        }

                        SearchField(query, { query = it }, "Search particulars, people or dates")
                        Spacer(Modifier.height(12.dp))

                        if (days.isEmpty()) {
                            EmptyState("ᛃ", "NO MATCHES", "Try a different search")
                        } else {
                            // weight(1f), never fillMaxSize(): an unweighted "fill" child
                            // placed after the tiles is handed the whole column height and
                            // renders below the fold. The Ledger shipped that bug once.
                            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                                items(days, key = { it.date }) { day ->
                                    DayCard(
                                        day = day,
                                        state = state,
                                        onEdit = { editing = it },
                                        onAdd = { seed -> creating = seed },
                                        onViewSlips = { viewingSlips = it }
                                    )
                                }
                                item {
                                    if (month != null) {
                                        NoteInventoryCard(scoped)
                                        Spacer(Modifier.height(12.dp))
                                    }
                                    Spacer(Modifier.height(20.dp))
                                }
                            }
                        }
                    }
                }
            }
        }

        Box(Modifier.align(Alignment.BottomEnd).padding(28.dp)) {
            AddButton(onClick = { creating = NewEntrySeed(todayBusinessDate(), CashBook.SLOT_EVENING) })
        }
    }

    creating?.let { seed ->
        CashEntryEditor(
            state = state,
            existing = null,
            defaultDate = seed.date,
            defaultSlot = seed.slot,
            seedDenominations = seed.denominations
        ) { creating = null }
    }
    editing?.let { entry ->
        CashEntryEditor(state, entry, entry.entryDate, entry.slot) { editing = null }
    }
    if (exporting) {
        CashBookExportDialog(
            state = state,
            entries = scoped,
            title = month?.format(monthLabel)?.let { "Cash Book — $it" } ?: "Cash Book — all entries"
        ) { exporting = false }
    }
    viewingSlips?.let { names ->
        SlipViewer(state, names) { viewingSlips = null }
    }
}

private data class DayGroup(val date: Long, val entries: List<CashEntryEntity>)

/** What a "+ add" button should prefill the editor with. */
private data class NewEntrySeed(
    val date: Long,
    val slot: String,
    val denominations: Map<Int, Int> = emptyMap()
)

// ── Month rail ───────────────────────────────────────────────────────────────

@Composable
private fun MonthRow(
    label: String,
    balance: Double,
    entries: Int,
    selected: Boolean,
    unverified: Int = 0,
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
        Column(Modifier.weight(1f)) {
            Text(
                label,
                color = if (selected) GoldIce else TextParchment,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium
            )
            Text("$entries entries", color = TextMuted, fontSize = 9.sp)
        }
        if (unverified > 0) {
            Box(
                Modifier.clip(CircleShape).background(AmberWarn.copy(0.16f))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text("$unverified", color = AmberWarn, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(8.dp))
        }
        Text(
            formatMoney(balance),
            color = when {
                abs(balance) < CashBook.EPSILON -> TextMuted
                balance < 0 -> ExpenseRed
                else -> IncomeGreen
            },
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

// ── Summary ──────────────────────────────────────────────────────────────────

/**
 * The three figures the whole book exists to produce, pinned above the scroll.
 *
 * A traditional cash book strikes these at the foot; on a screen you want them where
 * they stay visible while you read, and the printed statement puts them back at the
 * bottom where a reader expects them.
 */
@Composable
private fun CashSummaryTiles(entries: List<CashEntryEntity>, opening: Double) {
    val debit = entries.totalDebit()
    val credit = entries.totalCredit()
    val closing = opening + entries.netPosition()

    Row(Modifier.fillMaxWidth()) {
        CashTile("TOTAL DEBIT", debit, "received", IncomeGreen, Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        CashTile("TOTAL CREDIT", credit, "paid out", ExpenseRed, Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        CashTile(
            "CLOSING BALANCE",
            closing,
            if (opening != 0.0) "opened at ${formatMoney(opening)}" else "in hand",
            if (closing < 0) ExpenseRed else GoldIce,
            Modifier.weight(1f)
        )
    }
}

@Composable
private fun CashTile(
    label: String,
    amount: Double,
    hint: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    Column(
        modifier.clip(RoundedCornerShape(14.dp))
            .background(Brush.verticalGradient(listOf(SurfaceGem, SurfaceStone)))
            .padding(16.dp)
    ) {
        Text(label, color = TextMuted, fontSize = 8.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(5.dp))
        Text(formatMoney(amount), color = accent, fontSize = 19.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.height(2.dp))
        Text(hint, color = TextMuted, fontSize = 9.sp)
    }
}

@Composable
private fun AttentionStrip(pending: Int, mismatches: Int) {
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(AmberWarn.copy(0.07f))
            .border(1.dp, AmberWarn.copy(0.25f), RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("⚠", color = AmberWarn, fontSize = 13.sp)
        Spacer(Modifier.width(10.dp))
        if (pending > 0) {
            Text(
                "$pending awaiting verification",
                color = AmberWarn, fontSize = 11.sp, fontWeight = FontWeight.Medium
            )
        }
        if (pending > 0 && mismatches > 0) {
            Text(" · ", color = TextMuted, fontSize = 11.sp)
        }
        if (mismatches > 0) {
            Text(
                "$mismatches where the notes don't match the amount",
                color = ExpenseRed, fontSize = 11.sp, fontWeight = FontWeight.Medium
            )
        }
    }
}

// ── Day card ─────────────────────────────────────────────────────────────────

@Composable
private fun DayCard(
    day: DayGroup,
    state: AppState,
    onEdit: (CashEntryEntity) -> Unit,
    onAdd: (NewEntrySeed) -> Unit,
    onViewSlips: (List<String>) -> Unit
) {
    val date = businessDateOf(day.date)
    val evening = day.entries.firstOrNull { it.slot == CashBook.SLOT_EVENING }
    val morning = day.entries.firstOrNull { it.slot == CashBook.SLOT_MORNING }
    val others = day.entries.filter {
        it.slot != CashBook.SLOT_EVENING && it.slot != CashBook.SLOT_MORNING
    } + day.entries.filter { it.slot == CashBook.SLOT_EVENING }.drop(1) +
        day.entries.filter { it.slot == CashBook.SLOT_MORNING }.drop(1)

    val net = day.entries.netPosition()
    val settled = abs(net) < CashBook.EPSILON
    val allVerified = day.entries.all { it.isVerified() }
    val accent = when {
        day.entries.any { !it.isReconciled() } -> ExpenseRed
        !allVerified -> AmberWarn
        else -> IncomeGreen
    }

    Column(Modifier.padding(bottom = 12.dp)) {
        GemCard(accent = accent, modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text(
                        date.format(dayLabel),
                        color = GoldIce, fontSize = 15.sp,
                        fontWeight = FontWeight.Bold, fontFamily = FontFamily.Serif
                    )
                    Text(date.format(weekday), color = TextMuted, fontSize = 9.sp, letterSpacing = 1.sp)
                }
                Spacer(Modifier.weight(1f))
                if (allVerified) Pill("VERIFIED", IncomeGreen) else Pill("PENDING", AmberWarn)
                Spacer(Modifier.width(8.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        if (settled) "SETTLED" else "LEFT AT HOME",
                        color = TextMuted, fontSize = 8.sp, letterSpacing = 1.5.sp
                    )
                    Text(
                        formatMoney(net),
                        color = if (settled) TextSubtle else GoldTarnished,
                        fontSize = 15.sp, fontWeight = FontWeight.Black
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            Row(Modifier.fillMaxWidth()) {
                Box(Modifier.weight(1f)) {
                    if (evening != null) {
                        HalfSlot(evening, "EVENING", "Brought home", state, onEdit, onViewSlips)
                    } else {
                        // Carrying the morning's tally back into an unrecorded evening is
                        // rarely right, so the empty evening starts blank.
                        EmptySlot("EVENING", "Nothing recorded for tonight") {
                            onAdd(NewEntrySeed(day.date, CashBook.SLOT_EVENING))
                        }
                    }
                }
                Spacer(Modifier.width(12.dp))
                Box(Modifier.weight(1f)) {
                    if (morning != null) {
                        HalfSlot(morning, "MORNING", "Taken to office", state, onEdit, onViewSlips)
                    } else {
                        // The money that went home almost always goes back untouched, so
                        // the empty morning offers last night's exact tally in one click.
                        // Retyping 120 notes because the app couldn't guess is the kind of
                        // friction that gets a tool abandoned by the second week.
                        EmptySlot(
                            "MORNING",
                            if (evening != null) "Carry back ${formatMoney(evening.amount)}"
                            else "Nothing recorded for this morning"
                        ) {
                            onAdd(
                                NewEntrySeed(
                                    date = day.date,
                                    slot = CashBook.SLOT_MORNING,
                                    denominations = evening?.denominationCounts() ?: emptyMap()
                                )
                            )
                        }
                    }
                }
            }

            if (others.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                others.forEach { entry ->
                    OtherEntryRow(entry) { onEdit(entry) }
                }
            }
        }
    }
}

@Composable
private fun HalfSlot(
    entry: CashEntryEntity,
    label: String,
    hint: String,
    state: AppState,
    onEdit: (CashEntryEntity) -> Unit,
    onViewSlips: (List<String>) -> Unit
) {
    val accent = if (entry.isIn()) IncomeGreen else ExpenseRed
    val counts = entry.denominationCounts()
    val slips = entry.slipFilenames()

    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceStone.copy(0.4f))
            .border(1.dp, accent.copy(0.18f), RoundedCornerShape(12.dp))
            .clickable { onEdit(entry) }
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = accent, fontSize = 8.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text(
                (if (entry.isIn()) "+" else "−") + formatMoney(entry.amount),
                color = accent, fontSize = 15.sp, fontWeight = FontWeight.Black
            )
        }
        Text(hint, color = TextMuted, fontSize = 9.sp)

        if (counts.isNotEmpty()) {
            Spacer(Modifier.height(9.dp))
            DenominationChips(counts, TextSubtle)
        }

        if (!entry.isReconciled()) {
            Spacer(Modifier.height(8.dp))
            val diff = entry.reconciliationDifference()
            Text(
                "Notes ${if (diff < 0) "short" else "over"} by ${formatMoney(diff)} " +
                    "— counted ${formatMoney(entry.countedTotal())}",
                color = ExpenseRed, fontSize = 10.sp, fontWeight = FontWeight.Bold
            )
        }

        if (entry.particulars.isNotBlank()) {
            Spacer(Modifier.height(7.dp))
            Text(entry.particulars, color = TextSubtle, fontSize = 11.sp, maxLines = 2)
        }

        Spacer(Modifier.height(9.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                if (entry.countedBy.isNotBlank()) {
                    Text("Counted by ${entry.countedBy}", color = TextMuted, fontSize = 9.sp)
                }
                Text(
                    if (entry.verifiedBy.isNotBlank()) "Verified by ${entry.verifiedBy}"
                    else "Not verified",
                    color = if (entry.isVerified()) IncomeGreen else AmberWarn,
                    fontSize = 9.sp
                )
            }
            if (slips.isNotEmpty()) {
                Row(
                    Modifier.clip(RoundedCornerShape(7.dp))
                        .background(CyanGlow.copy(0.08f))
                        .clickable { onViewSlips(slips) }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("◈", color = CyanGlow, fontSize = 10.sp)
                    Spacer(Modifier.width(5.dp))
                    Text(
                        "${slips.size} slip${if (slips.size == 1) "" else "s"}",
                        color = CyanGlow, fontSize = 9.sp, fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptySlot(label: String, hint: String, onAdd: () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceStone.copy(0.16f))
            .border(1.dp, GoldDark.copy(0.18f), RoundedCornerShape(12.dp))
            .clickable { onAdd() }
            .padding(14.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text(label, color = TextMuted, fontSize = 8.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Text("+ Record", color = GoldTarnished, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(3.dp))
        Text(hint, color = TextMuted, fontSize = 9.sp)
    }
}

@Composable
private fun OtherEntryRow(entry: CashEntryEntity, onEdit: () -> Unit) {
    val accent = if (entry.isIn()) IncomeGreen else ExpenseRed
    Row(
        Modifier.fillMaxWidth().padding(top = 5.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(SurfaceStone.copy(0.25f))
            .clickable { onEdit() }
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(22.dp).clip(CircleShape).background(accent.copy(0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Text(if (entry.isIn()) "+" else "−", color = accent, fontSize = 12.sp, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.width(10.dp))
        Text(
            entry.particulars.ifBlank { "Other movement" },
            color = TextSubtle, fontSize = 11.sp, modifier = Modifier.weight(1f), maxLines = 1
        )
        if (!entry.isVerified()) {
            Pill("PENDING", AmberWarn)
            Spacer(Modifier.width(8.dp))
        }
        Text(
            (if (entry.isIn()) "+" else "−") + formatMoney(entry.amount),
            color = accent, fontSize = 12.sp, fontWeight = FontWeight.Bold
        )
    }
}

// ── Note inventory ───────────────────────────────────────────────────────────

/**
 * What is physically still in the house, note by note — every denomination that came in
 * less every one that went out. Paper slips cannot produce this, and it is the fastest
 * possible check against the actual pile of cash.
 */
@Composable
private fun NoteInventoryCard(entries: List<CashEntryEntity>) {
    val inventory = remember(entries) { entries.noteInventory() }
    if (inventory.isEmpty()) return

    val total = inventory.entries.sumOf { (denomination, count) -> denomination.toDouble() * count }

    GemCard(accent = GoldIce, modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "NOTES STILL HELD",
                color = GoldIce, fontSize = 9.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.weight(1f))
            Text(formatMoney(total), color = GoldIce, fontSize = 14.sp, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.height(12.dp))
        inventory.forEach { (denomination, count) ->
            Row(
                Modifier.fillMaxWidth().padding(bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("₹$denomination", color = TextSubtle, fontSize = 11.sp, modifier = Modifier.width(60.dp))
                Text("×", color = TextMuted, fontSize = 10.sp)
                Spacer(Modifier.width(8.dp))
                Text(
                    "$count",
                    color = TextParchment, fontSize = 12.sp,
                    fontWeight = FontWeight.Bold, modifier = Modifier.width(48.dp)
                )
                Text(
                    formatMoney(denomination.toDouble() * count),
                    color = TextMuted, fontSize = 11.sp, modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

// ── Slip viewer ──────────────────────────────────────────────────────────────

@Composable
private fun SlipViewer(state: AppState, names: List<String>, onClose: () -> Unit) {
    var index by remember { mutableStateOf(0) }
    val safe = index.coerceIn(0, names.lastIndex)

    AlertDialog(
        onDismissRequest = onClose,
        containerColor = BackgroundDeep,
        shape = RoundedCornerShape(18.dp),
        title = {
            Text(
                "SLIP ${safe + 1} OF ${names.size}",
                color = GoldTarnished, fontSize = 13.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 2.sp
            )
        },
        text = {
            Column(Modifier.width(560.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                VaultImage(
                    fileName = names[safe],
                    loader = state::readImage,
                    modifier = Modifier.fillMaxWidth().height(460.dp).clip(RoundedCornerShape(12.dp))
                )
                if (names.size > 1) {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { index = (safe - 1 + names.size) % names.size }) {
                            Text("◀ PREV", color = CyanGlow, fontSize = 11.sp)
                        }
                        Spacer(Modifier.width(16.dp))
                        TextButton(onClick = { index = (safe + 1) % names.size }) {
                            Text("NEXT ▶", color = CyanGlow, fontSize = 11.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onClose) {
                Text("CLOSE", color = CyanGlow, fontWeight = FontWeight.Bold)
            }
        }
    )
}
