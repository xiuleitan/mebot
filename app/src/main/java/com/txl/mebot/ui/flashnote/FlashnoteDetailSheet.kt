package com.txl.mebot.ui.flashnote

import android.content.Intent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.txl.mebot.ui.common.hapticAction
import com.txl.mebot.R
import com.txl.mebot.data.flashnote.FlashnoteTitlePhase
import com.txl.mebot.data.flashnote.FlashnoteTitleState
import com.txl.mebot.data.local.FlashnoteAttachment
import com.txl.mebot.ui.common.DetailDeleteButton
import com.txl.mebot.ui.common.DetailDeleteConfirmationDialog
import com.txl.mebot.ui.schedule.scheduleColors
import com.txl.mebot.ui.theme.LocalAppTheme

@Composable
internal fun FlashnoteDetailSheet(
    entry: FlashnoteEntry?, loading: Boolean, failed: Boolean,
    editor: FlashnoteEditorState?, busy: Boolean, deleted: Boolean, failure: FlashnoteActionFailure?,
    onRetry: () -> Unit, onDismiss: () -> Unit,
    onToggleCompleted: (FlashnoteEntry) -> Unit, onEdit: () -> Unit, onDelete: (FlashnoteEntry) -> Unit,
    onRetryAction: () -> Unit, onEditTitle: (String) -> Unit, onEditContent: (String) -> Unit,
    onSaveEdit: () -> Unit, onCancelEdit: () -> Unit, onRetryEditLoad: () -> Unit, onRetryEditInput: () -> Unit,
    attachments: List<FlashnoteAttachment>, onRemoveAttachment: (String) -> Unit,
    onOpenFile: suspend (FlashnoteAttachment) -> Intent?,
    titleState: FlashnoteTitleState? = null, onGenerateTitle: () -> Unit = {},
) {
    val colors = scheduleColors()
    var lastEntry by remember { mutableStateOf(entry) }
    var lastEditor by remember { mutableStateOf(editor) }
    var saveRequested by remember(entry?.id) { mutableStateOf(false) }
    SideEffect { if (entry != null) lastEntry = entry; if (editor != null) lastEditor = editor }
    val shownEntry = entry ?: lastEntry.takeIf { busy || deleted }
    var completionTarget by rememberSaveable(entry?.id) { mutableStateOf<Boolean?>(null) }
    val completionSucceeded = completionTarget != null && entry?.completed == completionTarget &&
        !busy && !loading && !failed && failure == null
    com.txl.mebot.ui.common.SuccessHaptics(if (completionSucceeded || deleted) entry?.revision ?: "deleted" else null)
    com.txl.mebot.ui.common.SuccessHaptics(if (saveRequested && editor == null && !busy && failure == null) "edited" else null)
    FlashnoteDetailDialog(deleted || completionSucceeded, onDismiss) { close ->
        val focus = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current
        Box(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth()) {
                if (editor != null) Text(stringResource(R.string.flashnote_edit_title),
                    Modifier.fillMaxWidth().padding(start = 20.dp, top = 20.dp, end = 68.dp, bottom = 16.dp),
                    color = colors.secondary, fontSize = 13.sp, lineHeight = 20.sp)
                Crossfade(editor != null, Modifier.fillMaxWidth(),
                    animationSpec = tween(160), label = "flashnote_editor") { editing ->
                    val editState = if (editing) editor ?: lastEditor else null
                    if (editState != null) FlashnoteEditForm(editState, editor != null, onEditTitle, onEditContent,
                        onSave = { focus.clearFocus(); keyboard?.hide(); saveRequested = true; onSaveEdit() },
                        onCancel = { focus.clearFocus(); keyboard?.hide(); saveRequested = false; onCancelEdit() },
                        onRetryLoad = onRetryEditLoad, onRetryInput = onRetryEditInput,
                        onRemoveAttachment = onRemoveAttachment, onOpenFile = onOpenFile)
                    else FlashnoteDetails(shownEntry, loading, failed, busy || deleted || loading || completionSucceeded, failure,
                        onRetry, onToggleCompleted = { target ->
                            if (!busy && !loading && !deleted && !completionSucceeded) {
                                completionTarget = !target.completed
                                onToggleCompleted(target)
                            }
                        }, onEdit, onDelete, onRetryAction, attachments, onOpenFile, titleState, onGenerateTitle)
                }
            }
            IconButton(onClick = hapticAction(close),
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 12.dp).size(48.dp),
                shape = CircleShape, colors = IconButtonDefaults.iconButtonColors(containerColor = colors.surface)) {
                Icon(painterResource(R.drawable.ic_close), stringResource(R.string.flashnote_close_detail), Modifier.size(22.dp))
            }
        }
    }
}

