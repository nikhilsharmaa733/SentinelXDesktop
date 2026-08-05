package com.nikhil.sentinelx.desktop.ui.panes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nikhil.sentinelx.desktop.core.format.CashBook
import com.nikhil.sentinelx.desktop.ui.theme.*

/**
 * The note-by-note tally — the thing this whole feature exists for.
 *
 * Each row does its own arithmetic (`500 × 12 = ₹6,000`) and the grand total grows as
 * you count, so the running figure is on screen at the moment your hands are on the
 * cash. That is the difference between a form and a counting tool.
 *
 * Uncommon denominations stay folded away: nobody hands over ₹1 coins at closing time,
 * and ten extra rows would bury the six that matter. Any denomination already carrying
 * a count is shown regardless of which list it belongs to, so a folded row can never
 * hide money.
 */
@Composable
fun DenominationCounter(
    counts: Map<Int, Int>,
    onChange: (Map<Int, Int>) -> Unit,
    accent: Color = GoldTarnished
) {
    var showAll by remember { mutableStateOf(false) }

    val visible = remember(counts, showAll) {
        if (showAll) CashBook.DENOMINATIONS
        else CashBook.DENOMINATIONS.filter { it in CashBook.COMMON_DENOMINATIONS || (counts[it] ?: 0) > 0 }
    }
    val total = counts.entries.sumOf { (denomination, count) -> denomination.toDouble() * count }
    val noteCount = counts.values.sum()

    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "NOTE COUNT",
                color = accent, fontSize = 8.sp,
                letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.weight(1f))
            if (counts.isNotEmpty()) {
                Text(
                    "CLEAR",
                    color = TextMuted, fontSize = 8.sp, letterSpacing = 1.sp,
                    modifier = Modifier.clickable { onChange(emptyMap()) }
                )
                Spacer(Modifier.width(14.dp))
            }
            Text(
                if (showAll) "FEWER" else "ALL DENOMINATIONS",
                color = CyanGlow, fontSize = 8.sp, letterSpacing = 1.sp,
                modifier = Modifier.clickable { showAll = !showAll }
            )
        }

        Spacer(Modifier.height(8.dp))

        visible.forEach { denomination ->
            DenominationRow(
                denomination = denomination,
                count = counts[denomination] ?: 0,
                accent = accent,
                onCount = { next ->
                    onChange(
                        if (next <= 0) counts - denomination else counts + (denomination to next)
                    )
                }
            )
        }

        Spacer(Modifier.height(10.dp))

        Row(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Brush.horizontalGradient(listOf(SurfaceGem, SurfaceStone)))
                .padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("COUNTED TOTAL", color = TextMuted, fontSize = 8.sp, letterSpacing = 2.sp)
                Spacer(Modifier.height(3.dp))
                Text(
                    "$noteCount note${if (noteCount == 1) "" else "s"}",
                    color = TextSubtle, fontSize = 10.sp
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                formatMoney(total),
                color = if (total > 0) GoldIce else TextMuted,
                fontSize = 22.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Serif
            )
        }
    }
}

@Composable
private fun DenominationRow(
    denomination: Int,
    count: Int,
    accent: Color,
    onCount: (Int) -> Unit
) {
    val subtotal = denomination.toDouble() * count
    val live = count > 0

    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.width(58.dp).height(30.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(if (live) accent.copy(0.14f) else SurfaceStone.copy(0.5f))
                .border(
                    1.dp,
                    if (live) accent.copy(0.4f) else GoldDark.copy(0.15f),
                    RoundedCornerShape(7.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "₹$denomination",
                color = if (live) accent else TextMuted,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(Modifier.width(12.dp))

        StepButton("−", enabled = count > 0) { onCount(count - 1) }
        Spacer(Modifier.width(6.dp))

        // Typed entry as well as stepping: counting out 120 notes with a button would
        // be absurd, and whoever is entering last night's slip already knows the number.
        Box(
            Modifier.width(60.dp).height(30.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(SurfaceGem)
                .border(1.dp, GoldDark.copy(0.22f), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            BasicTextField(
                value = if (count == 0) "" else count.toString(),
                onValueChange = { raw ->
                    val digits = raw.filter { it.isDigit() }.take(5)
                    onCount(digits.toIntOrNull() ?: 0)
                },
                singleLine = true,
                textStyle = TextStyle(
                    color = if (live) TextParchment else TextMuted,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                ),
                cursorBrush = SolidColor(CyanGlow),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.Center) {
                        if (count == 0) Text("0", color = TextMuted.copy(0.5f), fontSize = 13.sp)
                        inner()
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
            )
        }

        Spacer(Modifier.width(6.dp))
        StepButton("+", enabled = true) { onCount(count + 1) }

        Spacer(Modifier.width(14.dp))

        Text(
            if (live) "= ${formatMoney(subtotal)}" else "",
            color = if (live) TextParchment else Color.Transparent,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun StepButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(30.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (enabled) SurfaceElevated else SurfaceStone.copy(0.4f))
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (enabled) GoldTarnished else TextMuted.copy(0.4f),
            fontSize = 16.sp,
            fontWeight = FontWeight.Black
        )
    }
}

/** Read-only tally, for cards and rows. Compact enough to sit inside a list item. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DenominationChips(counts: Map<Int, Int>, tint: Color = TextSubtle) {
    if (counts.isEmpty()) return
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        counts.keys.sortedDescending().forEach { denomination ->
            Box(
                Modifier.clip(RoundedCornerShape(6.dp))
                    .background(tint.copy(0.10f))
                    .padding(horizontal = 7.dp, vertical = 3.dp)
            ) {
                Text(
                    "${denomination}×${counts.getValue(denomination)}",
                    color = tint, fontSize = 10.sp, fontWeight = FontWeight.Medium
                )
            }
        }
    }
}
