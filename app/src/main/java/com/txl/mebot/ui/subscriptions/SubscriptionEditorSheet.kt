package com.txl.mebot.ui.subscriptions

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.input.KeyboardType
import com.txl.mebot.R
import com.txl.mebot.data.local.SubscriptionCycleUnit
import com.txl.mebot.data.local.SubscriptionStatus
import com.txl.mebot.ui.schedule.scheduleColors
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

@Composable
internal fun SubscriptionEditorSheet(editor: SubscriptionEditor, page: SubscriptionsState, model: SubscriptionsViewModel) {
    val colors = scheduleColors()
    val enabled = !page.busy && !page.editorSaved
    var unitsOpen by remember { mutableStateOf(false) }
    var reloadConfirm by rememberSaveable(editor.requestId) { mutableStateOf(false) }
    var pickerTarget by rememberSaveable { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        val target = pickerTarget; pickerTarget = null
        if (target != null) model.importImages(target, uris)
    }
    val restoring = editor.original?.status in setOf(SubscriptionStatus.ARCHIVED, SubscriptionStatus.DELETED)
    com.txl.mebot.ui.common.SuccessHaptics(if (page.editorSaved) editor.requestId else null)
    SubscriptionSheet(onDismiss = model::dismissEditor, dismissible = !page.busy, beforeDismiss = model::flushEditor) { close ->
        LaunchedEffect(page.editorSaved) { if (page.editorSaved) close(null) }
        SubscriptionSheetTitle(if (restoring) "恢复订阅" else "编辑订阅", { close(null) }, enabled)
        Column(Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (restoring) Text("请重新核对日期和自动续费状态。原有历史会继续保留。", color = colors.secondary, fontSize = 12.sp)
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = colors.surface,
                border = BorderStroke(1.dp, colors.divider.copy(alpha = 0.7f))) {
                Column {
                    SubscriptionEditorRow("订阅名称") {
                        SubscriptionCellInput(editor.title, { value -> model.changeEditor { it.copy(title = value) } }, "订阅名称", enabled, placeholder = "填写名称")
                    }
                    SubscriptionEditorRow("每次费用") {
                        SubscriptionCellInput(editor.amount, { value -> model.changeEditor { it.copy(amount = value) } }, "每次费用", enabled,
                            keyboard = KeyboardType.Decimal, placeholder = "填写金额")
                    }
                    SubscriptionEditorRow("币种") {
                        SubscriptionCellInput(editor.currency, { value -> model.changeEditor { it.copy(currency = value.take(3).uppercase(Locale.ROOT)) } },
                            "币种", enabled, keyboard = KeyboardType.Ascii, placeholder = "例如 CNY")
                    }
                    val intervalLabel = when (editor.unit) {
                        SubscriptionCycleUnit.DAY -> "间隔天数"; SubscriptionCycleUnit.WEEK -> "间隔周数"
                        SubscriptionCycleUnit.MONTH -> "间隔月份"; SubscriptionCycleUnit.YEAR -> "间隔年数"
                        SubscriptionCycleUnit.NONE -> "计费周期"
                    }
                    SubscriptionEditorRow(intervalLabel) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            if (editor.unit != SubscriptionCycleUnit.NONE) SubscriptionCellInput(editor.interval,
                                { value -> model.changeEditor { it.copy(interval = value) } }, intervalLabel, enabled,
                                modifier = Modifier.weight(1f), keyboard = KeyboardType.Number, placeholder = "1")
                            Box(Modifier.then(if (editor.unit == SubscriptionCycleUnit.NONE) Modifier.fillMaxWidth() else Modifier)) {
                                TextButton({ unitsOpen = true }, enabled = enabled,
                                    modifier = Modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
                                    Text(unitLabel(editor.unit), fontSize = 14.sp)
                                    Icon(painterResource(R.drawable.ic_chevron_right), null, Modifier.padding(start = 4.dp).size(14.dp).rotate(90f))
                                }
                                DropdownMenu(unitsOpen, { unitsOpen = false }) {
                                    SubscriptionCycleUnit.entries.forEach { unit -> DropdownMenuItem(text = { Text(unitLabel(unit)) }, onClick = {
                                        unitsOpen = false
                                        model.changeEditor { it.copy(unit = unit, autoRenew = unit != SubscriptionCycleUnit.NONE && it.autoRenew,
                                            monthEnd = unit in setOf(SubscriptionCycleUnit.MONTH, SubscriptionCycleUnit.YEAR) && it.monthEnd) }
                                    }) }
                                }
                            }
                        }
                    }
                    if (editor.unit != SubscriptionCycleUnit.NONE) SubscriptionEditorToggle("是否自动续费", editor.autoRenew, enabled) { checked ->
                        model.changeEditor { it.copy(autoRenew = checked, validUntil = if (!checked && it.validUntil.isBlank()) it.next else it.validUntil) }
                    }
                    if (editor.autoRenew && editor.unit != SubscriptionCycleUnit.NONE) SubscriptionDateField(editor.next,
                        { value -> model.changeEditor { it.copy(next = value) } }, "下次扣费", enabled)
                    if (editor.unit in setOf(SubscriptionCycleUnit.MONTH, SubscriptionCycleUnit.YEAR)) SubscriptionEditorToggle("月底扣费", editor.monthEnd, enabled) { checked ->
                        model.changeEditor {
                            val adjusted = if (checked) runCatching { LocalDate.parse(it.next).withDayOfMonth(LocalDate.parse(it.next).lengthOfMonth()).toString() }.getOrDefault(it.next) else it.next
                            it.copy(monthEnd = checked, next = adjusted)
                        }
                    }
                    SubscriptionDateField(editor.validUntil, { value -> model.changeEditor { it.copy(validUntil = value) } }, "有效期至", enabled,
                        placeholder = if (editor.autoRenew) "选填" else if (editor.unit == SubscriptionCycleUnit.NONE) "长期有效" else "选择日期")
                    if (editor.purchaseDate != null) SubscriptionDateField(editor.purchaseDate,
                        { value -> model.changeEditor { it.copy(purchaseDate = value) } }, "首次扣费", enabled)
                    SubscriptionEditorRow("支付方式") {
                        SubscriptionCellInput(editor.paymentMethod, { value -> model.changeEditor { it.copy(paymentMethod = value) } }, "支付方式", enabled, placeholder = "选填")
                    }
                    SubscriptionEditorRow("账号") {
                        SubscriptionCellInput(editor.account, { value -> model.changeEditor { it.copy(account = value) } }, "账号", enabled, placeholder = "选填")
                    }
                    SubscriptionEditorRow("备注", multiline = true) {
                        SubscriptionCellInput(editor.notes, { value -> model.changeEditor { it.copy(notes = value) } }, "备注", enabled, multiline = true, placeholder = "选填")
                    }
                    SubscriptionEditorRow("订阅图片", divider = false) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
                            val images = editor.imageAssetIds ?: listOfNotNull(editor.coverAssetId)
                            if (images.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(images, key = { it }) { assetId -> SubscriptionPicture(assetId, model, Modifier.size(44.dp)) }
                            }
                            TextButton({ pickerTarget = editor.requestId; picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                                enabled = enabled, modifier = Modifier.heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 4.dp)) {
                                Icon(painterResource(R.drawable.ic_plus), null, Modifier.size(18.dp))
                                Text("添加图片", Modifier.padding(start = 6.dp), fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }
        page.editorError?.let { Text(it, Modifier.padding(horizontal = 20.dp, vertical = 6.dp), color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
        if (page.conflict) TextButton({ reloadConfirm = true }, enabled = !page.busy, modifier = Modifier.padding(horizontal = 12.dp)) { Text("读取最新记录") }
        HorizontalDivider(color = colors.divider.copy(alpha = 0.5f))
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton({ close(null) }, Modifier.weight(1f).heightIn(min = 48.dp), enabled = enabled) { Text("稍后继续") }
            Button(model::saveEditor, Modifier.weight(1f).heightIn(min = 48.dp), enabled = enabled && !page.conflict,
                colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = colors.onAccent)) { Text(if (page.busy) "保存中…" else "保存") }
        }
    }
    if (reloadConfirm) AlertDialog(onDismissRequest = { reloadConfirm = false }, title = { Text("读取最新记录？") },
        text = { Text("这会替换当前表单内容。确认后，可按最新记录继续编辑。") },
        confirmButton = { TextButton({ reloadConfirm = false; model.reloadEditor() }) { Text("读取") } },
        dismissButton = { TextButton({ reloadConfirm = false }) { Text("取消") } })
}

/** 统一左侧标签和右侧编辑区；行高为下限，字体放大及多行备注可自然增高。 */
@Composable
private fun SubscriptionEditorRow(label: String, multiline: Boolean = false, divider: Boolean = true, content: @Composable () -> Unit) {
    val colors = scheduleColors()
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = if (multiline) Alignment.Top else Alignment.CenterVertically) {
            Text(label, Modifier.width(104.dp).padding(horizontal = 12.dp, vertical = if (multiline) 14.dp else 8.dp),
                color = colors.secondary, fontSize = 13.sp, lineHeight = 18.sp)
            Box(Modifier.weight(1f)) { content() }
        }
        if (divider) HorizontalDivider(color = colors.divider.copy(alpha = 0.55f), thickness = 0.5.dp)
    }
}

