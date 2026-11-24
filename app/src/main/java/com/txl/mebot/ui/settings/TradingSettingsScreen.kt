package com.txl.mebot.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.txl.mebot.BuildConfig
import com.txl.mebot.R
import com.txl.mebot.data.trading.alerts.ProbeAlertException
import com.txl.mebot.data.trading.alerts.TradingAlertsRepository
import com.txl.mebot.ui.common.hapticAction
import com.txl.mebot.ui.trading.TradingConnectionDialog
import com.txl.mebot.ui.trading.rememberTradingViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun TradingSettingsScreen(onBack: () -> Unit) {
    val model = rememberTradingViewModel()
    val configuration by model.connectionConfiguration.collectAsStateWithLifecycle(initialValue = null)
    val configured = configuration?.getOrNull()
    val loadFailed = configuration?.isFailure == true
    val alertEndpoint by model.priceAlerts.endpoint.collectAsStateWithLifecycle(initialValue = null)
    var showConnection by remember { mutableStateOf(false) }
    var showAlertConnection by rememberSaveable { mutableStateOf(false) }
    val colors = settingsColors()
    val title = stringResource(R.string.trading_title)
    val status = stringResource(
        when {
            loadFailed -> R.string.trading_binance_configuration_failed
            configured == true -> R.string.trading_binance_configured
            configured == false -> R.string.trading_binance_disconnected
            else -> R.string.trading_binance_configuration_loading
        },
    )

    Column(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(colors.backgroundTop, colors.backgroundBottom)))
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .semantics { paneTitle = title },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(start = 12.dp, top = 6.dp, end = 12.dp)) {
            IconButton(
                onClick = hapticAction(onBack),
                modifier = Modifier.size(48.dp),
                shape = CircleShape,
                colors = IconButtonDefaults.iconButtonColors(contentColor = colors.ink),
            ) {
                Icon(
                    painterResource(R.drawable.ic_arrow_back),
                    stringResource(R.string.navigate_back),
                    Modifier.size(24.dp),
                )
            }
        }
        Column(
            Modifier.widthIn(max = 720.dp).fillMaxWidth().weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, top = 8.dp, end = 24.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                title,
                Modifier.semantics { heading() },
                color = colors.ink,
                fontSize = 30.sp,
                lineHeight = 40.sp,
                fontWeight = FontWeight.Bold,
            )
            TradingSettingsItem(
                title = stringResource(R.string.trading_binance_connection),
                detail = status,
                enabled = configured != null,
                error = loadFailed,
                onClick = { showConnection = true },
            )
            TradingSettingsItem(
                title = stringResource(R.string.trading_alert_connection),
                detail = stringResource(
                    if (alertEndpoint == null) R.string.trading_alert_connection_missing
                    else R.string.trading_alert_connection_configured,
                ),
                onClick = { showAlertConnection = true },
            )
        }
    }

    if (showConnection && configured != null) {
        TradingConnectionDialog(configured, onDismiss = { showConnection = false }, model)
    }
    if (showAlertConnection) {
        TradingAlertConnectionDialog(
            address = alertEndpoint ?: BuildConfig.DEFAULT_PROBE_ENDPOINT,
            repository = model.priceAlerts,
            onDismiss = { showAlertConnection = false },
        )
    }
}

@Composable
private fun TradingSettingsItem(
    title: String,
    detail: String,
    enabled: Boolean = true,
    error: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = settingsColors()
    Surface(
        onClick = hapticAction(onClick),
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = colors.surface,
        contentColor = colors.ink,
    ) {
        Row(
            Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    detail, fontSize = 13.sp,
                    color = if (error) MaterialTheme.colorScheme.error else colors.secondary,
                )
            }
            Icon(painterResource(R.drawable.ic_chevron_right), null, Modifier.size(20.dp), tint = colors.chevron)
        }
    }
}

@Composable
private fun TradingAlertConnectionDialog(
    address: String,
    repository: TradingAlertsRepository,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var endpoint by rememberSaveable { mutableStateOf(address) }
    var token by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var registered by remember { mutableStateOf<Boolean?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val genericError = stringResource(R.string.trading_alert_connect_failed)

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.trading_alert_connection)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = endpoint,
                    onValueChange = {
                        endpoint = it.take(512)
                        registered = null
                        error = null
                    },
                    modifier = Modifier.fillMaxWidth(), enabled = !busy, singleLine = true,
                    label = { Text(stringResource(R.string.trading_alert_endpoint)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = {
                        token = it.take(256)
                        registered = null
                        error = null
                    },
                    modifier = Modifier.fillMaxWidth(), enabled = !busy, singleLine = true,
                    label = { Text(stringResource(R.string.trading_alert_access_token)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    visualTransformation = PasswordVisualTransformation(),
                )
                error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                registered?.let { ready ->
                    Text(
                        stringResource(
                            if (ready) R.string.trading_alert_device_registered
                            else R.string.trading_alert_server_push_disabled,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && endpoint.isNotBlank() && token.isNotBlank(),
                onClick = hapticAction {
                    if (busy) return@hapticAction
                    busy = true
                    registered = null
                    error = null
                    scope.launch {
                        try {
                            registered = repository.connect(endpoint, token)
                            token = ""
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (problem: ProbeAlertException) {
                            error = problem.detail
                        } catch (_: Exception) {
                            error = genericError
                        } finally {
                            busy = false
                        }
                    }
                },
            ) {
                Text(stringResource(if (busy) R.string.trading_alert_connecting else R.string.trading_alert_connect))
            }
        },
    )
}
