package com.txl.mebot.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.txl.mebot.R

@Composable
internal fun DetailDeleteButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: String = stringResource(R.string.detail_delete),
    shape: Shape = RoundedCornerShape(16.dp),
    contentPadding: PaddingValues = PaddingValues(horizontal = 6.dp, vertical = 12.dp),
) {
    Button(hapticAction(onClick), modifier.heightIn(min = 48.dp), enabled = enabled,
        contentPadding = contentPadding, shape = shape,
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFBE1E1), contentColor = Color(0xFFA12B2B))) {
        Text(label, fontSize = 14.sp, lineHeight = 20.sp)
    }
}

@Composable
internal fun DetailDeleteConfirmationDialog(
    title: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    enabled: Boolean = true,
    confirmLabel: String = stringResource(R.string.detail_delete_confirm),
    text: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = { if (enabled) onDismiss() },
        containerColor = colors.surface, titleContentColor = colors.onSurface, textContentColor = colors.onSurfaceVariant,
        title = { Text(title) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { text() } },
        confirmButton = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = hapticAction(onDismiss), enabled = enabled,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.onSurface)) {
                    Text(stringResource(R.string.detail_delete_cancel))
                }
                Button(onClick = hapticAction(onConfirm), enabled = enabled, modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFBE1E1), contentColor = Color(0xFFA12B2B))) {
                    Text(confirmLabel)
                }
            }
        },
    )
}
