package com.txl.mebot.ui.trading

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.txl.mebot.R
import com.txl.mebot.data.trading.TradingContract
import com.txl.mebot.data.trading.TradingInterval
import com.txl.mebot.data.trading.news.TradingNewsStory
import com.txl.mebot.ui.common.hapticAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val NoteGold = Color(0xFFFFD56B)
private val NoteBackground = Color(0xFF2A2419)
private val NoteField = Color(0xFF201C15)
private val NoteBorder = Color(0xFF705930)
private val NoteListNumber = Regex("""^(?:\d+(?:[.．](?!\d)|[、)）])|[（(]\d+[)）])\s*""")
private const val NotePreviewLines = 7

private fun withoutNoteListNumber(line: String): String {
    val unindented = line.trimStart()
    val prefix = NoteListNumber.find(unindented) ?: return line
    return unindented.drop(prefix.value.length)
}

internal fun tradingNoteEditorBody(note: String): String =
    note.lineSequence().joinToString("\n", transform = ::withoutNoteListNumber)

internal fun numberedTradingNote(note: String): String = note.lineSequence()
    .map { withoutNoteListNumber(it).trim() }
    .filter(String::isNotBlank)
    .mapIndexed { index, line -> "${index + 1}. $line" }
    .joinToString("\n")

@Composable
internal fun TradingNoteAndNews(
    note: String?,
    latestNews: List<TradingNewsStory>,
    pushStatus: TradingPushStatus,
    onOpenNews: () -> Unit,
    onEdit: () -> Unit,
    hasUnreadNews: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val fonts = MaterialTheme.typography
    val shape = RoundedCornerShape(6.dp)
    val numbered = remember(note) { note?.let(::numberedTradingNote)?.takeIf { it.isNotEmpty() } }
    val preview = numbered ?: stringResource(
        if (note == null) R.string.trading_note_title else R.string.trading_note_empty,
    )
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp).height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Surface(
            onClick = hapticAction(onEdit),
            enabled = note != null,
            modifier = Modifier.weight(1f).fillMaxHeight(),
            shape = shape,
            color = NoteBackground,
            contentColor = NoteGold,
            border = BorderStroke(1.dp, NoteBorder),
        ) {
            Box(
                Modifier.heightIn(min = 48.dp).padding(horizontal = 8.dp, vertical = 6.dp),
                contentAlignment = if (numbered == null) Alignment.CenterStart else Alignment.TopStart,
            ) {
                if (numbered == null) {
                    Text(preview, style = fonts.bodySmall, maxLines = NotePreviewLines, overflow = TextOverflow.Ellipsis)
                } else TradingScrollableNote(preview, fonts.bodySmall)
            }
        }
        Surface(
            onClick = hapticAction(onOpenNews),
            modifier = Modifier.weight(1f).fillMaxHeight(),
            shape = shape,
            color = colors.surfaceContainer,
            contentColor = colors.onSurfaceVariant,
            border = BorderStroke(1.dp, colors.outlineVariant),
        ) {
            TradingNewsPreview(latestNews, pushStatus, hasUnreadNews)
        }
    }
}

@Composable
private fun TradingScrollableNote(text: String, style: TextStyle) {
    val scroll = remember(text) { ScrollState(0) }
    val textMeasurer = rememberTextMeasurer()
    val previewSize = remember(text, style, textMeasurer) {
        object : MeasurePolicy {
            // The row sizes both cards from the preview height while the scroll layer measures the full note.
            override fun IntrinsicMeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int =
                textMeasurer.measure(text, style, maxLines = NotePreviewLines, constraints = Constraints(maxWidth = width))
                    .size.height

            override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints) =
                measurables.single().measure(constraints).let { note ->
                    layout(note.width, note.height) { note.placeRelative(0, 0) }
                }
        }
    }
    Layout(
        content = { Text(text, Modifier.fillMaxWidth().verticalScroll(scroll), style = style) },
        measurePolicy = previewSize,
    )
}