@Composable
private fun FlashnoteDetails(
    entry: FlashnoteEntry?, loading: Boolean, failed: Boolean, busy: Boolean, failure: FlashnoteActionFailure?,
    onRetry: () -> Unit, onToggleCompleted: (FlashnoteEntry) -> Unit, onEdit: () -> Unit,
    onDelete: (FlashnoteEntry) -> Unit, onRetryAction: () -> Unit,
    attachments: List<FlashnoteAttachment>, onOpenFile: suspend (FlashnoteAttachment) -> Intent?,
    titleState: FlashnoteTitleState?, onGenerateTitle: () -> Unit,
) {
    val colors = scheduleColors()
    if (entry == null) {
        Box(Modifier.fillMaxWidth().padding(start = 28.dp, top = 64.dp, end = 28.dp, bottom = 28.dp), contentAlignment = Alignment.Center) {
            when {
                loading -> CircularProgressIndicator(Modifier.size(28.dp), color = colors.accent)
                failed -> TextButton(onRetry) { Text(stringResource(R.string.flashnote_detail_failed), color = colors.accent) }
                else -> Text(stringResource(R.string.flashnote_detail_missing), color = colors.secondary)
            }
        }
        return
    }
    var confirmingDelete by rememberSaveable(entry.id) { mutableStateOf(false) }
    val completedColor = if (LocalAppTheme.current.isDark) Color(0xFF81C995) else Color(0xFF2E7D32)
    Column(Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(start = 20.dp, top = 16.dp, end = 20.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SelectionContainer(Modifier.fillMaxWidth().padding(end = 48.dp)) {
                    Text(entry.title, fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.flashnote_created_at, entry.creationLabel()), Modifier.weight(1f),
                        color = colors.secondary, fontSize = 11.sp, lineHeight = 16.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(5.dp).background(if (entry.completed) completedColor else colors.accent, CircleShape))
                        Spacer(Modifier.width(5.dp))
                        Text(stringResource(if (entry.completed) R.string.flashnote_completed else R.string.flashnote_pending),
                            color = if (entry.completed) completedColor else colors.secondary, fontSize = 11.sp, lineHeight = 16.sp)
                    }
                }
            }
            if (titleState?.phase == FlashnoteTitlePhase.FAILED)
                FlashnoteTitleRetry(titleState, !busy, onGenerateTitle)
            HorizontalDivider(color = colors.divider.copy(alpha = 0.6f))
            if (entry.image != null) FlashnoteImage(entry.image, Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(18.dp)), maxDimension = 768)
            if (attachments.isNotEmpty()) BoxWithConstraints(Modifier.fillMaxWidth()) {
                FlashnoteMediaContent(attachments, onOpenFile, imageWidth = maxWidth)
            }
            if (entry.detail.isNotEmpty()) SelectionContainer { Text(entry.detail, color = colors.ink, fontSize = 15.sp, lineHeight = 25.sp) }
        }
        if (failure != null) FlashnoteActionNotice(failure, onRetryAction)
        HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = colors.divider)
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            DetailDeleteButton(onClick = { confirmingDelete = true }, enabled = !busy, modifier = Modifier.weight(1f))
            Button(hapticAction(onEdit), enabled = !busy, modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 12.dp), shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDDEBFF), contentColor = Color(0xFF245580))) {
                Text(stringResource(R.string.flashnote_edit), fontSize = 14.sp, lineHeight = 20.sp)
            }
            Button({ onToggleCompleted(entry) }, enabled = !busy,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = RoundedCornerShape(16.dp),
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDCEFE1), contentColor = Color(0xFF255F39))) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = Color(0xFF255F39), strokeWidth = 2.dp)
                else Text(stringResource(if (entry.completed) R.string.flashnote_restore else R.string.flashnote_complete), fontSize = 14.sp, lineHeight = 20.sp)
            }
        }
    }
    if (confirmingDelete) DetailDeleteConfirmationDialog(
        title = stringResource(R.string.flashnote_delete_confirmation_title),
        onDismiss = { confirmingDelete = false }, enabled = !busy,
        onConfirm = {
            if (confirmingDelete && !busy) {
                confirmingDelete = false
                onDelete(entry)
            }
        },
        text = { Text(stringResource(R.string.flashnote_delete_confirmation_body, entry.title)) },
    )
}

