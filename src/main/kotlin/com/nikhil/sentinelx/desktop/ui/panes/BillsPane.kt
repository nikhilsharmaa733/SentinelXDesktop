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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nikhil.sentinelx.desktop.core.format.BillEntity
import com.nikhil.sentinelx.desktop.core.format.Bills
import com.nikhil.sentinelx.desktop.core.format.billFilenames
import com.nikhil.sentinelx.desktop.core.format.businessDateOf
import com.nikhil.sentinelx.desktop.core.format.isOverdue
import com.nikhil.sentinelx.desktop.core.format.isPaid
import com.nikhil.sentinelx.desktop.core.format.toBusinessDate
import com.nikhil.sentinelx.desktop.core.format.todayBusinessDate
import com.nikhil.sentinelx.desktop.ui.AppState
import com.nikhil.sentinelx.desktop.ui.PanelRequest
import com.nikhil.sentinelx.desktop.ui.Section
import com.nikhil.sentinelx.desktop.ui.components.*
import com.nikhil.sentinelx.desktop.ui.theme.*
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Household bills — electricity, Wi-Fi, phone and the rest — grouped by the
 * month they fall due, with paid/unpaid tracked per bill and the photographed
 * bill attached. The one-tap action is the checkmark: marking a bill paid
 * stamps today as the payment date.
 *
 * Deliberately not fed into the Ledger or Wealth Vision, the cash-book rule:
 * many bills are paid from tracked accounts and would double-count.
 */
@Composable
fun BillsPane(state: AppState) {
    val all = state.backup.bills
    var typeFilter by remember { mutableStateOf<String?>(null) }
    var statusFilter by remember { mutableStateOf<String?>(null) }  // null · UNPAID · PAID
    var query by remember { mutableStateOf("") }

    val today = todayBusinessDate()
    val filtered = remember(all, typeFilter, statusFilter, query) {
        val q = query.trim()
        all.asSequence()
            .filter { typeFilter == null || it.billType.equals(typeFilter, true) }
            .filter { statusFilter == null || it.status == statusFilter }
            .filter {
                q.isEmpty() || listOf(it.provider, Bills.label(it.billType), it.refNo.orEmpty(), it.notes.orEmpty())
                    .any { field -> field.contains(q, true) }
            }
            .sortedWith(compareByDescending<BillEntity> { businessDateOf(it.dueDate).let { d -> YearMonth.from(d) } }
                .thenBy { it.isPaid() }             // unpaid first within the month
                .thenByDescending { it.dueDate })
            .toList()
    }
    val byMonth = remember(filtered) {
        filtered.groupBy { YearMonth.from(businessDateOf(it.dueDate)) }
    }

    val unpaid = all.filterNot { it.isPaid() }
    val overdueCount = unpaid.count { it.isOverdue(today) }
    val thisMonth = YearMonth.from(businessDateOf(today))
    val paidThisMonth = all.filter {
        it.isPaid() && it.paidDate?.let { d -> YearMonth.from(businessDateOf(d)) == thisMonth } == true
    }.sumOf { it.amount }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            PaneHeader(
                "Bills",
                if (all.isEmpty()) "DUES, REMEMBERED"
                else "${all.size} BILLS · ${unpaid.size} UNPAID"
            ) { TransferActions(state, Section.BILLS) }

            if (all.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    EmptyState(
                        "ᛚ", "NO BILLS YET",
                        "Add the electricity, Wi-Fi and phone bills once — then just mark them paid each month"
                    )
                }
            } else {
                Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 32.dp)) {
                    // ── Summary tiles ────────────────────────────────────────
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        BillTile("UNPAID", formatMoney(unpaid.sumOf { it.amount }), "${unpaid.size} bills", AmberWarn, Modifier.weight(1f))
                        BillTile(
                            "OVERDUE",
                            "$overdueCount",
                            if (overdueCount == 0) "nothing late" else "past their due date",
                            if (overdueCount == 0) IncomeGreen else ExpenseRed,
                            Modifier.weight(1f)
                        )
                        BillTile("PAID THIS MONTH", formatMoney(paidThisMonth), thisMonth.format(monthTitle), IncomeGreen, Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(14.dp))

                    // ── Filters ──────────────────────────────────────────────
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                            BillChip("ALL", typeFilter == null, GoldTarnished) { typeFilter = null }
                            Bills.TYPES.forEach { type ->
                                Spacer(Modifier.width(6.dp))
                                BillChip(Bills.label(type).uppercase(), typeFilter == type, billAccent(type)) {
                                    typeFilter = if (typeFilter == type) null else type
                                }
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        BillChip("UNPAID", statusFilter == Bills.UNPAID, AmberWarn) {
                            statusFilter = if (statusFilter == Bills.UNPAID) null else Bills.UNPAID
                        }
                        Spacer(Modifier.width(6.dp))
                        BillChip("PAID", statusFilter == Bills.PAID, IncomeGreen) {
                            statusFilter = if (statusFilter == Bills.PAID) null else Bills.PAID
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    SearchField(query, { query = it }, "Search provider, number or note")
                    Spacer(Modifier.height(8.dp))

                    // ── The list ─────────────────────────────────────────────
                    if (filtered.isEmpty()) {
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            EmptyState("ᛚ", "NO MATCHES", "Try a different filter")
                        }
                    } else {
                        LazyColumn(Modifier.weight(1f)) {
                            byMonth.forEach { (month, bills) ->
                                item(key = "month-$month") {
                                    Row(
                                        Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            month.format(monthTitle).uppercase(),
                                            color = GoldTarnished, fontSize = 10.sp,
                                            fontWeight = FontWeight.Black, letterSpacing = 2.sp
                                        )
                                        Spacer(Modifier.weight(1f))
                                        Text(
                                            formatMoney(bills.sumOf { it.amount }),
                                            color = TextSubtle, fontSize = 10.sp, fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                                items(bills, key = { it.id }) { bill ->
                                    BillRow(
                                        bill = bill,
                                        today = today,
                                        onTogglePaid = { state.setBillPaid(bill.id, !bill.isPaid()) },
                                        onClick = { state.panels.open(PanelRequest.Bill(bill)) }
                                    )
                                }
                            }
                            item { Spacer(Modifier.height(80.dp)) }
                        }
                    }
                }
            }
        }

        Box(Modifier.align(Alignment.BottomEnd).padding(28.dp)) {
            AddButton(onClick = { state.panels.open(PanelRequest.Bill(null)) })
        }
    }
}

