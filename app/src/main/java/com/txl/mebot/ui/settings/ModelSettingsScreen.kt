package com.txl.mebot.ui.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
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
import com.txl.mebot.R
import com.txl.mebot.ui.archive.ArchiveSearchSettingsEntry
import com.txl.mebot.data.models.ConnectionResultKind
import com.txl.mebot.data.models.ModelConnectionResult
import com.txl.mebot.data.models.ModelRole

@Composable
fun ModelSettingsScreen(onBack: () -> Unit, model: ModelSettingsViewModel?) {
    val state = model?.state?.collectAsStateWithLifecycle()?.value ?: ModelSettingsState()
    val colors = settingsColors()
    val title = stringResource(R.string.settings_models_title)
    LaunchedEffect(model) { model?.loadIfNeeded() }
    DisposableEffect(model) { onDispose { model?.stopWaiting() } }
    Column(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(colors.backgroundTop, colors.backgroundBottom)))
            .windowInsetsPadding(WindowInsets.safeDrawing).imePadding().semantics { paneTitle = title },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(start = 12.dp, top = 6.dp, end = 12.dp)) {
            IconButton(
                onClick = onBack, modifier = Modifier.size(48.dp), shape = CircleShape,
                colors = IconButtonDefaults.iconButtonColors(contentColor = colors.ink),
            ) { Icon(painterResource(R.drawable.ic_arrow_back), stringResource(R.string.navigate_back), Modifier.size(24.dp)) }
        }
        LazyColumn(
            modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(start = 24.dp, top = 8.dp, end = 24.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "title") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(title, Modifier.semantics { heading() }, fontSize = 30.sp, lineHeight = 40.sp, fontWeight = FontWeight.Bold, color = colors.ink)
                    Text(stringResource(R.string.models_intro), color = colors.secondary, fontSize = 13.sp, lineHeight = 20.sp)
                }
            }
            if (state.loading) item(key = "loading") {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(26.dp), strokeWidth = 2.dp)
                }
            }
            item(key = "key") {
                ModelSettingsCard(stringResource(R.string.models_api_key)) {
                    Text(stringResource(if (state.hasApiKey) R.string.models_key_present else R.string.models_key_absent), color = colors.secondary, fontSize = 13.sp)
                    CompositionLocalProvider(LocalSaveableStateRegistry provides null) { OutlinedTextField(
                        value = state.newApiKey, onValueChange = { model?.changeApiKey(it) },
                        modifier = Modifier.fillMaxWidth(), enabled = state.isLoaded && !state.busy,
                        label = { Text(stringResource(if (state.hasApiKey) R.string.models_replace_key else R.string.models_enter_key)) },
                        visualTransformation = PasswordVisualTransformation(), singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                        supportingText = { Text(stringResource(R.string.models_key_note)) },
                    ) }
                    if (state.hasApiKey) TextButton(
                        onClick = { model?.removeApiKey() }, enabled = !state.busy,
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(stringResource(R.string.models_remove_key)) }
                }
            }
            item(key = "language") {
                ModelSettingsCard(stringResource(R.string.models_language)) {
                    ModelTextField(state.settings.languageModel, { model?.changeSettings(state.settings.copy(languageModel = it)) }, R.string.models_model_id, state)
                    ModelTextField(state.settings.languageEndpoint, { model?.changeSettings(state.settings.copy(languageEndpoint = it)) }, R.string.models_endpoint, state, url = true)
                    ModelCheckAction(ModelRole.LANGUAGE, state, { model?.check(ModelRole.LANGUAGE) }, { model?.stopWaiting() })
                }
            }
            item(key = "image") {
                ModelSettingsCard(stringResource(R.string.models_image)) {
                    ModelTextField(state.settings.imageModel, { model?.changeSettings(state.settings.copy(imageModel = it)) }, R.string.models_model_id, state)
                    ModelTextField(state.settings.imageEndpoint, { model?.changeSettings(state.settings.copy(imageEndpoint = it)) }, R.string.models_endpoint, state, url = true)
                    Text(stringResource(R.string.models_image_size), color = colors.secondary, fontSize = 13.sp)
                    ModelCheckAction(ModelRole.IMAGE, state, { model?.check(ModelRole.IMAGE) }, { model?.stopWaiting() })
                }
            }
            item(key = "notice") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.models_endpoint_note), color = colors.secondary, fontSize = 12.sp, lineHeight = 19.sp)
                }
            }
            item(key = "save") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.message != null) Text(
                        stringResource(state.message), color = if (state.isError) MaterialTheme.colorScheme.error else colors.ink,
                        fontSize = 13.sp, lineHeight = 20.sp,
                    )
                    if (state.reloadRequired) TextButton(onClick = { model?.reload() }, enabled = !state.busy) {
                        Text(stringResource(R.string.models_reload))
                    }
                    Button(
                        onClick = { model?.save() }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        enabled = state.isLoaded && !state.busy && !state.reloadRequired && (state.dirty || !state.hasSavedSettings),
                    ) {
                        if (state.saving) { CircularProgressIndicator(Modifier.size(18.dp), color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                        Text(stringResource(if (state.saving) R.string.models_saving else R.string.models_save))
                    }
                    if (state.dirty) Text(stringResource(R.string.models_unsaved), color = colors.secondary, fontSize = 12.sp)
                }
            }
            item(key = "home_router") { com.txl.mebot.ui.home.HomeRoutingSettingsEntry() }
            item(key = "archive_search") { ArchiveSearchSettingsEntry() }
        }
    }
}

