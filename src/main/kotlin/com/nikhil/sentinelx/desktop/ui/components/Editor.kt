package com.nikhil.sentinelx.desktop.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.nikhil.sentinelx.desktop.ui.theme.*
import java.awt.Cursor

/**
 * The handle a floating panel gives to whatever it is hosting.
 *
 * Null when the same composable is being shown as an ordinary modal dialog, which is
 * what [EditorDialog] falls back to when nobody is hosting panels — so an editor never
 * has to know which of the two it is in.
 */
class PanelScope(
    /** True for the panel on top of the stack. Only that one wears the bright border. */
    val focused: Boolean,
    /** Width in px once the user has resized, or null for the editor's own default. */
    val width: Float?,
    /** Scroll-area height in px once resized, or null for the default capped one. */
    val contentHeight: Float?,
    val onDrag: (Offset) -> Unit,
    /**
     * Called once as a resize starts, carrying the size the panel currently has, so the
     * first pixel of drag continues from where the panel already is instead of snapping.
     */
    val onResizeBegin: (width: Float, contentHeight: Float) -> Unit,
    val onResize: (Offset) -> Unit
)

// Deliberately not `staticCompositionLocalOf`: the scope carries the live resize
// dimensions, and a static local would recompose the whole editor subtree on every
// frame of a drag rather than only the parts that read it.
val LocalPanelScope = compositionLocalOf<PanelScope?> { null }

private val moveCursor = PointerIcon(Cursor(Cursor.MOVE_CURSOR))
private val resizeCursor = PointerIcon(Cursor(Cursor.SE_RESIZE_CURSOR))

/**
 * Shared editor chrome, so every add/edit form looks and behaves the same.
 *
 * Save is disabled until [canSave], rather than saving something invalid and
 * complaining afterwards.
 *
 * Renders as a **floating panel** when a [PanelScope] is in scope, and as a modal
 * dialog when there is none. The name is now half a lie, but every editor in the app
 * calls this and the alternative was touching seven files to rename it.
 */
@Composable
fun EditorDialog(
    title: String,
    canSave: Boolean,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onDelete: (() -> Unit)? = null,
    width: Int = 560,
    content: @Composable ColumnScope.() -> Unit
) {
    val panel = LocalPanelScope.current
    if (panel == null) {
        Dialog(onDismissRequest = onCancel) {
            EditorSurface(title, canSave, onSave, onCancel, onDelete, width, null, content)
        }
    } else {
        EditorSurface(title, canSave, onSave, onCancel, onDelete, width, panel, content)
    }
}