private val monthTitle = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH)
private val dueFormat = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

internal fun billAccent(type: String): Color = when (type.uppercase()) {
    Bills.TYPE_ELECTRICITY -> AmberWarn
    Bills.TYPE_WIFI -> CyanGlow
    Bills.TYPE_PHONE -> IncomeGreen
    Bills.TYPE_GAS -> ExpenseRed
    Bills.TYPE_WATER -> CyanSoft
    Bills.TYPE_RENT -> GoldTarnished
    Bills.TYPE_TV -> PurpleMystic
    else -> GoldBright
}

internal fun billIcon(type: String): ImageVector = when (type.uppercase()) {
    Bills.TYPE_ELECTRICITY -> Icons.Default.Bolt
    Bills.TYPE_WIFI -> Icons.Default.Wifi
    Bills.TYPE_PHONE -> Icons.Default.Phone
    Bills.TYPE_GAS -> Icons.Default.LocalFireDepartment
    Bills.TYPE_WATER -> Icons.Default.WaterDrop
    Bills.TYPE_RENT -> Icons.Default.Home
    Bills.TYPE_TV -> Icons.Default.Tv
    else -> Icons.Default.ReceiptLong
}

@Composable
private fun BillTile(label: String, value: String, sub: String, accent: Color, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceStone)
            .border(1.dp, accent.copy(0.25f), RoundedCornerShape(14.dp))
            .padding(16.dp)
    ) {
        Text(label, color = TextMuted, fontSize = 8.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(value, color = accent, fontSize = 20.sp, fontWeight = FontWeight.Black)
        Text(sub, color = TextSubtle, fontSize = 10.sp)
    }
}

@Composable
private fun BillChip(label: String, active: Boolean, tint: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(if (active) tint.copy(0.16f) else SurfaceStone)
            .border(1.dp, if (active) tint.copy(0.5f) else GoldDark.copy(0.25f), RoundedCornerShape(9.dp))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 7.dp)
    ) {
        Text(
            label, color = if (active) tint else TextSubtle,
            fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp
        )
    }
}