@Composable
private fun ModelSettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    val colors = settingsColors()
    Surface(shape = RoundedCornerShape(20.dp), color = colors.surface, contentColor = colors.ink, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, Modifier.semantics { heading() }, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
            content()
        }
    }
}

@Composable
private fun ModelTextField(value: String, onChange: (String) -> Unit, label: Int, state: ModelSettingsState, url: Boolean = false) {
    OutlinedTextField(
        value, onChange, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(label)) },
        enabled = state.isLoaded && !state.busy, minLines = 1, maxLines = if (url) 4 else 2,
        keyboardOptions = KeyboardOptions(keyboardType = if (url) KeyboardType.Uri else KeyboardType.Ascii, autoCorrectEnabled = false),
    )
}

@Composable
private fun ModelCheckAction(role: ModelRole, state: ModelSettingsState, onCheck: () -> Unit, onStop: () -> Unit) {
    val active = state.checking == role
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(if (role == ModelRole.LANGUAGE) R.string.models_language_check_note else R.string.models_image_check_note),
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 19.sp,
        )
        TextButton(
            onClick = if (active) onStop else onCheck,
            enabled = active || (state.isLoaded && !state.busy && !state.dirty && state.hasApiKey && !state.reloadRequired),
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            if (active) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
            Text(stringResource(if (active) R.string.models_stop_check else R.string.models_check))
        }
        val result = if (role == ModelRole.LANGUAGE) state.languageResult else state.imageResult
        AnimatedContent(
            targetState = result, transitionSpec = { fadeIn(tween(180)).togetherWith(fadeOut(tween(120))) }, label = "model_check_result",
        ) { outcome -> if (outcome != null) ModelResult(outcome) }
    }
}

@Composable
private fun ModelResult(result: ModelConnectionResult) {
    val color = when (result.kind) {
        ConnectionResultKind.SUCCESS -> MaterialTheme.colorScheme.primary
        ConnectionResultKind.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.error
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(result.kind.messageResource()), color = color, fontSize = 13.sp, lineHeight = 20.sp)
        if (result.httpStatus != null) Text(stringResource(R.string.models_http_result, result.httpStatus, result.elapsedMillis / 1000f), color = color, fontSize = 11.sp)
    }
}

private fun ConnectionResultKind.messageResource() = when (this) {
    ConnectionResultKind.SUCCESS -> R.string.models_connection_ok
    ConnectionResultKind.INVALID_KEY -> R.string.models_result_key
    ConnectionResultKind.NO_PERMISSION -> R.string.models_result_permission
    ConnectionResultKind.SERVICE_NOT_ENABLED -> R.string.models_result_service_disabled
    ConnectionResultKind.BILLING -> R.string.models_result_billing
    ConnectionResultKind.MODEL_UNAVAILABLE -> R.string.models_result_model
    ConnectionResultKind.ENDPOINT -> R.string.models_result_endpoint
    ConnectionResultKind.PARAMETERS -> R.string.models_result_parameters
    ConnectionResultKind.RATE_LIMIT -> R.string.models_result_rate_limit
    ConnectionResultKind.SERVICE_ERROR -> R.string.models_result_service
    ConnectionResultKind.DNS -> R.string.models_result_dns
    ConnectionResultKind.NETWORK -> R.string.models_result_network
    ConnectionResultKind.TIMEOUT -> R.string.models_result_timeout
    ConnectionResultKind.TLS -> R.string.models_result_tls
    ConnectionResultKind.BAD_RESPONSE -> R.string.models_result_response
    ConnectionResultKind.CANCELLED -> R.string.models_result_cancelled
}