@Composable
internal fun TradingNoteEditor(
    contract: TradingContract,
    interval: TradingInterval,
    note: String,
    onDismiss: () -> Unit,
    onSave: suspend (String) -> Boolean,
    onCapture: suspend () -> ByteArray?,
) {
    var draft by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(tradingNoteEditorBody(note)))
    }
    var saving by remember { mutableStateOf(false) }
    var saveFailed by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }
    var shareResult by remember { mutableStateOf<TradingNoteShareResult?>(null) }
    var pendingExport by remember { mutableStateOf<TradingNoteExport?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current.applicationContext
    val exporter = remember(context) { TradingNoteShare(context) }
    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { uri ->
        val export = pendingExport
        pendingExport = null
        if (uri == null) {
            sharing = false
        } else {
            scope.launch {
                try {
                    shareResult = if (export != null) exporter.finish(export, uri) else {
                        exporter.discardDocument(uri)
                        TradingNoteShareResult.SAVE_FAILED
                    }
                } finally {
                    sharing = false
                }
            }
        }
    }
    val busy = saving || sharing
    val colors = MaterialTheme.colorScheme.copy(
        primary = NoteGold,
        onSurface = Color(0xFFF9F0DB),
        onSurfaceVariant = Color(0xFFD2C29B),
        surface = NoteField,
        outline = NoteBorder,
        outlineVariant = NoteBorder,
    )
    val typography = MaterialTheme.typography

    fun saveDraft() {
        val text = draft.text
        saving = true
        saveFailed = false
        shareResult = null
        scope.launch {
            try {
                val saved = onSave(text)
                saveFailed = !saved
                if (saved) onDismiss()
            } finally {
                saving = false
            }
        }
    }

    fun shareDraft() {
        val text = draft.text
        sharing = true
        saveFailed = false
        shareResult = null
        scope.launch {
            try {
                val png = onCapture()
                if (png == null) {
                    shareResult = TradingNoteShareResult.CHART_UNAVAILABLE
                    return@launch
                }
                val export = TradingNoteExport(tradingNoteExportName(contract, interval), png, text)
                if (Build.VERSION.SDK_INT >= 29) {
                    shareResult = exporter.finish(export)
                } else {
                    pendingExport = export
                    documentPicker.launch(export.name)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                pendingExport = null
                shareResult = TradingNoteShareResult.SAVE_FAILED
            } finally {
                if (pendingExport == null) sharing = false
            }
        }
    }

    Dialog(onDismissRequest = { if (!busy) onDismiss() }) {
        MaterialTheme(colorScheme = colors) {
            Surface(
                modifier = Modifier.widthIn(max = 380.dp).fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                color = NoteBackground,
                contentColor = colors.onSurface,
                border = BorderStroke(1.dp, NoteBorder),
            ) {
                Column(
                    Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        stringResource(R.string.trading_note_title),
                        style = typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = NoteGold,
                    )
                    OutlinedTextField(
                        value = draft,
                        onValueChange = {
                            if (draft.text != it.text) {
                                saveFailed = false
                                shareResult = null
                            }
                            draft = it
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !busy,
                        label = { Text(stringResource(R.string.trading_note_input), style = typography.labelMedium) },
                        textStyle = typography.bodySmall,
                        minLines = 5,
                        maxLines = 12,
                        visualTransformation = TradingLineNumbers,
                        shape = RoundedCornerShape(6.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = colors.surface,
                            unfocusedContainerColor = colors.surface,
                            focusedBorderColor = colors.primary,
                            unfocusedBorderColor = colors.outlineVariant,
                        ),
                    )
                    if (saveFailed) {
                        Text(
                            stringResource(R.string.trading_trend_save_failed),
                            style = typography.bodySmall,
                            color = colors.error,
                        )
                    }
                    shareResult?.let { result ->
                        Text(
                            stringResource(result.message),
                            style = typography.bodySmall,
                            color = if (result.error) colors.error else colors.primary,
                        )
                    }
                    FlowRow(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        itemVerticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(enabled = !busy, onClick = hapticAction(::shareDraft)) {
                            Text(
                                stringResource(if (sharing) R.string.trading_note_sharing else R.string.trading_note_share),
                                style = typography.labelLarge,
                            )
                        }
                        TextButton(enabled = !busy, onClick = hapticAction(::saveDraft)) {
                            Text(
                                stringResource(if (saving) R.string.trading_trend_saving else R.string.trading_trend_save),
                                style = typography.labelLarge,
                            )
                        }
                    }
                }
            }
        }
    }
}
