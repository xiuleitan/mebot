package com.txl.mebot.ui.flashnote

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.txl.mebot.R
import com.txl.mebot.data.flashnote.FlashnoteTitleFailure
import com.txl.mebot.data.flashnote.FlashnoteTitlePhase
import com.txl.mebot.data.flashnote.FlashnoteTitleState
import com.txl.mebot.ui.schedule.scheduleColors

@Composable
internal fun FlashnoteTitleIndicator(state: FlashnoteTitleState?) {
    if (state == null) return
    val colors = scheduleColors()
    val label = stringResource(when (state.phase) {
        FlashnoteTitlePhase.WAITING -> R.string.flashnote_ai_waiting
        FlashnoteTitlePhase.GENERATING -> R.string.flashnote_ai_generating
        FlashnoteTitlePhase.FAILED -> R.string.flashnote_ai_failed
    })
    if (state.phase == FlashnoteTitlePhase.GENERATING) CircularProgressIndicator(
        Modifier.size(12.dp).semantics { contentDescription = label }, color = colors.secondary, strokeWidth = 1.5.dp)
    else Text(if (state.busy) "AI" else "!", Modifier.semantics { contentDescription = label },
        fontSize = 10.sp, color = colors.secondary)
}

@Composable
internal fun FlashnoteTitleRetry(state: FlashnoteTitleState, enabled: Boolean, onGenerate: () -> Unit) {
    if (state.phase != FlashnoteTitlePhase.FAILED) return
    val colors = scheduleColors()
    val message = when (state.failure) {
        FlashnoteTitleFailure.CONFIGURATION, FlashnoteTitleFailure.CREDENTIAL, FlashnoteTitleFailure.AUTHORIZATION -> R.string.flashnote_ai_configuration
        FlashnoteTitleFailure.INVALID_RESULT -> R.string.flashnote_ai_invalid_title
        FlashnoteTitleFailure.IMAGE -> R.string.flashnote_ai_image_failed
        FlashnoteTitleFailure.LIMIT -> R.string.flashnote_ai_limit
        FlashnoteTitleFailure.CHANGED -> R.string.flashnote_ai_changed
        else -> R.string.flashnote_ai_failed
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(message), Modifier.weight(1f), color = colors.secondary, fontSize = 11.sp, lineHeight = 17.sp)
        TextButton(onGenerate, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp),
            colors = ButtonDefaults.textButtonColors(contentColor = colors.ink)) {
            Text(stringResource(R.string.flashnote_ai_retry), fontSize = 12.sp)
        }
    }
}
