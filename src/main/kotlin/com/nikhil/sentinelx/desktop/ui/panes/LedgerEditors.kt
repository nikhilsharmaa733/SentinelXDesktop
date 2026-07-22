package com.nikhil.sentinelx.desktop.ui.panes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nikhil.sentinelx.desktop.core.format.AccountEntity
import com.nikhil.sentinelx.desktop.core.format.TransactionEntity
import com.nikhil.sentinelx.desktop.ui.AppState
import com.nikhil.sentinelx.desktop.ui.components.*
import com.nikhil.sentinelx.desktop.ui.theme.*

private val accountColors = listOf(
    "#D4A853", "#00E5FF", "#2ED573", "#FF4757", "#BB86FC", "#E8A830", "#5352ED", "#00B4D8"
)

private val categories = listOf(
    "MISC", "FOOD", "TRANSPORT", "BILLS", "SHOPPING", "HEALTH",
    "RENT", "SALARY", "GIFT", "ENTERTAINMENT", "EDUCATION"
)

@Composable
fun AccountEditor(state: AppState, existing: AccountEntity?, onClose: () -> Unit) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var colorHex by remember { mutableStateOf(existing?.colorHex ?: accountColors.first()) }
    var confirmDelete by remember { mutableStateOf(false) }

    // The phone's accounts table is UNIQUE on name.
    val clash = remember(name, state.backup.accounts) {
        name.isNotBlank() && state.backup.accounts.any {
            it.name.equals(name.trim(), true) && it.id != existing?.id
        }
    }

    val linkedCount = remember(existing, state.backup.ledger) {
        existing?.let { acc -> state.backup.ledger.count { it.accountId == acc.id } } ?: 0
    }

    EditorDialog(
        title = if (existing == null) "New Account" else "Edit Account",
        canSave = name.isNotBlank() && !clash,
        onSave = {
            state.upsertAccount(
                AccountEntity(
                    id = existing?.id ?: 0L,
                    name = name.trim(),
                    colorHex = colorHex,
                    sigilType = existing?.sigilType ?: "VAULT",
                    timestamp = existing?.timestamp ?: 0L
                )
            )
            onClose()
        },
        onCancel = onClose,
        onDelete = existing?.let { { confirmDelete = true } },
        width = 460
    ) {
        EditorField(name, { name = it }, "Account Name", placeholder = "HDFC Savings")

        if (clash) {
            Text(
                "⚠ An account with this name already exists. The phone keys accounts by " +
                    "name, so restoring would merge them.",
                color = ExpenseRed, fontSize = 11.sp, lineHeight = 16.sp,
                modifier = Modifier.padding(bottom = 10.dp)
            )
        }

        Text("COLOUR", color = GoldTarnished, fontSize = 8.sp, letterSpacing = 1.5.sp,
            modifier = Modifier.padding(start = 2.dp, bottom = 8.dp))
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
            accountColors.forEach { hex ->
                val parsed = runCatching { Color(hex.removePrefix("#").toLong(16) or 0xFF000000) }
                    .getOrDefault(GoldTarnished)
                Box(
                    Modifier.padding(end = 9.dp).size(30.dp).clip(CircleShape).background(parsed)
                        .border(
                            width = if (colorHex == hex) 2.dp else 0.dp,
                            color = GoldIce,
                            shape = CircleShape
                        )
                        .clickable { colorHex = hex }
                )
            }
        }
    }

    if (confirmDelete && existing != null) {
        ConfirmDelete(
            // Deleting an account takes its transactions with it, so say how many.
            itemName = if (linkedCount > 0) "${existing.name} and its $linkedCount transaction(s)"
            else existing.name,
            onConfirm = { state.deleteAccount(existing.id); confirmDelete = false; onClose() },
            onDismiss = { confirmDelete = false }
        )
    }
}

