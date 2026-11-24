package com.txl.mebot.ui.trading

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import com.txl.mebot.R
import com.txl.mebot.data.trading.TradingContract
import com.txl.mebot.data.trading.TradingProblem
import com.txl.mebot.data.trading.tradingProblem
import kotlinx.coroutines.launch

@Composable
internal fun TradingAddContractDialog(
    onDismiss: () -> Unit,
    onAdd: suspend (String) -> TradingContract,
    onAdded: (TradingContract) -> Unit,
) {
    var input by rememberSaveable { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<TradingProblem?>(null) }
    val scope = rememberCoroutineScope()
    val submit: () -> Unit = {
        if (!adding && input.isNotBlank()) {
            adding = true
            problem = null
            val entered = input
            scope.launch {
                try {
                    onAdded(onAdd(entered))
                } catch (error: Exception) {
                    problem = error.tradingProblem()
                    adding = false
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!adding) onDismiss() },
        title = { Text(stringResource(R.string.trading_add_contract)) },
        text = {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it; problem = null },
                modifier = Modifier.fillMaxWidth(),
                enabled = !adding,
                label = { Text(stringResource(R.string.trading_add_contract_input)) },
                placeholder = { Text(stringResource(R.string.trading_add_contract_example)) },
                isError = problem != null,
                supportingText = problem?.let { issue -> { Text(issue.message) } },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { submit() }),
            )
        },
        confirmButton = {
            TextButton(onClick = submit, enabled = !adding && input.isNotBlank()) {
                Text(stringResource(if (adding) R.string.trading_adding_contract else R.string.trading_add_contract_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !adding) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}
