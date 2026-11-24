package com.txl.mebot.ui.trading

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.txl.mebot.R
import com.txl.mebot.data.trading.TradingContract
import com.txl.mebot.data.trading.WATCHLIST_CATEGORY_LIMIT
import com.txl.mebot.ui.common.DetailDeleteConfirmationDialog
import com.txl.mebot.ui.common.hapticAction
import kotlinx.coroutines.launch

private enum class WatchlistEditOperation { ICON, CATEGORY, REMOVE }

@Composable
internal fun TradingWatchlistEditor(
    contract: TradingContract,
    model: TradingViewModel,
    category: String?,
    categoryColors: Map<String, WatchlistCategoryColor>,
    onDismiss: () -> Unit,
    onRemoved: () -> Unit,
) {
    val symbol = contract.symbol
    val scope = rememberCoroutineScope()
    var operation by remember(symbol) { mutableStateOf<WatchlistEditOperation?>(null) }
    val busy = operation != null
    var removing by rememberSaveable(symbol) { mutableStateOf(false) }
    var error by remember(symbol) { mutableStateOf<Int?>(null) }
    var selectedCategory by rememberSaveable(symbol) { mutableStateOf(category) }
    var creatingCategory by rememberSaveable(symbol) { mutableStateOf(false) }
    var newCategory by rememberSaveable(symbol) { mutableStateOf("") }
    var pickedColor by rememberSaveable(symbol) { mutableStateOf<WatchlistCategoryColor?>(null) }
    var categoryMenuOpen by remember(symbol) { mutableStateOf(false) }
    val typedCategory = newCategory.trim()
    val categoryValid = !creatingCategory ||
        typedCategory.isNotEmpty() && typedCategory.length <= WATCHLIST_CATEGORY_LIMIT && typedCategory.none { it.isISOControl() }
    val desiredCategory = if (creatingCategory) {
        categoryColors.keys.firstOrNull { it.equals(typedCategory, ignoreCase = true) } ?: typedCategory
    } else selectedCategory
    val previewColor = desiredCategory?.takeIf { categoryValid }?.let { name ->
        pickedColor ?: categoryColors[name] ?: availableWatchlistColor(categoryColors.values)
    }
    val colorToSave = if (desiredCategory != null && desiredCategory !in categoryColors) previewColor else pickedColor
    val customIcon by remember(model, symbol) { model.observeCustomContractIcon(symbol) }
        .collectAsStateWithLifecycle(initialValue = null)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            operation = WatchlistEditOperation.ICON
            error = null
            scope.launch {
                try {
                    if (!model.saveContractIcon(symbol, uri)) error = R.string.trading_watchlist_icon_failed
                } finally {
                    operation = null
                }
            }
        }
    }
    if (removing) {
        DetailDeleteConfirmationDialog(
            title = stringResource(R.string.trading_watchlist_remove_title, contract.asset),
            onDismiss = { removing = false; error = null },
            confirmLabel = stringResource(R.string.trading_watchlist_remove),
            enabled = !busy,
            onConfirm = {
                operation = WatchlistEditOperation.REMOVE
                error = null
                scope.launch {
                    try {
                        if (model.removeContract(symbol)) onRemoved()
                        else error = R.string.trading_watchlist_remove_failed
                    } finally {
                        operation = null
                    }
                }
            },
        ) {
            Text(stringResource(R.string.trading_watchlist_remove_description, symbol))
            error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
        }
        return
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.trading_watchlist_edit_title, contract.asset)) },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ContractIcon(contract, customIcon, iconSize = 56.dp)
                OutlinedButton(enabled = !busy, onClick = hapticAction {
                    try {
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    } catch (_: android.content.ActivityNotFoundException) {
                        error = R.string.trading_watchlist_icon_failed
                    }
                }) {
                    val label = if (operation == WatchlistEditOperation.ICON) R.string.trading_watchlist_icon_saving
                        else R.string.trading_watchlist_pick_icon
                    Text(stringResource(label))
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.trading_watchlist_category), style = MaterialTheme.typography.labelLarge)
                    Box(Modifier.weight(1f)) {
                        OutlinedButton(
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !busy,
                            onClick = hapticAction { categoryMenuOpen = true },
                        ) {
                            val label = when {
                                creatingCategory -> stringResource(R.string.trading_watchlist_category_new)
                                else -> selectedCategory ?: stringResource(R.string.trading_watchlist_uncategorized)
                            }
                            Text(label, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Icon(painterResource(R.drawable.ic_chevron_right), null, Modifier.size(15.dp).rotate(90f))
                        }
                        DropdownMenu(expanded = categoryMenuOpen, onDismissRequest = { categoryMenuOpen = false }) {
                            fun choose(name: String?) {
                                if (creatingCategory || selectedCategory != name) pickedColor = null
                                selectedCategory = name
                                creatingCategory = false
                                categoryMenuOpen = false
                                error = null
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.trading_watchlist_uncategorized)) },
                                onClick = hapticAction { choose(null) },
                            )
                            categoryColors.keys.forEach { name ->
                                DropdownMenuItem(text = { Text(name) }, onClick = hapticAction { choose(name) })
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.trading_watchlist_category_new)) },
                                onClick = hapticAction {
                                    if (!creatingCategory) pickedColor = null
                                    creatingCategory = true
                                    categoryMenuOpen = false
                                    error = null
                                },
                            )
                        }
                    }
                }
                if (creatingCategory) {
                    val invalid = typedCategory.isNotEmpty() && !categoryValid
                    OutlinedTextField(
                        value = newCategory,
                        onValueChange = { newCategory = it; error = null },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy,
                        label = { Text(stringResource(R.string.trading_watchlist_category_name)) },
                        singleLine = true,
                        isError = invalid,
                        supportingText = if (invalid) {
                            { Text(stringResource(R.string.trading_watchlist_category_invalid, WATCHLIST_CATEGORY_LIMIT)) }
                        } else null,
                    )
                }
                if (previewColor != null) {
                    TradingWatchlistColorPicker(previewColor, enabled = !busy) {
                        pickedColor = it
                        error = null
                    }
                }
                error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = hapticAction { removing = true; error = null }) {
                Text(stringResource(R.string.trading_watchlist_remove), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && categoryValid, onClick = hapticAction {
                if (desiredCategory == category && colorToSave == null) {
                    onDismiss()
                } else {
                    operation = WatchlistEditOperation.CATEGORY
                    error = null
                    scope.launch {
                        try {
                            if (model.saveContractCategory(symbol, desiredCategory, colorToSave)) onDismiss()
                            else error = R.string.trading_watchlist_category_failed
                        } finally {
                            operation = null
                        }
                    }
                }
            }) {
                Text(stringResource(R.string.trading_watchlist_done))
            }
        },
    )
}

@Composable
internal fun TradingWatchlistColorPicker(
    color: WatchlistCategoryColor,
    enabled: Boolean,
    onSelect: (WatchlistCategoryColor) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(8.dp)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.trading_watchlist_category_background), style = MaterialTheme.typography.labelLarge)
        FlowRow(
            modifier = Modifier.selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            maxItemsInEachRow = 4,
        ) {
            for (option in WatchlistCategoryColor.entries) {
                val selected = option == color
                val name = stringResource(option.label)
                Box(
                    Modifier.size(44.dp).clip(shape).background(option.background)
                        .border(if (selected) 2.dp else 1.dp, if (selected) colors.primary else colors.outlineVariant, shape)
                        .selectable(
                            selected = selected, enabled = enabled, role = Role.RadioButton,
                            onClick = hapticAction { onSelect(option) },
                        )
                        .semantics { contentDescription = name },
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) {
                        Box(Modifier.size(9.dp).background(colors.primary, CircleShape))
                    }
                }
            }
        }
    }
}