@Composable
private fun SubscriptionCellInput(value: String, changed: (String) -> Unit, label: String, enabled: Boolean,
    modifier: Modifier = Modifier, keyboard: KeyboardType = KeyboardType.Text, multiline: Boolean = false, placeholder: String = "") {
    val colors = scheduleColors()
    BasicTextField(value, changed, modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = label },
        enabled = enabled, singleLine = !multiline, minLines = if (multiline) 2 else 1, maxLines = if (multiline) 5 else 1,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = if (enabled) colors.ink else colors.secondary, fontSize = 14.sp, lineHeight = 20.sp),
        keyboardOptions = KeyboardOptions(keyboardType = keyboard), cursorBrush = SolidColor(colors.accent),
        decorationBox = { field ->
            Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) Text(placeholder, color = colors.secondary.copy(alpha = 0.75f), fontSize = 14.sp, lineHeight = 20.sp)
                field()
            }
        })
}

@Composable
private fun SubscriptionEditorToggle(label: String, checked: Boolean, enabled: Boolean, changed: (Boolean) -> Unit) {
    val colors = scheduleColors()
    SubscriptionEditorRow(label) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = changed)
            .semantics { contentDescription = label }.padding(start = 12.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(if (checked) "是" else "否", color = if (enabled) colors.ink else colors.secondary, fontSize = 14.sp, lineHeight = 20.sp)
            Checkbox(checked, onCheckedChange = null, enabled = enabled)
        }
    }
}