@Composable
private fun BillRow(bill: BillEntity, today: Long, onTogglePaid: () -> Unit, onClick: () -> Unit) {
    val accent = billAccent(bill.billType)
    val overdue = bill.isOverdue(today)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp)
            .clip(RoundedCornerShape(12.dp))
            .rowSurface(false)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Paid toggle — the daily interaction.
        Box(
            Modifier.size(24.dp).clip(CircleShape)
                .background(if (bill.isPaid()) IncomeGreen else SurfaceElevated)
                .border(1.dp, if (bill.isPaid()) IncomeGreen else GoldDark.copy(0.4f), CircleShape)
                .clickable { onTogglePaid() },
            contentAlignment = Alignment.Center
        ) {
            if (bill.isPaid()) Text("✓", color = BackgroundVoid, fontSize = 13.sp, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.width(12.dp))

        Box(
            Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(accent.copy(0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(billIcon(bill.billType), contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    bill.provider.ifBlank { Bills.label(bill.billType) },
                    color = TextParchment, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1
                )
                if (bill.billFilenames().isNotEmpty()) {
                    Spacer(Modifier.width(6.dp))
                    Text("🖼", fontSize = 10.sp)
                }
            }
            Text(
                listOfNotNull(
                    Bills.label(bill.billType),
                    bill.refNo?.takeIf { it.isNotBlank() }
                ).joinToString(" · "),
                color = TextMuted, fontSize = 10.sp, maxLines = 1
            )
        }

        Column(horizontalAlignment = Alignment.End) {
            Text(
                formatMoney(bill.amount),
                color = if (bill.isPaid()) TextSubtle else accent,
                fontSize = 13.sp, fontWeight = FontWeight.Black
            )
            when {
                bill.isPaid() -> Text(
                    "PAID" + (bill.paidDate?.let { " " + businessDateOf(it).format(dueFormat) } ?: ""),
                    color = IncomeGreen, fontSize = 8.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold
                )
                overdue -> Text(
                    "OVERDUE · was due ${businessDateOf(bill.dueDate).format(dueFormat)}",
                    color = ExpenseRed, fontSize = 8.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold
                )
                else -> Text(
                    "due ${businessDateOf(bill.dueDate).format(dueFormat)}",
                    color = TextSubtle, fontSize = 9.sp
                )
            }
        }
    }
}

/**
 * Add / edit a bill. Provider names already in the vault come back as one-tap
 * chips per type — the same few billers recur every month.
 */
