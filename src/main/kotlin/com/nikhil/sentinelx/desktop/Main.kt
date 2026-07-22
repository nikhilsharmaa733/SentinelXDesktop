package com.nikhil.sentinelx.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.nikhil.sentinelx.desktop.ui.AppShell
import com.nikhil.sentinelx.desktop.ui.AppState
import com.nikhil.sentinelx.desktop.ui.UnlockScreen
import com.nikhil.sentinelx.desktop.ui.theme.*

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "SentinelX",
        state = rememberWindowState(width = 1180.dp, height = 760.dp)
    ) {
        val state = remember { AppState() }

        MaterialTheme(
            colorScheme = darkColorScheme(
                primary = GoldTarnished,
                secondary = CyanGlow,
                background = BackgroundDeep,
                surface = SurfaceStone,
                onPrimary = BackgroundVoid,
                onBackground = TextParchment,
                onSurface = TextParchment,
                error = ExpenseRed
            )
        ) {
            Box(Modifier.fillMaxSize().background(BackgroundDeep)) {
                // Crossfade between the gate and the shell rather than swapping
                // instantly — the unlock is slow enough (Argon2id) that a hard cut
                // reads as a glitch.
                AnimatedVisibility(
                    visible = state.locked,
                    enter = fadeIn(tween(400)),
                    exit = fadeOut(tween(400))
                ) {
                    UnlockScreen(state) { /* state.locked drives the switch */ }
                }
                AnimatedVisibility(
                    visible = !state.locked,
                    enter = fadeIn(tween(500)),
                    exit = fadeOut(tween(250))
                ) {
                    AppShell(state)
                }
            }
        }
    }
}
