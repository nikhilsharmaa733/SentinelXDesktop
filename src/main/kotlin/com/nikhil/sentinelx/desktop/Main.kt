package com.nikhil.sentinelx.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

/**
 * Toolchain smoke test. Replaced by the real shell once core/format is proven.
 *
 * Deliberately the first thing built: if Compose Desktop cannot resolve and open
 * a window on this machine, nothing else in the plan is worth writing.
 */
fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "SentinelX",
        state = rememberWindowState(width = 1100.dp, height = 720.dp)
    ) {
        SmokeTestScreen()
    }
}

@Composable
private fun SmokeTestScreen() {
    Box(
        Modifier.fillMaxSize().background(Color(0xFF070709)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "ᚠ",
                color = Color(0xFFD4A853),
                fontSize = 64.sp
            )
            Text(
                "SENTINEL X",
                color = Color(0xFFD4A853),
                fontSize = 34.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Serif,
                letterSpacing = 6.sp
            )
            Text(
                "DESKTOP · TOOLCHAIN VERIFIED",
                color = Color(0xFF00E5FF),
                fontSize = 11.sp,
                letterSpacing = 3.sp
            )
        }
    }
}
