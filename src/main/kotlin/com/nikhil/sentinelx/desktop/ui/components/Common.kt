package com.nikhil.sentinelx.desktop.ui.components

import androidx.compose.animation.core.Spring
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
    Row(
        Modifier.fillMaxWidth().padding(start = 32.dp, end = 32.dp, top = 30.dp, bottom = 18.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        Column {
            Text(
                title.uppercase(),
                color = GoldTarnished,
                fontSize = 26.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Serif,
                letterSpacing = 3.sp
            )
            Text(subtitle.uppercase(), color = TextMuted, fontSize = 9.sp, letterSpacing = 3.sp)
        }
        Spacer(Modifier.weight(1f))
        trailing()
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
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.verticalGradient(listOf(SurfaceGem, SurfaceStone)))
            .border(1.dp, accent.copy(0.2f), RoundedCornerShape(16.dp))
            .padding(20.dp),
        content = content
    )
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
