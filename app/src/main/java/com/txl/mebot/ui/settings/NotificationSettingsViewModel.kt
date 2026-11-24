package com.txl.mebot.ui.settings

import android.app.Application
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.txl.mebot.MebotApplication
import com.txl.mebot.data.reminders.CalendarRinging
import com.txl.mebot.data.reminders.ReminderEngineState
import com.txl.mebot.data.reminders.ReminderTestSchedule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class NotificationSettingsState(
    val loaded: Boolean = false,
    val busy: Boolean = false,
    val notificationsAllowed: Boolean = false,
    val calendarAccess: Boolean = false,
    val engine: ReminderEngineState = ReminderEngineState(),
    val test: ReminderTestSchedule? = null,
    val message: String? = null,
    val error: Boolean = false,
)

class NotificationSettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as MebotApplication).container
    private val mutableState = MutableStateFlow(NotificationSettingsState())
    val state = mutableState.asStateFlow()
    private var engineJob: Job? = null
    private var permissionsJob: Job? = null

    fun setVisible(visible: Boolean) {
        engineJob?.cancel()
        if (!visible) return
        engineJob = viewModelScope.launch {
            container.reminders.state.collect { engine -> mutableState.update { it.copy(engine = engine) } }
        }
        refreshPermissions()
    }

    fun refreshPermissions() {
        permissionsJob?.cancel()
        permissionsJob = viewModelScope.launch {
            val (notificationsAllowed, calendarAccess) = withContext(Dispatchers.IO) {
                NotificationManagerCompat.from(getApplication()).areNotificationsEnabled() to container.scheduleCalendar.hasAccess()
            }
            mutableState.update {
                it.copy(loaded = true, notificationsAllowed = notificationsAllowed, calendarAccess = calendarAccess)
            }
            container.refreshReminders()
        }
    }

    fun createTest() {
        if (mutableState.value.busy) return
        if (!mutableState.value.calendarAccess) {
            refreshPermissions()
            return
        }
        mutableState.update { it.copy(busy = true, message = null, test = null) }
        viewModelScope.launch {
            try {
                val created = withContext(Dispatchers.IO) { container.reminders.createTestSchedule() }
                val ringing = created.calendarRegistration?.ringing
                val result = when (ringing) {
                    CalendarRinging.ENABLED -> "已登记 ${created.time} 的日历提醒，并确认响铃开关已打开。"
                    CalendarRinging.UNAVAILABLE -> "已登记普通日历提醒。此设备的日历接口未提供响铃开关。"
                    CalendarRinging.UNCONFIRMED -> "已登记普通日历提醒，暂未确认响铃开关已打开。"
                    null -> "测试日程已保存，暂未确认日历提醒登记。"
                }
                mutableState.update {
                    it.copy(test = created, message = result, error = !created.registered || ringing == CalendarRinging.UNCONFIRMED)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                message("测试日程未能完成创建，请查看日程列表后重试。", true)
            } finally {
                mutableState.update { it.copy(busy = false) }
            }
        }
    }

    fun retryRegistration() { container.reminders.requestRefresh() }
    fun message(text: String, error: Boolean = false) { mutableState.update { it.copy(message = text, error = error) } }
}
