package com.txl.mebot.ui.dayima

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.txl.mebot.R
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.room.withTransaction
import com.txl.mebot.MebotApplication
import com.txl.mebot.data.local.*
import com.txl.mebot.ui.archive.archiveActivity
import com.txl.mebot.ui.flashnote.FlashnoteDetailDialog
import com.txl.mebot.ui.schedule.scheduleColors
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import androidx.lifecycle.viewModelScope

class DayimaRemindersViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as MebotApplication).container
    val settings = container.dayimaStorage.observeReminderSettings().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    private val mutable = MutableStateFlow<String?>(null)
    val error = mutable.asStateFlow()
    private val working = MutableStateFlow(false)
    val busy = working.asStateFlow()
    fun update(prepare: Boolean? = null, missing: Boolean? = null) {
        if (working.value) return
        working.value = true
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) {
                container.database.withTransaction {
                    val old = container.dayimaStorage.reminderSettings()
                    val value = old ?: DayimaReminderSettingsEntity(updatedAt = 0)
                    val next = value.copy(prepareEnabled = prepare ?: value.prepareEnabled, missingRecordEnabled = missing ?: value.missingRecordEnabled,
                        updatedAt = System.currentTimeMillis(), revision = (old?.revision ?: 0) + 1)
                    if (old == null) container.dayimaStorage.insertReminderSettings(next)
                    else check(container.dayimaStorage.updateReminderSettings(next.prepareEnabled, next.missingRecordEnabled, next.reminderTime,
                        next.prepareDaysBefore, next.updatedAt, old.revision) == 1)
                }
            }
                mutable.value = null
                try { withContext(Dispatchers.IO) { container.dayimaReminders.reconcile() } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { mutable.value = "设置已保存，首页提醒稍后会更新" }
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.value = "未能更新提醒，请重试" }
            finally { working.value = false }
        }
    }
}

@Composable
internal fun DayimaReminderButton() {
    val context = LocalContext.current
    val owner = context.archiveActivity()
    val model = remember(owner) { ViewModelProvider(owner)[DayimaRemindersViewModel::class.java] }
    var open by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    TextButton({ closing = false; open = true }) { Text("提醒") }
    if (!open) return
    val settings by model.settings.collectAsStateWithLifecycle()
    val error by model.error.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val colors = scheduleColors()
    val saved = settings ?: DayimaReminderSettingsEntity(updatedAt = 0)
    FlashnoteDetailDialog(closing, { open = false }) { close ->
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("经期提醒", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                IconButton(close, enabled = !busy, modifier = Modifier.size(48.dp)) {
                    Icon(painterResource(R.drawable.ic_close), stringResource(R.string.close_panel), Modifier.size(22.dp))
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("经期前准备 · 提前 ${saved.prepareDaysBefore} 天", Modifier.weight(1f))
                Switch(saved.prepareEnabled, { model.update(prepare = it) }, enabled = !busy)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("超过预测范围后提醒补记", Modifier.weight(1f))
                Switch(saved.missingRecordEnabled, { model.update(missing = it) }, enabled = !busy)
            }
            Text("经期提醒在首页按日期显示。补记提醒从预测范围结束后的第一天开始。", color = colors.secondary,
                style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button({ closing = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("完成") }
        }
    }
}
