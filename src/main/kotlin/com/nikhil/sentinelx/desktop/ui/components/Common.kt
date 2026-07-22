package com.nikhil.sentinelx.desktop.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.TextStyle
import androidx.compose.runtime.withFrameNanos
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nikhil.sentinelx.desktop.ui.theme.*
import kotlinx.coroutines.delay

/**
 * Shared widgets. Anything used by more than one pane belongs here.
 *
 * The Android app learned this the hard way: SentinelConfirmDelete lived inside
 * CardScreen.kt while six unrelated screens called it, so rewriting one screen
 * risked breaking six. Keep the boundary clean from the start here.
 */

/** Deterministic accent colour per name — same idea as the phone's siteColor(). */
private val avatarPalette = listOf(
    Color(0xFF00B4D8), Color(0xFFD4A853), Color(0xFF7B2FBE),
    Color(0xFF2ED573), Color(0xFFFF4757), Color(0xFF5352ED),
    Color(0xFF00E5FF), Color(0xFFE8A830), Color(0xFFB01A2D)
)

fun accentFor(name: String): Color =
    avatarPalette[(name.firstOrNull()?.code ?: 0) % avatarPalette.size]

@Composable
fun PaneHeader(title: String, subtitle: String, trailing: @Composable RowScope.() -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(start = 32.dp, end = 32.dp, top = 28.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Column {
                Text(
                    title.uppercase(),
                    // Gradient fill on the display type — the single cheapest thing
                    // that makes a heading look crafted rather than defaulted.
                    style = TextStyle(
                        brush = Brush.horizontalGradient(listOf(GoldIce, GoldTarnished, GoldDark)),
                        fontSize = 27.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Serif,
                        letterSpacing = 4.sp
                    )
                )
                Spacer(Modifier.height(2.dp))
                Text(subtitle.uppercase(), color = TextMuted, fontSize = 9.sp, letterSpacing = 3.sp)
            }
            Spacer(Modifier.weight(1f))
            trailing()
        }
        Spacer(Modifier.height(14.dp))
        // Rule that fades out rather than stopping dead — a hard edge across a wide
        // window looks like a table border.
        Box(
            Modifier.fillMaxWidth().height(1.dp).background(
                Brush.horizontalGradient(
                    listOf(GoldTarnished.copy(0.45f), GoldDark.copy(0.15f), Color.Transparent)
                )
            )
        )
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        leadingIcon = {
            Icon(Icons.Default.Search, null, tint = TextMuted, modifier = Modifier.size(16.dp))
        },
        placeholder = { Text(placeholder, color = TextMuted, fontSize = 12.sp) },
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = CyanGlow.copy(0.6f),
            unfocusedBorderColor = GoldDark.copy(0.22f),
            focusedTextColor = TextParchment,
            unfocusedTextColor = TextParchment,
            cursorColor = CyanGlow,
            focusedContainerColor = SurfaceGem,
            unfocusedContainerColor = SurfaceStone
        )
    )
}

/** Elevated card with a gradient border, matching the phone's SurfaceGem language. */
@Composable
fun GemCard(
    accent: Color = GoldTarnished,
    modifier: Modifier = Modifier,
    stripe: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier
            .clip(shape)
            .background(Brush.verticalGradient(listOf(SurfaceGem, SurfaceStone, BackgroundDeep)))
            .border(
                width = 1.dp,
                // Gradient border rather than a flat one: it catches the eye along
                // the top edge and recedes at the bottom, which reads as lit.
                brush = Brush.verticalGradient(listOf(accent.copy(0.35f), accent.copy(0.06f))),
                shape = shape
            )
    ) {
        if (stripe) {
            Box(
                Modifier.width(3.dp).fillMaxHeight()
                    .background(Brush.verticalGradient(listOf(accent, accent.copy(0.1f))))
            )
        }
        Column(Modifier.padding(start = if (stripe) 22.dp else 20.dp, top = 18.dp, end = 20.dp, bottom = 18.dp), content = content)
    }
}

/**
 * Copies to the clipboard and confirms with a checkmark.
 *
 * Always routes through [SecureClipboard] rather than the raw clipboard, so the
 * 30-second auto-clear cannot be forgotten at a call site.
 */
@Composable
fun CopyButton(value: String, label: String, tint: Color = GoldTarnished) {
    var copied by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        if (copied) 1.18f else 1f, spring(Spring.DampingRatioMediumBouncy), label = "copy"
    )

    LaunchedEffect(copied) {
        if (copied) { delay(2000); copied = false }
    }

    Box(
        Modifier
            .size(30.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(8.dp))
            .background(tint.copy(0.08f))
            .border(1.dp, tint.copy(0.2f), RoundedCornerShape(8.dp))
            .clickable {
                SecureClipboard.copySensitive(label, value)
                copied = true
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
            contentDescription = "Copy $label",
            tint = if (copied) IncomeGreen else tint,
            modifier = Modifier.size(13.dp)
        )
    }
}

@Composable
fun Pill(text: String, color: Color) {
    Box(
        Modifier
            .clip(CircleShape)
            .background(color.copy(0.14f))
            .border(1.dp, color.copy(0.3f), CircleShape)
            .padding(horizontal = 9.dp, vertical = 3.dp)
    ) {
        Text(text, color = color, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    }
}

/**
 * Row background that responds to the pointer.
 *
 * Hover feedback is the main thing that separates a desktop app from a phone layout
 * stretched wide — without it, a list of 40 logins gives no indication that rows are
 * interactive until you click one.
 */
@Composable
fun Modifier.rowSurface(selected: Boolean): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val tint by animateColorAsState(
        when {
            selected -> GoldDim.copy(0.34f)
            hovered -> SurfaceGem.copy(0.55f)
            else -> Color.Transparent
        },
        tween(140),
        label = "rowTint"
    )
    return this.hoverable(interaction).background(tint)
}

/**
 * Requests focus once the node is actually attached to the focus tree.
 *
 * Calling FocusRequester.requestFocus() from a LaunchedEffect that fires on the
 * first frame throws "FocusRequester is not initialized" — the composable exists
 * but its focus target node does not yet, which is especially common inside a
 * Popup. On desktop that exception lands on the AWT event thread and takes the
 * whole window down, so it must never be thrown rather than merely caught.
 *
 * Waits a frame at a time until the node attaches, then gives up quietly. Failing
 * to focus a field is a small annoyance; crashing the app is not.
 */
suspend fun FocusRequester.requestWhenReady(attempts: Int = 12) {
    repeat(attempts) {
        withFrameNanos { }
        if (runCatching { requestFocus() }.isSuccess) return
    }
}

@Composable
fun EmptyState(glyph: String, title: String, hint: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(glyph, color = GoldDark.copy(0.35f), fontSize = 48.sp)
            Spacer(Modifier.height(14.dp))
            Text(title, color = TextSubtle, fontSize = 14.sp, letterSpacing = 2.sp)
            Spacer(Modifier.height(6.dp))
            Text(hint, color = TextMuted, fontSize = 11.sp)
        }
    }
}
