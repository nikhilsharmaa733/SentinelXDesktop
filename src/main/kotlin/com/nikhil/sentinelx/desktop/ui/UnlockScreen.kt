package com.nikhil.sentinelx.desktop.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nikhil.sentinelx.desktop.ui.components.requestWhenReady
import com.nikhil.sentinelx.desktop.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MIN_PASSWORD = 8

/**
 * Master-password gate — the desktop equivalent of the phone's Seal screen.
 *
 * Branches on whether a vault already exists: first run asks for a new password
 * twice, subsequent runs just unlock.
 */
@Composable
fun UnlockScreen(state: AppState, onUnlocked: () -> Unit) {
    val creating = !state.vaultExists
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }

    LaunchedEffect(Unit) { focus.requestWhenReady() }

    val tooShort = creating && password.isNotEmpty() && password.length < MIN_PASSWORD
    val mismatch = creating && confirm.isNotEmpty() && password != confirm
    val canSubmit = password.length >= (if (creating) MIN_PASSWORD else 1) &&
            (!creating || password == confirm) &&
            state.busy == null

    fun submit() {
        if (!canSubmit) return
        scope.launch {
            // Argon2id is deliberately slow (~1s at 64 MB). Run it off the UI thread,
            // or the window visibly freezes while the key derives.
            val ok = withContext(Dispatchers.Default) {
                if (creating) state.create(password.toCharArray())
                else state.unlock(password.toCharArray())
            }
            password = ""
            confirm = ""
            if (ok) onUnlocked()
        }
    }

    SentinelBackground {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(420.dp)
        ) {
            BreathingRune()

            Spacer(Modifier.height(18.dp))
            Text(
                "SENTINEL X",
                style = androidx.compose.ui.text.TextStyle(
                    brush = Brush.horizontalGradient(listOf(GoldIce, GoldBright, GoldTarnished, GoldDark)),
                    fontSize = 38.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Serif,
                    letterSpacing = 9.sp
                )
            )
            Text(
                if (creating) "FORGE THE SEAL" else "SPEAK THE WORD",
                color = TextMuted,
                fontSize = 10.sp,
                letterSpacing = 4.sp
            )

            Spacer(Modifier.height(36.dp))

            SealField(
                value = password,
                onValueChange = { password = it },
                label = if (creating) "NEW MASTER PASSWORD" else "MASTER PASSWORD",
                modifier = Modifier.focusRequester(focus),
                onSubmit = { if (!creating) submit() }
            )

            if (creating) {
                Spacer(Modifier.height(14.dp))
                SealField(
                    value = confirm,
                    onValueChange = { confirm = it },
                    label = "CONFIRM",
                    onSubmit = { submit() }
                )
            }

            Spacer(Modifier.height(10.dp))

            val message: Pair<String, Color>? = when {
                state.error != null -> state.error!! to ExpenseRed
                mismatch -> "Passwords do not match." to ExpenseRed
                tooShort -> "At least $MIN_PASSWORD characters." to AmberWarn
                creating -> "There is no recovery. Forgetting this loses the vault." to TextMuted
                else -> null
            }
            Box(Modifier.height(34.dp), contentAlignment = Alignment.Center) {
                message?.let { (text, color) ->
                    Text(text, color = color, fontSize = 11.sp, letterSpacing = 0.5.sp)
                }
            }

            GoldButton(
                text = when {
                    state.busy != null -> state.busy!!.uppercase()
                    creating -> "FORGE VAULT"
                    else -> "UNSEAL"
                },
                enabled = canSubmit,
                onClick = { submit() }
            )

            if (creating) {
                Spacer(Modifier.height(20.dp))
                Text(
                    "Vault location: ${state.vaultLocation}",
                    color = TextMuted.copy(0.7f),
                    fontSize = 9.sp
                )
            }
        }
    }
    }
}

@Composable
private fun BreathingRune() {
    val transition = rememberInfiniteTransition(label = "rune")
    val glow by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(2600, easing = LinearEasing), RepeatMode.Reverse
        ),
        label = "glow"
    )
    Box(contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(120.dp).graphicsLayer { alpha = glow * 0.28f }
                .background(
                    Brush.radialGradient(listOf(GoldTarnished, Color.Transparent)),
                    RoundedCornerShape(60.dp)
                )
        )
        Text("ᚠ", color = GoldBright.copy(alpha = glow), fontSize = 62.sp)
    }
}

@Composable
private fun SealField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    onSubmit: () -> Unit = {}
) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            label,
            color = GoldTarnished,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp,
            modifier = Modifier.padding(start = 2.dp, bottom = 6.dp)
        )
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onSubmit() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = CyanGlow.copy(0.7f),
                unfocusedBorderColor = GoldDark.copy(0.3f),
                focusedTextColor = TextParchment,
                unfocusedTextColor = TextParchment,
                cursorColor = CyanGlow,
                focusedContainerColor = SurfaceGem,
                unfocusedContainerColor = SurfaceStone
            )
        )
    }
}

@Composable
private fun GoldButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(50.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (enabled) Brush.linearGradient(listOf(GoldBright, GoldTarnished))
                else Brush.linearGradient(listOf(SurfaceGem, SurfaceStone))
            )
            .border(
                1.dp,
                if (enabled) GoldIce.copy(0.35f) else GoldDark.copy(0.2f),
                RoundedCornerShape(14.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxSize()) {
            Text(
                text,
                color = if (enabled) BackgroundVoid else TextMuted,
                fontWeight = FontWeight.Bold,
                letterSpacing = 3.sp,
                fontSize = 13.sp
            )
        }
    }
}