private fun unitLabel(unit: SubscriptionCycleUnit): String = when (unit) {
    SubscriptionCycleUnit.DAY -> "天"; SubscriptionCycleUnit.WEEK -> "周"; SubscriptionCycleUnit.MONTH -> "个月"
    SubscriptionCycleUnit.YEAR -> "年"; SubscriptionCycleUnit.NONE -> "不重复"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SubscriptionDateField(value: String, onChange: (String) -> Unit, label: String, enabled: Boolean, placeholder: String = "选择日期") {
    var pickerOpen by remember { mutableStateOf(false) }
    SubscriptionEditorRow(label) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SubscriptionCellInput(value, onChange, label, enabled, modifier = Modifier.weight(1f), keyboard = KeyboardType.Ascii, placeholder = placeholder)
            IconButton({ pickerOpen = true }, enabled = enabled, modifier = Modifier.size(48.dp)) {
                Icon(painterResource(R.drawable.ic_calendar), "选择$label", Modifier.size(20.dp))
            }
        }
    }
    if (pickerOpen) {
        val initial = runCatching { LocalDate.parse(value).takeIf { it.year in 1900..9999 } }.getOrNull() ?: LocalDate.now()
        val state = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), yearRange = 1900..9999)
        DatePickerDialog(onDismissRequest = { pickerOpen = false }, confirmButton = {
            TextButton({ state.selectedDateMillis?.let { onChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()) }; pickerOpen = false },
                enabled = state.selectedDateMillis != null) { Text("确定") }
        }, dismissButton = { TextButton({ pickerOpen = false }) { Text("取消") } }) { DatePicker(state) }
    }
}
