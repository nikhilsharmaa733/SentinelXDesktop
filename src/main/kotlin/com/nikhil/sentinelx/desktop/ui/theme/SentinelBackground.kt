package com.nikhil.sentinelx.desktop.ui.theme

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Three-layer drifting nebula, adapted from the phone's `SentinelBackground`.
 *
 * The drift is slower here (26s vs 18s) and the alphas are lower. What reads as
 * atmospheric on a 6-inch screen becomes a distraction across a 27-inch monitor
 * where it fills your peripheral vision — the effect should register when you look
 * for it and disappear when you are working.
 */
@Composable
fun SentinelBackground(content: @Composable () -> Unit) {
    val transition = rememberInfiniteTransition(label = "nebula")

    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(26_000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "phase"
    )

    Box(
        Modifier
            .fillMaxSize()
            .background(BackgroundVoid)
            // drawWithCache rebuilds the brushes only when size or phase changes,
            // rather than allocating three gradients on every frame.
            .drawWithCache {
                val gold = Brush.radialGradient(
                    colors = listOf(GoldDim.copy(alpha = 0.10f + phase * 0.03f), Color.Transparent),
                    center = Offset(size.width * (0.12f + phase * 0.10f), size.height * (0.18f + phase * 0.12f)),
                    radius = size.width * 0.95f
                )
                val cyan = Brush.radialGradient(
                    colors = listOf(CyanGlowDim.copy(alpha = 0.06f + phase * 0.025f), Color.Transparent),
                    center = Offset(size.width * (0.88f - phase * 0.14f), size.height * (0.80f - phase * 0.09f)),
                    radius = size.width * 0.8f
                )
                val lift = Brush.radialGradient(
                    colors = listOf(Color(0xFF12121A).copy(alpha = 0.5f), Color.Transparent),
                    center = Offset(size.width * 0.45f, size.height * 0.4f),
                    radius = size.minDimension * 0.9f
                )
                onDrawBehind {
                    drawRect(gold)
                    drawRect(cyan)
                    drawRect(lift)
                }
            }
    ) {
        content()
    }
}

/**
 * A rune watermark for a pane corner — the phone's signature flourish.
 *
 * Drawn at very low alpha so it reads as texture rather than content. Anything
 * bolder competes with the data, which is what you actually came to look at.
 */
@Composable
fun RuneWatermark(glyph: String, modifier: Modifier = Modifier) {
    androidx.compose.material3.Text(
        text = glyph,
        color = GoldDark.copy(alpha = 0.055f),
        fontSize = androidx.compose.ui.unit.TextUnit(148f, androidx.compose.ui.unit.TextUnitType.Sp),
        modifier = modifier
    )
}