@Composable
private fun FlashnoteEditForm(
    state: FlashnoteEditorState, active: Boolean, onTitle: (String) -> Unit, onContent: (String) -> Unit,
    onSave: () -> Unit, onCancel: () -> Unit, onRetryLoad: () -> Unit, onRetryInput: () -> Unit,
    onRemoveAttachment: (String) -> Unit, onOpenFile: suspend (FlashnoteAttachment) -> Intent?,
) {
    val colors = scheduleColors()
    Column(Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (state.loading) Box(Modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(28.dp), color = colors.accent)
            } else if (state.loadFailed) TextButton(onRetryLoad) { Text(stringResource(R.string.flashnote_edit_load_failed), color = colors.accent) }
            else {
                FlashnoteMediaContent(state.attachments, onOpenFile, enabled = active && state.editable, onRemove = onRemoveAttachment)
                if (state.attachmentFailed) Text(stringResource(R.string.schedule_attachment_remove_failed), color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                val fieldColors = OutlinedTextFieldDefaults.colors(focusedBorderColor = colors.accent,
                    unfocusedBorderColor = colors.divider, focusedContainerColor = colors.past,
                    unfocusedContainerColor = colors.past, focusedTextColor = colors.ink, unfocusedTextColor = colors.ink,
                    focusedLabelColor = colors.accent, unfocusedLabelColor = colors.secondary, cursorColor = colors.accent)
                OutlinedTextField(state.title, onTitle, Modifier.fillMaxWidth(), readOnly = !active || !state.editable,
                    label = { Text(stringResource(R.string.flashnote_edit_title_label)) }, singleLine = true,
                    shape = RoundedCornerShape(18.dp), colors = fieldColors, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next))
                OutlinedTextField(state.content, onContent, Modifier.fillMaxWidth(), readOnly = !active || !state.editable,
                    label = { Text(stringResource(R.string.flashnote_edit_content_label)) }, minLines = 5,
                    shape = RoundedCornerShape(18.dp), colors = fieldColors, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default))
                when {
                    state.inputSaveState == FlashnoteInputSaveState.CONFLICT -> FlashnoteEditNotice(
                        R.string.flashnote_input_conflict, R.string.flashnote_keep_input_retry, onRetryInput)
                    state.saveConflict -> Text(stringResource(R.string.flashnote_edit_conflict), color = MaterialTheme.colorScheme.error, fontSize = 13.sp, lineHeight = 20.sp)
                    state.saveFailed -> Text(stringResource(R.string.flashnote_save_failed), color = MaterialTheme.colorScheme.error, fontSize = 13.sp, lineHeight = 20.sp)
                    state.inputSaveState == FlashnoteInputSaveState.FAILED -> FlashnoteEditNotice(
                        R.string.flashnote_input_save_failed, R.string.flashnote_retry, onRetryInput)
                }
            }
            if (state.cancelFailed) Text(stringResource(R.string.flashnote_cancel_failed), color = MaterialTheme.colorScheme.error, fontSize = 13.sp, lineHeight = 20.sp)
        }
        HorizontalDivider(color = colors.divider.copy(alpha = 0.5f))
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onCancel, enabled = active && !state.loading && !state.busy, modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = colors.ink)) {
                Text(stringResource(if (state.cancelFailed) R.string.flashnote_retry_cancel else R.string.flashnote_cancel), fontSize = 14.sp, lineHeight = 20.sp)
            }
            Button(onSave, enabled = active && state.canSave && !state.saveConflict, modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = colors.onAccent)) {
                if (state.isSaving || state.isCancelling) CircularProgressIndicator(Modifier.size(18.dp), color = colors.onAccent, strokeWidth = 2.dp)
                else Text(stringResource(if (state.saveFailed) R.string.flashnote_retry_save else R.string.flashnote_save_edit), fontSize = 14.sp, lineHeight = 20.sp)
            }
        }
    }
}

@Composable
private fun FlashnoteEditNotice(message: Int, button: Int, onClick: () -> Unit) {
    Text(stringResource(message), color = MaterialTheme.colorScheme.error, fontSize = 13.sp, lineHeight = 20.sp)
    TextButton(onClick) { Text(stringResource(button), color = scheduleColors().accent) }
}

@Composable
internal fun FlashnoteActionNotice(failure: FlashnoteActionFailure, onRetry: () -> Unit) {
    val message = when {
        failure.conflict -> R.string.flashnote_action_conflict
        failure.operation?.action == FlashnoteAction.DELETE -> R.string.flashnote_delete_failed
        failure.operation != null -> R.string.flashnote_completion_failed
        else -> R.string.flashnote_previous_draft_failed
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(message), Modifier.weight(1f), color = MaterialTheme.colorScheme.error, fontSize = 12.sp, lineHeight = 19.sp)
        TextButton(onRetry) { Text(stringResource(when {
            failure.conflict -> R.string.flashnote_refresh
            failure.editingNoteId != null -> R.string.flashnote_resume_edit
            else -> R.string.flashnote_retry
        }), color = scheduleColors().accent) }
    }
}