@Composable
fun BillEditor(state: AppState, existing: BillEntity?, onClose: () -> Unit) {
    var billType by remember { mutableStateOf(existing?.billType ?: Bills.TYPE_ELECTRICITY) }
    var provider by remember { mutableStateOf(existing?.provider ?: "") }
    var refNo by remember { mutableStateOf(existing?.refNo ?: "") }
    var amountText by remember { mutableStateOf(existing?.amount?.takeIf { it > 0 }?.toString() ?: "") }
    var dueText by remember {
        mutableStateOf(
            (existing?.dueDate?.let { businessDateOf(it) } ?: LocalDate.now())
                .format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
        )
    }
    var paid by remember { mutableStateOf(existing?.isPaid() ?: false) }
    var notes by remember { mutableStateOf(existing?.notes ?: "") }
    var images by remember { mutableStateOf(existing?.billFilenames() ?: emptyList()) }
    var confirmDelete by remember { mutableStateOf(false) }

    val parsedDue = remember(dueText) {
        listOf("d/M/uuuu", "d-M-uuuu", "d.M.uuuu").firstNotNullOfOrNull { pattern ->
            runCatching { LocalDate.parse(dueText.trim(), DateTimeFormatter.ofPattern(pattern)) }.getOrNull()
        }
    }
    val amount = remember(amountText) { amountText.replace(",", "").trim().toDoubleOrNull()?.takeIf { it > 0 } }

    val knownProviders = remember(state.backup.bills, billType) {
        state.backup.bills
            .filter { it.billType.equals(billType, true) && it.id != existing?.id }
            .map { it.provider }.filter { it.isNotBlank() }.distinct().take(5)
    }

    EditorDialog(
        title = if (existing == null) "New Bill" else "Edit Bill",
        canSave = provider.isNotBlank() && amount != null && parsedDue != null,
        onSave = {
            state.upsertBill(
                BillEntity(
                    id = existing?.id ?: 0L,
                    billType = billType,
                    provider = provider.trim(),
                    refNo = refNo.trim().ifBlank { null },
                    amount = amount!!,
                    dueDate = parsedDue!!.toBusinessDate(),
                    status = if (paid) Bills.PAID else Bills.UNPAID,
                    paidDate = when {
                        !paid -> null
                        existing?.isPaid() == true -> existing.paidDate  // already paid: keep the date
                        else -> todayBusinessDate()
                    },
                    billImageUris = images.joinToString(",").ifBlank { null },
                    notes = notes.trim().ifBlank { null },
                    timestamp = existing?.timestamp ?: 0L
                )
            )
            onClose()
        },
        onCancel = onClose,
        onDelete = existing?.let { { confirmDelete = true } },
        width = 600
    ) {
        Text("BILL TYPE", color = GoldTarnished, fontSize = 8.sp, letterSpacing = 1.5.sp,
            modifier = Modifier.padding(start = 2.dp, bottom = 5.dp))
        Bills.TYPES.chunked(4).forEach { row ->
            Row(Modifier.padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { type ->
                    val active = billType == type
                    val tint = billAccent(type)
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(9.dp))
                            .background(if (active) tint.copy(0.16f) else SurfaceGem)
                            .border(1.dp, if (active) tint.copy(0.6f) else GoldDark.copy(0.25f), RoundedCornerShape(9.dp))
                            .clickable { billType = type }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(billIcon(type), contentDescription = null, tint = if (active) tint else TextMuted,
                            modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(
                            Bills.label(type).uppercase(),
                            color = if (active) tint else TextSubtle,
                            fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        EditorField(provider, { provider = it }, "Provider",
            placeholder = "MSEB, Airtel, the landlord…")
        if (knownProviders.isNotEmpty() && provider.isBlank()) {
            Row(Modifier.padding(start = 2.dp, bottom = 10.dp).offset(y = (-6).dp)) {
                knownProviders.forEach { name ->
                    Box(
                        Modifier.padding(end = 6.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(CyanGlow.copy(0.08f))
                            .border(1.dp, CyanGlow.copy(0.25f), RoundedCornerShape(8.dp))
                            .clickable { provider = name }
                            .padding(horizontal = 9.dp, vertical = 4.dp)
                    ) { Text(name, color = CyanSoft, fontSize = 10.sp, maxLines = 1) }
                }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.weight(1f)) {
                EditorField(amountText, { amountText = it }, "Amount", placeholder = "0.00")
            }
            Box(Modifier.weight(1f)) {
                EditorField(dueText, { dueText = it }, "Due date", placeholder = "dd/MM/yyyy")
            }
        }
        if (dueText.isNotBlank() && parsedDue == null) {
            Text("Due date must be dd/MM/yyyy.", color = ExpenseRed, fontSize = 10.sp,
                modifier = Modifier.padding(bottom = 8.dp))
        }

        EditorField(refNo, { refNo = it }, "Consumer / account no.", placeholder = "Optional — printed on the bill")

        // Paid toggle
        Row(
            Modifier
                .clip(RoundedCornerShape(9.dp))
                .clickable { paid = !paid }
                .padding(horizontal = 4.dp, vertical = 4.dp)
                .padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(16.dp).clip(RoundedCornerShape(4.dp))
                    .background(if (paid) IncomeGreen else SurfaceElevated),
                contentAlignment = Alignment.Center
            ) {
                if (paid) Text("✓", color = BackgroundVoid, fontSize = 11.sp, fontWeight = FontWeight.Black)
            }
            Spacer(Modifier.width(8.dp))
            Text(
                if (paid) "Paid" else "Not paid yet",
                color = if (paid) IncomeGreen else TextSubtle, fontSize = 12.sp
            )
        }

        EditorField(notes, { notes = it }, "Notes", singleLine = false, minLines = 2, placeholder = "Optional")

        Text("BILL PHOTO", color = GoldTarnished, fontSize = 8.sp, letterSpacing = 1.5.sp,
            modifier = Modifier.padding(start = 2.dp, bottom = 6.dp))
        Row(Modifier.fillMaxWidth().height(110.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            images.forEachIndexed { i, name ->
                ImageSlot("BILL", name, state, Modifier.weight(1f)) { changed ->
                    images = if (changed == null) images.filterIndexed { j, _ -> j != i }
                    else images.mapIndexed { j, old -> if (j == i) changed else old }
                }
            }
            if (images.size < 3) {
                ImageSlot("ADD", null, state, Modifier.weight(1f)) { added ->
                    if (added != null) images = images + added
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    if (confirmDelete && existing != null) {
        ConfirmDelete(
            itemName = existing.provider.ifBlank { Bills.label(existing.billType) },
            onConfirm = { state.deleteBill(existing.id); confirmDelete = false; onClose() },
            onDismiss = { confirmDelete = false }
        )
    }
}
