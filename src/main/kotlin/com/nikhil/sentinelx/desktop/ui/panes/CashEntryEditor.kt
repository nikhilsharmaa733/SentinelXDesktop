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
import com.nikhil.sentinelx.desktop.core.format.CashBook
import com.nikhil.sentinelx.desktop.core.format.CashEntryEntity
import com.nikhil.sentinelx.desktop.core.format.businessDateOf
import com.nikhil.sentinelx.desktop.core.format.denominationCounts
import com.nikhil.sentinelx.desktop.core.format.encodeDenominations
import com.nikhil.sentinelx.desktop.core.format.slipFilenames
import com.nikhil.sentinelx.desktop.core.format.toBusinessDate
import com.nikhil.sentinelx.desktop.ui.AppState
import com.nikhil.sentinelx.desktop.ui.components.*
import com.nikhil.sentinelx.desktop.ui.theme.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs

private val isoDate = DateTimeFormatter.ofPattern("yyyy-MM-dd")
private val longDate = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy")

/**
 * Records or amends one movement of cash.
 *
 * The amount tracks the note count automatically until someone types over it. That is
 * the ordinary case — you count the notes and the total is simply what they add up to.
 * Once it is overridden the two are allowed to disagree, and the disagreement is shown
 * loudly rather than reconciled away: a slip that says ₹56,000 over notes worth ₹55,500
 * is exactly the discrepancy the nightly count exists to surface.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CashEntryEditor(
    state: AppState,
    existing: CashEntryEntity?,
    defaultDate: Long,
    defaultSlot: String = CashBook.SLOT_OTHER,
    seedDenominations: Map<Int, Int> = emptyMap(),
    onClose: () -> Unit
) {
    var date by remember {
        mutableStateOf(businessDateOf(existing?.entryDate ?: defaultDate))
    }
    var slot by remember { mutableStateOf(existing?.slot ?: defaultSlot) }
    var direction by remember {
        mutableStateOf(existing?.direction ?: defaultDirectionFor(defaultSlot))
    }
    var counts by remember {
        mutableStateOf(existing?.denominationCounts() ?: seedDenominations)
    }

    val countedTotal = counts.entries.sumOf { (denomination, count) -> denomination.toDouble() * count }

    // Starts "following" for a new entry, and for an existing one only if its stored
    // amount already agreed with its tally — reopening a deliberate mismatch must not
    // silently repair it.
    var amountFollowsCount by remember {
        mutableStateOf(
            existing == null ||
                (existing.denominationCounts().isNotEmpty() &&
                    abs(existing.denominationCounts().entries
                        .sumOf { (d, c) -> d.toDouble() * c } - existing.amount) < CashBook.EPSILON)
        )
    }
    var amount by remember { mutableStateOf(existing?.amount?.takeIf { it > 0 }?.trimZeros() ?: "") }

    if (amountFollowsCount && counts.isNotEmpty()) {
        amount = countedTotal.trimZeros()
    }

    var particulars by remember { mutableStateOf(existing?.particulars ?: "") }
    var countedBy by remember { mutableStateOf(existing?.countedBy ?: "") }
    var verifiedBy by remember { mutableStateOf(existing?.verifiedBy ?: "") }
    var verified by remember { mutableStateOf(existing?.status == CashBook.STATUS_VERIFIED) }
    var notes by remember { mutableStateOf(existing?.notes ?: "") }
    var slips by remember { mutableStateOf(existing?.slipFilenames() ?: emptyList()) }
    var confirmDelete by remember { mutableStateOf(false) }

    val parsedAmount = amount.replace(",", "").trim().toDoubleOrNull()
    val difference = if (counts.isEmpty() || parsedAmount == null) 0.0 else countedTotal - parsedAmount
    val mismatched = abs(difference) >= CashBook.EPSILON

    // Naming who signed off is the point of the ritual; "verified by nobody" is not a
    // state worth being able to save.
    val verifierMissing = verified && verifiedBy.isBlank()
    val canSave = parsedAmount != null && parsedAmount > 0 && !verifierMissing

    val accent = if (direction == CashBook.IN) IncomeGreen else ExpenseRed

    EditorDialog(
        title = if (existing == null) "New Cash Entry" else "Edit Cash Entry",
        canSave = canSave,
        width = 720,
        onDelete = existing?.let { { confirmDelete = true } },
        onCancel = onClose,
        onSave = {
            state.upsertCashEntry(
                CashEntryEntity(
                    id = existing?.id ?: 0L,
                    book = existing?.book ?: CashBook.DEFAULT_BOOK,
                    entryDate = date.toBusinessDate(),
                    direction = direction,
                    slot = slot,
                    amount = parsedAmount ?: 0.0,
                    denominations = counts.encodeDenominations(),
                    particulars = particulars.trim(),
                    countedBy = countedBy.trim(),
                    verifiedBy = verifiedBy.trim(),
                    status = if (verified) CashBook.STATUS_VERIFIED else CashBook.STATUS_PENDING,
                    slipImageUris = slips.takeIf { it.isNotEmpty() }?.joinToString(","),
                    notes = notes.trim().takeIf { it.isNotEmpty() }
                )
            )
            onClose()
        }
    ) {
        // ── Date ─────────────────────────────────────────────────────────────
        FieldLabel("BUSINESS DATE")
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepChip("◀") { date = date.minusDays(1) }
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.width(190.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(SurfaceGem)
                    .border(1.dp, GoldDark.copy(0.25f), RoundedCornerShape(9.dp))
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(date.format(isoDate), color = TextParchment, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(8.dp))
            StepChip("▶") { date = date.plusDays(1) }
            Spacer(Modifier.width(14.dp))
            TextButton(onClick = { date = LocalDate.now() }) {
                Text("TODAY", color = CyanGlow, fontSize = 10.sp, letterSpacing = 1.sp)
            }
            Spacer(Modifier.weight(1f))
            Text(date.format(longDate), color = TextMuted, fontSize = 10.sp)
        }

        Spacer(Modifier.height(16.dp))

        // ── Slot and direction ───────────────────────────────────────────────
        FieldLabel("WHEN")
        Row {
            SlotChip("EVENING", "Brought home", slot == CashBook.SLOT_EVENING) {
                slot = CashBook.SLOT_EVENING
                direction = CashBook.IN
            }
            Spacer(Modifier.width(8.dp))
            SlotChip("MORNING", "Taken to office", slot == CashBook.SLOT_MORNING) {
                slot = CashBook.SLOT_MORNING
                direction = CashBook.OUT
            }
            Spacer(Modifier.width(8.dp))
            SlotChip("OTHER", "Any other movement", slot == CashBook.SLOT_OTHER) {
                slot = CashBook.SLOT_OTHER
            }
        }

        Spacer(Modifier.height(14.dp))

        FieldLabel("DIRECTION")
        Row {
            SlotChip("IN", "Received · debit", direction == CashBook.IN, IncomeGreen) {
                direction = CashBook.IN
            }
            Spacer(Modifier.width(8.dp))
            SlotChip("OUT", "Paid out · credit", direction == CashBook.OUT, ExpenseRed) {
                direction = CashBook.OUT
            }
        }

        Spacer(Modifier.height(18.dp))
        HorizontalDivider(color = GoldDark.copy(0.15f))
        Spacer(Modifier.height(18.dp))

        // ── The count ────────────────────────────────────────────────────────
        DenominationCounter(counts = counts, accent = accent, onChange = { next ->
            counts = next
            if (amountFollowsCount) amount = next.entries
                .sumOf { (d, c) -> d.toDouble() * c }.trimZeros()
        })

        Spacer(Modifier.height(16.dp))

        EditorField(
            value = amount,
            onValueChange = {
                amount = it.filter { ch -> ch.isDigit() || ch == '.' }
                amountFollowsCount = false
            },
            label = "Amount (₹)",
            placeholder = "0.00",
            accent = accent
        )

        if (mismatched) {
            MismatchBanner(
                difference = difference,
                countedTotal = countedTotal,
                onUseCounted = {
                    amount = countedTotal.trimZeros()
                    amountFollowsCount = true
                }
            )
            Spacer(Modifier.height(12.dp))
        }

        EditorField(particulars, { particulars = it }, "Particulars", placeholder = "Day's takings, counted at home")

        // ── Who ──────────────────────────────────────────────────────────────
        Row {
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                EditorField(countedBy, { countedBy = it }, "Counted by")
            }
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                EditorField(verifiedBy, { verifiedBy = it }, "Verified by", accent = CyanGlow)
            }
        }

        val people = remember(state.backup.cashBook) { state.knownCashPeople().take(6) }
        if (people.isNotEmpty()) {
            Text("QUICK PICK", color = TextMuted, fontSize = 8.sp, letterSpacing = 1.5.sp)
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                people.forEach { person ->
                    PersonChip(person, "counted") { countedBy = person }
                    PersonChip(person, "verified") { verifiedBy = person }
                }
            }
            Spacer(Modifier.height(14.dp))
        }

        Row(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(if (verified) CyanGlow.copy(0.08f) else SurfaceStone.copy(0.4f))
                .clickable { verified = !verified }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(18.dp).clip(RoundedCornerShape(5.dp))
                    .background(if (verified) CyanGlow else Color.Transparent)
                    .border(1.dp, if (verified) CyanGlow else TextMuted, RoundedCornerShape(5.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (verified) Text("✓", color = BackgroundVoid, fontSize = 12.sp, fontWeight = FontWeight.Black)
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    "Count verified",
                    color = if (verified) GoldIce else TextSubtle,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    if (verifierMissing) "Name who verified it before ticking this"
                    else "Someone checked the notes against the slip",
                    color = if (verifierMissing) ExpenseRed else TextMuted,
                    fontSize = 10.sp
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── Slip photo ───────────────────────────────────────────────────────
        FieldLabel("SLIP PHOTO")
        Row(verticalAlignment = Alignment.CenterVertically) {
            slips.forEach { name ->
                Box(Modifier.padding(end = 8.dp)) {
                    VaultImage(
                        fileName = name,
                        loader = state::readImage,
                        modifier = Modifier.size(64.dp).clip(RoundedCornerShape(9.dp))
                    )
                    Box(
                        Modifier.align(Alignment.TopEnd).size(18.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(BackgroundVoid.copy(0.85f))
                            .clickable { slips = slips - name },
                        contentAlignment = Alignment.Center
                    ) {
                        Text("×", color = ExpenseRed, fontSize = 13.sp, fontWeight = FontWeight.Black)
                    }
                }
            }
            Box(
                Modifier.size(64.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(SurfaceStone.copy(0.5f))
                    .border(1.dp, GoldDark.copy(0.3f), RoundedCornerShape(9.dp))
                    .clickable {
                        ImagePicker.pick(multiple = true).forEach { picked ->
                            slips = slips + state.addImage(picked.bytes, picked.extension)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text("+", color = GoldTarnished, fontSize = 20.sp, fontWeight = FontWeight.Black)
            }
        }

        Spacer(Modifier.height(16.dp))
        EditorField(notes, { notes = it }, "Notes", placeholder = "Anything unusual", singleLine = false, minLines = 2)
    }

    if (confirmDelete && existing != null) {
        ConfirmDelete(
            itemName = "${existing.direction} ${formatMoney(existing.amount)} on " +
                businessDateOf(existing.entryDate).format(isoDate),
            onConfirm = {
                state.deleteCashEntry(existing.id)
                confirmDelete = false
                onClose()
            },
            onDismiss = { confirmDelete = false }
        )
    }
}

@Composable
private fun MismatchBanner(difference: Double, countedTotal: Double, onUseCounted: () -> Unit) {
    val short = difference < 0
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(ExpenseRed.copy(0.10f))
            .border(1.dp, ExpenseRed.copy(0.35f), RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                if (short) "NOTES ARE ${formatMoney(difference)} SHORT"
                else "NOTES ARE ${formatMoney(difference)} OVER",
                color = ExpenseRed, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp
            )
            Spacer(Modifier.height(3.dp))
            Text(
                "The tally comes to ${formatMoney(countedTotal)}. Save anyway to record the " +
                    "discrepancy, or correct one of the two.",
                color = TextSubtle, fontSize = 10.sp, lineHeight = 14.sp
            )
        }
        Spacer(Modifier.width(10.dp))
        TextButton(onClick = onUseCounted) {
            Text("USE ${formatMoney(countedTotal)}", color = CyanGlow, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, color = GoldTarnished, fontSize = 8.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(7.dp))
}

@Composable
private fun StepChip(label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(SurfaceElevated)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = GoldTarnished, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SlotChip(
    label: String,
    hint: String,
    selected: Boolean,
    accent: Color = GoldTarnished,
    onClick: () -> Unit
) {
    Column(
        Modifier.clip(RoundedCornerShape(10.dp))
            .background(if (selected) accent.copy(0.13f) else SurfaceStone.copy(0.45f))
            .border(
                1.dp,
                if (selected) accent.copy(0.5f) else GoldDark.copy(0.15f),
                RoundedCornerShape(10.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 9.dp)
    ) {
        Text(
            label,
            color = if (selected) accent else TextSubtle,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
        Text(hint, color = TextMuted, fontSize = 9.sp)
    }
}

@Composable
private fun PersonChip(person: String, role: String, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(7.dp))
            .background(SurfaceElevated)
            .clickable { onClick() }
            .padding(horizontal = 9.dp, vertical = 5.dp)
    ) {
        Text("$person → $role", color = TextSubtle, fontSize = 10.sp)
    }
}

private fun defaultDirectionFor(slot: String): String = when (slot) {
    CashBook.SLOT_MORNING -> CashBook.OUT
    else -> CashBook.IN
}

/** "56000" rather than "56000.0" — the trailing zero reads as a stray keystroke. */
private fun Double.trimZeros(): String =
    if (this == toLong().toDouble()) toLong().toString() else toString()