@Composable
fun TransactionEditor(
    state: AppState,
    existing: TransactionEntity?,
    defaultAccountId: Long?,
    onClose: () -> Unit
) {
    val accounts = state.backup.accounts
    var accountId by remember {
        mutableStateOf(existing?.accountId ?: defaultAccountId ?: accounts.firstOrNull()?.id ?: 0L)
    }
    var title by remember { mutableStateOf(existing?.title ?: "") }
    var amountText by remember { mutableStateOf(existing?.amount?.toString() ?: "") }
    var incoming by remember { mutableStateOf(existing?.isIncoming ?: false) }
    var category by remember { mutableStateOf(existing?.category ?: "MISC") }
    var settled by remember { mutableStateOf(existing?.isSettled ?: false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var accountMenu by remember { mutableStateOf(false) }
    var categoryMenu by remember { mutableStateOf(false) }

    val amount = amountText.toDoubleOrNull()

    EditorDialog(
        title = if (existing == null) "New Transaction" else "Edit Transaction",
        canSave = title.isNotBlank() && amount != null && amount > 0 && accounts.isNotEmpty(),
        onSave = {
            state.upsertTransaction(
                TransactionEntity(
                    id = existing?.id ?: 0L,
                    accountId = accountId,
                    title = title.trim(),
                    amount = amount ?: 0.0,
                    isIncoming = incoming,
                    category = category,
                    // Preserve the original timestamp when editing: the phone's unique
                    // index includes it, so changing it would create a second row
                    // rather than updating the existing one on restore.
                    timestamp = existing?.timestamp ?: 0L,
                    isSettled = settled,
                    billImageUris = existing?.billImageUris
                )
            )
            onClose()
        },
        onCancel = onClose,
        onDelete = existing?.let { { confirmDelete = true } },
        width = 520
    ) {
        if (accounts.isEmpty()) {
            Text(
                "Create an account first — every transaction belongs to one.",
                color = AmberWarn, fontSize = 12.sp, modifier = Modifier.padding(bottom = 10.dp)
            )
        }

        // Direction
        Row(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
            listOf(false to "EXPENSE", true to "INCOME").forEach { (isIn, label) ->
                val on = incoming == isIn
                val tone = if (isIn) IncomeGreen else ExpenseRed
                Box(
                    Modifier.weight(1f).padding(end = 8.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (on) tone.copy(0.18f) else SurfaceStone)
                        .border(1.dp, if (on) tone.copy(0.5f) else GoldDark.copy(0.15f), RoundedCornerShape(10.dp))
                        .clickable { incoming = isIn }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label,
                        color = if (on) tone else TextMuted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                }
            }
        }

        EditorField(title, { title = it }, "Description", placeholder = "Groceries")
        EditorField(
            amountText, { amountText = it.filter { c -> c.isDigit() || c == '.' } },
            "Amount",
            placeholder = "0.00",
            accent = if (incoming) IncomeGreen else ExpenseRed
        )
        if (amountText.isNotBlank() && amount == null) {
            Text("Enter a valid number.", color = ExpenseRed, fontSize = 11.sp,
                modifier = Modifier.padding(bottom = 8.dp))
        }

        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            // Account
            Column(Modifier.weight(1f).padding(end = 10.dp)) {
                Text("ACCOUNT", color = GoldTarnished, fontSize = 8.sp, letterSpacing = 1.5.sp,
                    modifier = Modifier.padding(bottom = 5.dp))
                Box {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(SurfaceGem)
                            .border(1.dp, GoldDark.copy(0.25f), RoundedCornerShape(10.dp))
                            .clickable { accountMenu = true }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            accounts.firstOrNull { it.id == accountId }?.name ?: "—",
                            color = TextParchment, fontSize = 12.sp, maxLines = 1,
                            modifier = Modifier.weight(1f)
                        )
                        Text("▾", color = TextMuted, fontSize = 10.sp)
                    }
                    DropdownMenu(accountMenu, onDismissRequest = { accountMenu = false }) {
                        accounts.forEach { acc ->
                            DropdownMenuItem(
                                text = { Text(acc.name, fontSize = 12.sp) },
                                onClick = { accountId = acc.id; accountMenu = false }
                            )
                        }
                    }
                }
            }
            // Category
            Column(Modifier.weight(1f)) {
                Text("CATEGORY", color = GoldTarnished, fontSize = 8.sp, letterSpacing = 1.5.sp,
                    modifier = Modifier.padding(bottom = 5.dp))
                Box {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(SurfaceGem)
                            .border(1.dp, GoldDark.copy(0.25f), RoundedCornerShape(10.dp))
                            .clickable { categoryMenu = true }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(category, color = TextParchment, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        Text("▾", color = TextMuted, fontSize = 10.sp)
                    }
                    DropdownMenu(categoryMenu, onDismissRequest = { categoryMenu = false }) {
                        categories.forEach { c ->
                            DropdownMenuItem(
                                text = { Text(c, fontSize = 12.sp) },
                                onClick = { category = c; categoryMenu = false }
                            )
                        }
                    }
                }
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable { settled = !settled }
        ) {
            Checkbox(
                checked = settled,
                onCheckedChange = { settled = it },
                colors = CheckboxDefaults.colors(checkedColor = CyanGlow, uncheckedColor = TextMuted)
            )
            Text("Settled", color = TextSubtle, fontSize = 12.sp)
        }
    }

    if (confirmDelete && existing != null) {
        ConfirmDelete(
            itemName = existing.title.ifBlank { "Untitled" },
            onConfirm = { state.deleteTransaction(existing.id); confirmDelete = false; onClose() },
            onDismiss = { confirmDelete = false }
        )
    }
}
