package com.txl.mebot.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.txl.mebot.ui.common.hapticAction

@Composable
internal fun HomeReflectionTextDialog(state: HomeReflectionState, model: HomeReflectionViewModel) {
    val editor = state.textEditor ?: return
    AlertDialog(onDismissRequest = model::cancelText,
        properties = DialogProperties(dismissOnBackPress = !state.busy, dismissOnClickOutside = !state.busy),
        title = { Text(if (editor.original == null) "添加文字" else "编辑文字") },
        text = { Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(editor.text, model::text, Modifier.fillMaxWidth().testTag("home_reflection_input"), enabled = !state.busy,
                placeholder = { Text("写下想每天提醒自己的话") }, minLines = 5, maxLines = 12,
                textStyle = LocalTextStyle.current.copy(fontSize = 16.sp, lineHeight = 24.sp), shape = RoundedCornerShape(16.dp))
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, lineHeight = 18.sp) }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        } },
        confirmButton = { Button({ model.saveText() }, enabled = !state.busy && editor.text.isNotBlank(),
            modifier = Modifier.heightIn(min = 48.dp).testTag("home_reflection_save_text")) { Text("保存") } },
        dismissButton = { TextButton(hapticAction(model::cancelText), enabled = !state.busy) { Text("取消") } })
}
