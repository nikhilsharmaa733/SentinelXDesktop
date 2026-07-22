package com.nikhil.sentinelx.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.nikhil.sentinelx.desktop.ui.theme.*

/**
 * Shared editor chrome, so every add/edit form looks and behaves the same.
 *
 * Save is disabled until [canSave], rather than saving something invalid and
 * complaining afterwards.
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
    Dialog(onDismissRequest = onCancel) {
        Column(
            Modifier
                .width(width.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(BackgroundDeep)
                .border(1.dp, GoldDark.copy(0.35f), RoundedCornerShape(20.dp))
                .padding(26.dp)
        ) {
            Text(
                title.uppercase(),
                color = GoldTarnished,
                fontSize = 17.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Serif,
                letterSpacing = 2.sp
            )
            Spacer(Modifier.height(18.dp))

            Column(
                Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                content = content
            )

            Spacer(Modifier.height(22.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
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
                unfocusedContainerColor = SurfaceStone
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