@Composable
private fun EditorSurface(
    title: String,
    canSave: Boolean,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onDelete: (() -> Unit)?,
    width: Int,
    panel: PanelScope?,
    content: @Composable ColumnScope.() -> Unit
) {
    val lit = panel == null || panel.focused
    val density = LocalDensity.current

    // Natural sizes, in px, handed to the grip so a resize starts from where the panel
    // already sits. Height is measured rather than assumed because a short form is
    // shorter than the 460dp cap.
    val naturalWidth = with(density) { width.dp.toPx() }
    var naturalContentHeight by remember { mutableStateOf(0f) }

    // The gesture blocks below are keyed on Unit and must never be re-keyed: Compose
    // tears a pointerInput down and rebuilds it whenever its key changes, which cancels
    // any drag in flight. Keying the grip on the measured height did exactly that — the
    // resize changed the height, the height changed the key, and the drag died about a
    // centimetre in. The scope is rebuilt on every recomposition, so it is read through
    // a ref instead of being captured.
    val live = rememberUpdatedState(panel)
    val naturalSize = rememberUpdatedState(naturalWidth to naturalContentHeight)

    // Wrapper so the resize grip can sit on the panel's bottom-right corner.
    Box {
        Column(
            Modifier
                .width(panel?.width?.let { with(density) { it.toDp() } } ?: width.dp)
                // Panels overlap each other and the pane behind them; without a shadow the
                // stack reads as one flat collage rather than as separate sheets.
                .then(if (panel != null) Modifier.shadow(30.dp, RoundedCornerShape(20.dp)) else Modifier)
                .clip(RoundedCornerShape(20.dp))
                .background(BackgroundDeep)
                .border(
                    1.dp,
                    if (lit) GoldDark.copy(0.35f) else GoldDark.copy(0.14f),
                    RoundedCornerShape(20.dp)
                )
        ) {
            // ── Title bar — the drag handle ──────────────────────────────────────
            Row(
                Modifier
                    .fillMaxWidth()
                    .then(
                        if (panel == null) Modifier
                        else Modifier
                            .pointerHoverIcon(moveCursor)
                            .pointerInput(Unit) {
                                detectDragGestures { change, drag ->
                                    change.consume()
                                    live.value?.onDrag(drag)
                                }
                            }
                    )
                    .padding(start = 26.dp, end = 12.dp, top = 22.dp, bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    title.uppercase(),
                    color = if (lit) GoldTarnished else GoldDark,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Serif,
                    letterSpacing = 2.sp
                )
                if (panel != null) {
                    Spacer(Modifier.weight(1f))
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onCancel() }
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text("✕", color = TextMuted, fontSize = 13.sp)
                    }
                }
            }

            if (panel != null) {
                HorizontalDivider(color = GoldDark.copy(0.15f))
                Spacer(Modifier.height(16.dp))
            }

            Column(
                Modifier
                    .padding(horizontal = 26.dp)
                    .then(
                        panel?.contentHeight
                            ?.let { Modifier.height(with(density) { it.toDp() }) }
                            ?: Modifier.heightIn(max = 460.dp)
                    )
                    // Only while the panel is still its natural size. Once resized the
                    // height is dictated rather than measured, and feeding it back would
                    // make the grip chase its own output.
                    .onSizeChanged {
                        if (panel?.contentHeight == null) naturalContentHeight = it.height.toFloat()
                    }
                    .verticalScroll(rememberScrollState()),
                content = content
            )

            Row(
                Modifier.fillMaxWidth().padding(start = 26.dp, end = 26.dp, top = 22.dp, bottom = 24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Text("DELETE", color = ExpenseRed, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onCancel) {
                    Text("CANCEL", color = TextMuted, fontSize = 11.sp)
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (canSave) Brush.linearGradient(listOf(GoldBright, GoldTarnished))
                            else Brush.linearGradient(listOf(SurfaceGem, SurfaceStone))
                        )
                        .clickable(enabled = canSave) { onSave() }
                        .padding(horizontal = 22.dp, vertical = 10.dp)
                ) {
                    Text(
                        "SAVE",
                        color = if (canSave) BackgroundVoid else TextMuted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp
                    )
                }
            }
        }

        if (panel != null) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(22.dp)
                    .pointerHoverIcon(resizeCursor)
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = {
                                val (w, h) = naturalSize.value
                                live.value?.onResizeBegin(w, h)
                            }
                        ) { change, drag ->
                            change.consume()
                            live.value?.onResize(drag)
                        }
                    }
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    // Three stacked ticks, the usual corner-grip idiom.
                    val tint = GoldDark.copy(0.55f)
                    repeat(3) { i ->
                        val inset = 3.dp.toPx() + i * 4.dp.toPx()
                        drawLine(
                            color = tint,
                            start = Offset(size.width - inset, size.height - 3.dp.toPx()),
                            end = Offset(size.width - 3.dp.toPx(), size.height - inset),
                            strokeWidth = 1.dp.toPx()
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun EditorField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = true,
    minLines: Int = 1,
    accent: Color = GoldTarnished,
    enabled: Boolean = true,
    trailing: @Composable (() -> Unit)? = null
) {
    Column(modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Text(
            label.uppercase(),
            color = accent,
            fontSize = 8.sp,
            letterSpacing = 1.5.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 2.dp, bottom = 5.dp)
        )
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            singleLine = singleLine,
            minLines = minLines,
            shape = RoundedCornerShape(11.dp),
            placeholder = { Text(placeholder, color = TextMuted, fontSize = 12.sp) },
            trailingIcon = trailing,
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = CyanGlow.copy(0.6f),
                unfocusedBorderColor = GoldDark.copy(0.25f),
                focusedTextColor = TextParchment,
                unfocusedTextColor = TextParchment,
                cursorColor = CyanGlow,
                focusedContainerColor = SurfaceGem,
                unfocusedContainerColor = SurfaceStone,
                disabledTextColor = TextMuted,
                disabledBorderColor = GoldDark.copy(0.12f),
                disabledContainerColor = SurfaceStone.copy(0.5f)
            )
        )
    }
}

/** Confirms an irreversible delete. Named so the user sees what they are destroying. */
@Composable
fun ConfirmDelete(itemName: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BackgroundDeep,
        shape = RoundedCornerShape(18.dp),
        title = {
            Text(
                "DELETE?",
                color = ExpenseRed,
                fontSize = 15.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.sp
            )
        },
        text = {
            Text(
                "\"$itemName\" will be removed from the vault. This cannot be undone.",
                color = TextSubtle,
                fontSize = 13.sp,
                lineHeight = 19.sp
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = ExpenseRed),
                shape = RoundedCornerShape(9.dp)
            ) { Text("DELETE", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("CANCEL", color = TextMuted, fontSize = 12.sp) }
        }
    )
}

/** Floating add button, bottom-right of a pane. */
@Composable
fun AddButton(onClick: () -> Unit, label: String = "+") {
    Box(
        Modifier
            .size(50.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(Brush.linearGradient(listOf(GoldBright, GoldTarnished)))
            .border(1.dp, GoldIce.copy(0.3f), RoundedCornerShape(15.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = BackgroundVoid, fontSize = 22.sp, fontWeight = FontWeight.Black)
    }
}
