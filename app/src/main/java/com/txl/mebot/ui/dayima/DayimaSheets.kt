package com.txl.mebot.ui.dayima

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.txl.mebot.ui.common.hapticAction
import com.txl.mebot.R
import com.txl.mebot.data.local.DayimaSpanReason
import com.txl.mebot.data.dayima.DayimaImage
import com.txl.mebot.ui.common.DetailDeleteButton
import com.txl.mebot.ui.common.DetailDeleteConfirmationDialog
import com.txl.mebot.ui.common.botSheetScrim
import com.txl.mebot.ui.schedule.scheduleColors
import java.time.LocalDate

private enum class DayimaPane { DETAIL, EDITOR, MARKERS, CHART }

/** 保存／取消也经过同一个 SheetState 收起；详情与编辑在弹层内交叉淡入。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DayimaSheetHost(state: DayimaPageState, model: DayimaViewModel?, reminderEventId: String? = null, onClosed: () -> Unit = {}) {
    val colors = scheduleColors()
    val pane = when {
        state.editor != null && state.editor.kind != DayimaEditorKind.BIRTH -> DayimaPane.EDITOR
        state.reviewingMarkers -> DayimaPane.MARKERS
        state.detailId != null -> DayimaPane.DETAIL
        state.charts.selection != null -> DayimaPane.CHART
        else -> null
    }
    val reminder = com.txl.mebot.ui.common.rememberReminderDetail(com.txl.mebot.data.home.HomeSource.DAYIMA,
        state.detailId.orEmpty(), state.detail?.period?.revision ?: 0, reminderEventId)
    val busy = rememberUpdatedState(state.busy || reminder.busy)
    val closed by rememberUpdatedState(onClosed)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { !busy.value })
    val scroll = rememberScrollState()
    var mounted by remember { mutableStateOf(false) }
    var lastPane by remember { mutableStateOf(DayimaPane.DETAIL) }
    var lastState by remember { mutableStateOf(state) }
    var lastEditor by remember { mutableStateOf(state.editor) }
    SideEffect {
        if (pane != null) { lastPane = pane; lastState = state }
        if (state.editor != null) lastEditor = state.editor
    }
    LaunchedEffect(pane) {
        if (pane != null) {
            scroll.scrollTo(0)
            mounted = true
            if (sheetState.hasExpandedState) sheetState.show()
        } else if (mounted) {
            sheetState.hide()
            mounted = false
            closed()
        }
    }
    if (mounted) {
        val shown = if (pane == null) lastState else state
        val availableHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
        ModalBottomSheet(onDismissRequest = { if (!busy.value) model?.closeSheet() }, sheetState = sheetState,
            scrimColor = botSheetScrim(),
            sheetMaxWidth = 688.dp, sheetGesturesEnabled = false,
            containerColor = colors.surface, contentColor = colors.ink,
            dragHandle = {
                if ((pane ?: lastPane) == DayimaPane.DETAIL) {
                    Box(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 22.dp)) {
                        IconButton(onClick = hapticAction { model?.closeSheet() }, enabled = pane == DayimaPane.DETAIL && !busy.value,
                            modifier = Modifier.align(Alignment.CenterEnd).size(48.dp)) {
                            Icon(painterResource(R.drawable.ic_close), stringResource(R.string.dayima_close), Modifier.size(22.dp), tint = colors.ink)
                        }
                    }
                } else Spacer(Modifier.height(12.dp))
            },
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
            AnimatedContent(pane ?: lastPane, Modifier.fillMaxWidth(),
                transitionSpec = { fadeIn(tween(180)).togetherWith(fadeOut(tween(100))) }, label = "dayima_sheet_content") { current ->
                if (current == DayimaPane.DETAIL) {
                    DayimaDetails(shown, model, enabled = pane == DayimaPane.DETAIL && !busy.value,
                        modifier = Modifier.heightIn(max = availableHeight * 0.85f), reminder = reminder)
                } else Column(Modifier.fillMaxWidth().heightIn(max = availableHeight * 0.85f)
                    .verticalScroll(scroll).padding(start = 22.dp, end = 22.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(when (current) {
                            DayimaPane.MARKERS -> R.string.dayima_markers_title
                            DayimaPane.CHART -> R.string.dayima_chart_selection_title
                            else -> (state.editor ?: lastEditor)?.title() ?: R.string.dayima_record_title
                        }), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        IconButton(onClick = hapticAction { model?.closeSheet() }, enabled = !state.busy, modifier = Modifier.size(48.dp)) {
                            Icon(painterResource(R.drawable.ic_close), stringResource(R.string.dayima_close), Modifier.size(22.dp))
                        }
                    }
                    when (current) {
                        DayimaPane.MARKERS -> DayimaMarkers(shown, model, enabled = pane == DayimaPane.MARKERS && !state.busy)
                        DayimaPane.CHART -> DayimaChartDetails(shown.charts, model, enabled = pane == DayimaPane.CHART && !state.busy)
                        DayimaPane.EDITOR -> (state.editor ?: lastEditor)?.let { editor ->
                            DayimaEditForm(editor, shown.today, enabled = pane == DayimaPane.EDITOR && !state.busy,
                                busy = state.busy, model = model, images = shown.detail?.images.orEmpty())
                        }
                        else -> Unit
                    }
                }
            }
        }
    }
    state.editor?.takeIf { it.kind == DayimaEditorKind.BIRTH }?.let { editor ->
        AlertDialog(onDismissRequest = { model?.cancelEditor() },
            title = { Text(stringResource(R.string.dayima_birth_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.dayima_birth_help))
                    DayimaDateField(editor.date, { model?.changeDate(it) }, state.today,
                        R.string.dayima_birth_title, R.string.dayima_birth_hint, !state.busy, birth = true)
                    if (editor.date.isNotEmpty()) TextButton(onClick = { model?.changeDate("") }, enabled = !state.busy) {
                        Text(stringResource(R.string.dayima_birth_clear))
                    }
                    DayimaEditorError(editor.error)
                }
            },
            confirmButton = { TextButton(onClick = { model?.saveEditor() }, enabled = !state.busy) {
                Text(stringResource(if (state.busy) R.string.dayima_saving else R.string.dayima_save))
            } },
            dismissButton = { TextButton(onClick = { model?.cancelEditor() }, enabled = !state.busy) { Text(stringResource(R.string.dayima_cancel)) } })
    }
    state.deletion?.let { target ->
        val title = when (target.kind) {
            DayimaDeleteKind.PERIOD -> R.string.dayima_delete_period_title
            DayimaDeleteKind.NOTE -> R.string.dayima_delete_note_title
            DayimaDeleteKind.SPAN -> R.string.dayima_delete_span_title
        }
        val body = when (target.kind) {
            DayimaDeleteKind.PERIOD -> R.string.dayima_delete_period_body
            DayimaDeleteKind.NOTE -> R.string.dayima_delete_note_body
            DayimaDeleteKind.SPAN -> R.string.dayima_delete_span_body
        }
        DetailDeleteConfirmationDialog(title = stringResource(title),
            onDismiss = { model?.cancelDelete() }, onConfirm = { model?.confirmDelete() }, enabled = !state.busy,
            confirmLabel = stringResource(if (state.busy) R.string.detail_delete_pending else R.string.detail_delete_confirm),
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(body, target.label)); DayimaEditorError(target.error)
            } },
        )
    }
}

@Composable
private fun DayimaDetails(state: DayimaPageState, model: DayimaViewModel?, enabled: Boolean, modifier: Modifier, reminder: com.txl.mebot.ui.common.ReminderDetailState) {
    val colors = scheduleColors()
    val detail = state.detail
    val actionsEnabled = enabled && !reminder.busy
    val bodyNotes = detail?.notes.orEmpty().filter { it.content.isNotBlank() }
    val hasBody = detail?.images?.isNotEmpty() == true || bodyNotes.isNotEmpty() || reminder.card != null || reminder.error != null
    Column(modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, bottom = 24.dp)) {
        Box(Modifier.fillMaxWidth().heightIn(min = 48.dp), contentAlignment = Alignment.CenterStart) {
            Text(detail?.period?.startDate?.dayimaDate().orEmpty(), Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        }
        if (hasBody) HorizontalDivider(color = colors.divider)
        if (hasBody || detail == null || state.detailFailed) Column(
            Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            when {
                state.detailLoading && detail == null -> DayimaLoading()
                state.detailFailed -> DayimaLoadError { model?.refreshDetail() }
                detail == null -> Text(stringResource(R.string.dayima_detail_missing), color = colors.secondary)
                else -> {
                    DayimaImages("period:${detail.period.id}", detail.images)
                    bodyNotes.forEach { note -> SelectionContainer { Text(note.content, color = colors.ink) } }
                    com.txl.mebot.ui.common.ReminderDetailAction(reminder, actionsEnabled,
                        onSaved = { model?.closeSheet() }, onRecord = { model?.openDetail(null); model?.beginRecord(false) })
                }
            }
        }
        if (hasBody) HorizontalDivider(color = colors.divider)
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            DetailDeleteButton(onClick = { model?.askDeletePeriod() }, enabled = actionsEnabled && detail != null,
                modifier = Modifier.weight(1f))
            Button(onClick = hapticAction { model?.beginPeriodEdit() }, enabled = actionsEnabled && detail != null,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = RoundedCornerShape(16.dp),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDDEBFF), contentColor = Color(0xFF245580))) {
                Text(stringResource(R.string.dayima_edit))
            }
        }
    }
}

@Composable
private fun DayimaEditForm(editor: DayimaEditor, today: LocalDate, enabled: Boolean, busy: Boolean, model: DayimaViewModel?, images: List<DayimaImage>) {
    val colors = scheduleColors()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (editor.kind != DayimaEditorKind.SPAN) DayimaDateField(editor.date, { model?.changeDate(it) }, today,
            if (editor.kind == DayimaEditorKind.NOTE) R.string.dayima_note_date else R.string.dayima_start_date,
            R.string.dayima_date_hint, enabled)
        when (editor.kind) {
            DayimaEditorKind.PERIOD -> {
                DayimaImages("editing-period:${editor.targetId}", images.filter { it.asset.id !in editor.removedImageIds },
                    enabled = enabled, onRemove = { model?.removeEditingImage(it) })
                editor.bodyNotes.forEach { note -> key(note.id) {
                    OutlinedTextField(note.content, { model?.changeBodyNote(note.id, it) }, enabled = enabled,
                        modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 8, shape = RoundedCornerShape(18.dp),
                        label = { Text(stringResource(R.string.dayima_body_optional)) })
                } }
                if (editor.bodyNotes.isEmpty()) OutlinedTextField(editor.content, { model?.changeContent(it) }, enabled = enabled,
                    modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 8, shape = RoundedCornerShape(18.dp),
                    label = { Text(stringResource(R.string.dayima_body_optional)) })
            }
            DayimaEditorKind.NOTE -> if (editor.periodId == null) Text(stringResource(R.string.dayima_note_independent), color = colors.secondary,
                style = MaterialTheme.typography.bodySmall)
            DayimaEditorKind.SPAN -> {
                Text(editor.date, color = colors.secondary)
                Text(stringResource(R.string.dayima_span_help), color = colors.secondary, style = MaterialTheme.typography.bodySmall)
                DayimaSpanReason.entries.forEach { reason ->
                    Surface(onClick = { model?.changeReason(reason) }, enabled = enabled,
                        shape = RoundedCornerShape(16.dp), color = if (editor.reason == reason) colors.selected else colors.past) {
                        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(editor.reason == reason, onClick = null, enabled = enabled)
                            Text(stringResource(if (reason == DayimaSpanReason.MISSING_RECORDS) R.string.dayima_missing_span else R.string.dayima_special_span),
                                Modifier.padding(start = 10.dp).weight(1f))
                        }
                    }
                }
            }
            else -> Unit
        }
        if (editor.kind != DayimaEditorKind.PERIOD) OutlinedTextField(
            editor.content, { model?.changeContent(it) }, enabled = enabled, modifier = Modifier.fillMaxWidth(),
            minLines = 3, maxLines = 8, shape = RoundedCornerShape(18.dp),
            label = { Text(stringResource(when (editor.kind) {
                DayimaEditorKind.SPAN -> R.string.dayima_span_detail
                DayimaEditorKind.NOTE -> R.string.dayima_note_content
                else -> R.string.dayima_note_optional
            })) }, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default))
        DayimaEditorError(editor.error)
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (editor.kind == DayimaEditorKind.NOTE && editor.targetId != null) {
                DetailDeleteButton(onClick = { model?.askDeleteEditingNote() }, enabled = enabled,
                    modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = 50.dp),
                    label = stringResource(R.string.dayima_note_delete),
                    shape = ButtonDefaults.shape, contentPadding = ButtonDefaults.ContentPadding)
            }
            OutlinedButton(onClick = { focus.clearFocus(); keyboard?.hide(); model?.cancelEditor() }, enabled = enabled,
                modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = 50.dp)) { Text(stringResource(R.string.dayima_cancel)) }
            Button(onClick = { focus.clearFocus(); keyboard?.hide(); model?.saveEditor() }, enabled = enabled,
                modifier = Modifier.weight(1f).fillMaxHeight().heightIn(min = 50.dp)) {
                Text(stringResource(if (busy) R.string.dayima_saving else R.string.dayima_save))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DayimaDateField(value: String, onChange: (String) -> Unit, today: LocalDate, label: Int, hint: Int, enabled: Boolean, birth: Boolean = false) {
    var calendarOpen by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    OutlinedTextField(value, onValueChange = { if (it.length <= 10 && it.all { char -> char.isDigit() || char == '-' }) onChange(it) },
        label = { Text(stringResource(label)) }, supportingText = { Text(stringResource(hint)) }, singleLine = true, enabled = enabled,
        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
        trailingIcon = { IconButton(onClick = { focus.clearFocus(); calendarOpen = true }, enabled = enabled) {
            Icon(painterResource(R.drawable.ic_calendar), stringResource(R.string.dayima_calendar), Modifier.size(22.dp))
        } })
    if (calendarOpen) {
        val date = parseDayimaDate(value)?.takeIf { it <= today }
        val cursor = date ?: if (birth) today.minusYears(25) else today
        val latest = today.toEpochDay() * 86_400_000L
        val picker = rememberDatePickerState(initialSelectedDateMillis = date?.toEpochDay()?.times(86_400_000L),
            initialDisplayedMonthMillis = cursor.toEpochDay() * 86_400_000L,
            yearRange = minOf(1900, cursor.year)..today.year,
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis <= latest
                override fun isSelectableYear(year: Int) = year <= today.year
            })
        DatePickerDialog(onDismissRequest = { calendarOpen = false }, confirmButton = {
            TextButton(onClick = { picker.selectedDateMillis?.let { onChange(LocalDate.ofEpochDay(it / 86_400_000L).toString()) }; calendarOpen = false },
                enabled = picker.selectedDateMillis != null) { Text(stringResource(R.string.dayima_save)) }
        }, dismissButton = { TextButton(onClick = { calendarOpen = false }) { Text(stringResource(R.string.dayima_cancel)) } }) {
            DatePicker(picker)
        }
    }
}

@Composable
private fun DayimaMarkers(state: DayimaPageState, model: DayimaViewModel?, enabled: Boolean) {
    val colors = scheduleColors()
    val markers = state.overview?.markers.orEmpty().sortedByDescending { it.marker.startDate }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.dayima_markers_help), color = colors.secondary, style = MaterialTheme.typography.bodySmall)
        if (markers.isEmpty()) Text(stringResource(R.string.dayima_markers_empty), color = colors.secondary)
        markers.forEach { marker ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${marker.marker.startDate.dayimaDate()} → ${marker.marker.endDate.dayimaDate()}", fontWeight = FontWeight.Medium)
                Text(stringResource(if (marker.needsReview) R.string.dayima_pending_span else if (marker.marker.reason == DayimaSpanReason.MISSING_RECORDS)
                    R.string.dayima_missing_span else R.string.dayima_special_span), color = colors.secondary)
                if (marker.marker.detail.isNotBlank()) Text(marker.marker.detail, color = colors.secondary)
                DetailDeleteButton(onClick = { model?.askDeleteSpan(marker.marker) }, enabled = enabled,
                    label = stringResource(R.string.dayima_span_remove))
                HorizontalDivider(color = colors.divider)
            }
        }
    }
}

@Composable
private fun DayimaEditorError(error: Int?) {
    error?.let { Text(stringResource(it), Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
}

private fun DayimaEditor.title(): Int = when (kind) {
    DayimaEditorKind.RECORD -> R.string.dayima_record_title
    DayimaEditorKind.BIRTH -> R.string.dayima_birth_title
    DayimaEditorKind.PERIOD -> R.string.dayima_edit_record_title
    DayimaEditorKind.NOTE -> if (targetId == null) R.string.dayima_note_add else R.string.dayima_note_edit
    DayimaEditorKind.SPAN -> R.string.dayima_span_title
}
