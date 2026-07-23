package com.nikhil.sentinelx.desktop.ui.panes

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nikhil.sentinelx.desktop.core.format.TransactionEntity
import com.nikhil.sentinelx.desktop.ui.components.GemCard
import com.nikhil.sentinelx.desktop.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** A single vertex of the running-balance line. */
private data class BalancePoint(
    val xNorm: Float,     // 0..1 position along the time axis
    val balance: Double,  // cumulative balance up to and including this transaction
    val timestamp: Long
)

private val graphDate = SimpleDateFormat("d MMM ''yy", Locale.getDefault())

/**
 * Cumulative-balance trend graph.
 *
 * This is the thing a phone genuinely cannot do well: it walks every transaction in
 * time order, accumulates the running balance, and draws the whole trajectory at once
 * — where the money went and when, not just today's total. It self-draws left-to-right
 * on appearance, and on desktop the mouse is a first-class input, so hovering anywhere
 * along the line reveals the exact balance and date at that moment.
 *
 * Time-based X (not evenly spaced by index): a burst of spending in one week and a
 * quiet month must look different, and index spacing would flatten that away.
 */
@Composable
fun BalanceTrendGraph(transactions: List<TransactionEntity>) {
    // A line needs at least two vertices; below that there is nothing to trend.
    if (transactions.size < 2) return

    val ordered = remember(transactions) { transactions.sortedBy { it.timestamp } }

    val points = remember(ordered) {
        val tMin = ordered.first().timestamp
        val tMax = ordered.last().timestamp
        val span = (tMax - tMin).toDouble()
        var running = 0.0
        ordered.mapIndexed { i, tx ->
            running += if (tx.isIncoming) tx.amount else -tx.amount
            // Fall back to even spacing when every stamp collides (same millisecond),
            // otherwise the whole line would pile onto x = 0.
            val xNorm = if (span > 0) ((tx.timestamp - tMin) / span).toFloat()
                        else i.toFloat() / (ordered.size - 1)
            BalancePoint(xNorm, running, tx.timestamp)
        }
    }

    // Include zero in the vertical range so the baseline is always on screen — a line
    // that never crosses zero should still show how far above or below it sits.
    val balances = points.map { it.balance }
    val maxY = (balances.maxOrNull() ?: 0.0).coerceAtLeast(0.0)
    val minY = (balances.minOrNull() ?: 0.0).coerceAtMost(0.0)
    val rangeY = (maxY - minY).takeIf { it > 0.0 } ?: 1.0

    val current = points.last().balance
    val positive = current >= 0

    // Self-draw: wipe the line in from the left on first paint and whenever the set of
    // points changes (switching accounts, editing the filter).
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(points.size, current) {
        reveal.snapTo(0f)
        reveal.animateTo(1f, tween(900, easing = FastOutSlowInEasing))
    }

    // Hover position in canvas pixels, or null when the cursor is off the plot.
    var hoverPx by remember { mutableStateOf<Float?>(null) }

    val density = LocalDensity.current
    val hInsetDp = 8.dp
    val vInsetDp = 16.dp

    GemCard(accent = if (positive) GoldTarnished else ExpenseRed, modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(
                    "BALANCE TREND",
                    color = if (positive) GoldTarnished else ExpenseRed,
                    fontSize = 9.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    "${points.size} transactions · ${graphDate.format(Date(points.first().timestamp))} → ${graphDate.format(Date(points.last().timestamp))}",
                    color = TextMuted, fontSize = 10.sp
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("CURRENT", color = TextMuted, fontSize = 8.sp, letterSpacing = 2.sp)
                Spacer(Modifier.height(2.dp))
                Text(
                    (if (positive) "+" else "−") + formatMoney(current),
                    color = if (positive) IncomeGreen else ExpenseRed,
                    fontSize = 18.sp, fontWeight = FontWeight.Black
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        BoxWithConstraints(Modifier.fillMaxWidth().height(190.dp)) {
            val boxWpx = with(density) { maxWidth.toPx() }
            val hPx = with(density) { hInsetDp.toPx() }
            val plotWpx = (boxWpx - 2 * hPx).coerceAtLeast(1f)

            // Which vertex the cursor is nearest, for the crosshair + tooltip.
            val hovered: BalancePoint? = hoverPx?.let { px ->
                val frac = ((px - hPx) / plotWpx).coerceIn(0f, 1f)
                points.minByOrNull { abs(it.xNorm - frac) }
            }

            Canvas(
                Modifier.fillMaxSize().pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            when (event.type) {
                                PointerEventType.Move, PointerEventType.Enter ->
                                    hoverPx = event.changes.first().position.x
                                PointerEventType.Exit -> hoverPx = null
                                else -> {}
                            }
                        }
                    }
                }
            ) {
                val w = size.width
                val h = size.height
                val vPad = vInsetDp.toPx()
                val plotH = h - 2 * vPad
                val plotW = w - 2 * hPx

                fun px(p: BalancePoint) = hPx + p.xNorm * plotW
                fun py(balance: Double): Float =
                    (vPad + ((maxY - balance) / rangeY).toFloat() * plotH)

                // Smooth cubic line through the vertices. Control points sit on the
                // vertical mid-x between neighbours, which curves the corners without
                // letting the line overshoot above a peak or below a trough.
                val line = Path().apply {
                    moveTo(px(points.first()), py(points.first().balance))
                    for (i in 1 until points.size) {
                        val a = points[i - 1]
                        val b = points[i]
                        val ax = px(a); val bx = px(b)
                        val cx = (ax + bx) / 2f
                        cubicTo(cx, py(a.balance), cx, py(b.balance), bx, py(b.balance))
                    }
                }

                // Area = the line, closed down to the baseline and back.
                val zeroY = py(0.0)
                val area = Path().apply {
                    addPath(line)
                    lineTo(px(points.last()), h)   // straight down to the baseline
                    lineTo(px(points.first()), h)  // across the bottom
                    close()                         // back up to the first vertex
                }

                // Reveal both fill and line left-to-right by clipping to the animated x.
                clipRect(right = hPx + plotW * reveal.value) {
                    drawPath(
                        area,
                        brush = Brush.verticalGradient(
                            listOf(
                                (if (positive) GoldTarnished else ExpenseRed).copy(0.28f),
                                (if (positive) GoldTarnished else ExpenseRed).copy(0.02f)
                            )
                        )
                    )
                    // Soft under-glow beneath the stroke, then the crisp line on top.
                    drawPath(
                        line,
                        color = (if (positive) GoldBright else ExpenseRed).copy(0.20f),
                        style = Stroke(width = 6f)
                    )
                    drawPath(
                        line,
                        brush = Brush.horizontalGradient(
                            listOf(GoldBright, if (positive) GoldTarnished else ExpenseRed)
                        ),
                        style = Stroke(width = 2.5f)
                    )
                }

                // Dashed zero baseline, only when the range actually straddles zero.
                if (minY < 0.0 && maxY > 0.0) {
                    drawLine(
                        color = TextMuted.copy(0.35f),
                        start = Offset(hPx, zeroY),
                        end = Offset(hPx + plotW, zeroY),
                        strokeWidth = 1f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 8f))
                    )
                }

                // Crosshair + marker at the hovered vertex (drawn after the reveal clip
                // so it is never wiped by the animation).
                hovered?.let { p ->
                    val x = px(p)
                    val y = py(p.balance)
                    drawLine(
                        color = CyanGlow.copy(0.45f),
                        start = Offset(x, vPad),
                        end = Offset(x, h - vPad),
                        strokeWidth = 1f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 5f))
                    )
                    drawCircle(CyanGlow.copy(0.20f), radius = 9f, center = Offset(x, y))
                    drawCircle(CyanGlow, radius = 4f, center = Offset(x, y))
                    drawCircle(BackgroundDeep, radius = 1.6f, center = Offset(x, y))
                }
            }

            // Floating tooltip. Kept as real Text (not canvas glyphs) so it stays sharp
            // and themed; positioned over the hovered vertex and clamped inside the box.
            hovered?.let { p ->
                val tipW = 132.dp
                val tipWpx = with(density) { tipW.toPx() }
                val markerX = hPx + p.xNorm * plotWpx
                val left = (markerX - tipWpx / 2f).coerceIn(0f, (boxWpx - tipWpx).coerceAtLeast(0f))
                Column(
                    Modifier
                        .offset { IntOffset(left.roundToInt(), 0) }
                        .width(tipW)
                        .background(BackgroundVoid.copy(0.92f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp, vertical = 7.dp)
                ) {
                    Text(graphDate.format(Date(p.timestamp)), color = TextMuted, fontSize = 9.sp)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        (if (p.balance >= 0) "+" else "−") + formatMoney(p.balance),
                        color = if (p.balance >= 0) IncomeGreen else ExpenseRed,
                        fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        // Axis extremes, so the vertical scale is legible without a gridded Y axis.
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("peak ${formatMoney(maxY)}", color = TextMuted, fontSize = 9.sp)
            if (minY < 0.0) Text("low −${formatMoney(minY)}", color = ExpenseRedDim, fontSize = 9.sp)
        }
    }
}
