package com.txl.mebot

import android.graphics.Color
import android.content.Intent
import android.os.Bundle
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.txl.mebot.ui.MebotApp
import com.txl.mebot.ui.settings.AppearanceViewModel
import com.txl.mebot.ui.settings.ModelSettingsViewModel
import com.txl.mebot.ui.settings.NotificationSettingsViewModel
import com.txl.mebot.data.reminders.LegacyScheduleAlarms
import com.txl.mebot.data.reminders.RetiredReminderNotifications
import com.txl.mebot.ui.schedule.ScheduleConversationViewModel
import com.txl.mebot.ui.schedule.ScheduleViewModel
import com.txl.mebot.ui.schedule.ScheduleAttachmentsViewModel
import com.txl.mebot.ui.flashnote.FlashnoteViewModel
import com.txl.mebot.ui.dayima.DayimaViewModel
import com.txl.mebot.ui.dayima.DayimaChatViewModel
import com.txl.mebot.ui.subscriptions.SubscriptionsViewModel
import com.txl.mebot.ui.theme.AppTheme
import com.txl.mebot.ui.theme.MebotTheme

class MainActivity : ComponentActivity() {
    private var pendingHomeEvent by mutableStateOf<String?>(null)
    private var pendingReminderId by mutableStateOf<String?>(null)
    private var pendingSubscriptionId by mutableStateOf<String?>(null)

    override fun onResume() {
        super.onResume()
        (application as MebotApplication).container.refreshReminders()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingHomeEvent = intent.getStringExtra(RetiredReminderNotifications.EXTRA_DAYIMA_EVENT)
        pendingReminderId = intent.getStringExtra(LegacyScheduleAlarms.EXTRA_OCCURRENCE)
        pendingSubscriptionId = intent.getStringExtra(RetiredReminderNotifications.EXTRA_SUBSCRIPTION)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingHomeEvent = intent.getStringExtra(RetiredReminderNotifications.EXTRA_DAYIMA_EVENT)
        pendingReminderId = intent.getStringExtra(LegacyScheduleAlarms.EXTRA_OCCURRENCE)
        pendingSubscriptionId = intent.getStringExtra(RetiredReminderNotifications.EXTRA_SUBSCRIPTION)
        val home = ViewModelProvider(this)[com.txl.mebot.ui.home.HomeViewModel::class.java]
        val reflection = ViewModelProvider(this)[com.txl.mebot.ui.home.HomeReflectionViewModel::class.java]
        val homeComposer = ViewModelProvider(this)[com.txl.mebot.ui.home.HomeComposerViewModel::class.java]
        val appearance = ViewModelProvider(this)[AppearanceViewModel::class.java]
        val haptics = ViewModelProvider(this)[com.txl.mebot.ui.settings.HapticSettingsViewModel::class.java]
        val conversations = ViewModelProvider(this)[ScheduleConversationViewModel::class.java]
        val schedules = ViewModelProvider(this)[ScheduleViewModel::class.java]
        val scheduleEditor = ViewModelProvider(this)[com.txl.mebot.ui.schedule.ScheduleEditorViewModel::class.java]
        val flashnotes = ViewModelProvider(this)[FlashnoteViewModel::class.java]
        val dayima = ViewModelProvider(this)[DayimaViewModel::class.java]
        val dayimaChat = ViewModelProvider(this)[DayimaChatViewModel::class.java]
        val subscriptions = ViewModelProvider(this)[SubscriptionsViewModel::class.java]
        val attachments = ViewModelProvider(this)[ScheduleAttachmentsViewModel::class.java]
        val modelSettings = ViewModelProvider(this)[ModelSettingsViewModel::class.java]
        val notificationSettings = ViewModelProvider(this)[NotificationSettingsViewModel::class.java]
        val decorView = window.decorView
        // 后台读取本地偏好，在首次绘制前就确定主题，避免先显示错误的深浅色页面。
        decorView.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (!appearance.state.value.isLoaded) return false
                decorView.viewTreeObserver.removeOnPreDrawListener(this)
                return true
            }
        })
        setContent {
            val state by appearance.state.collectAsStateWithLifecycle()
            val hapticState by haptics.state.collectAsStateWithLifecycle()
            val theme = state.theme ?: AppTheme.defaultFor(isSystemInDarkTheme())
            var tradingSystemBars by remember { mutableStateOf(false) }
            val useDarkBars = theme.isDark || tradingSystemBars
            SideEffect {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { useDarkBars },
                    navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { useDarkBars },
                )
            }
            if (state.isLoaded) {
                MebotTheme(theme = theme) {
                    com.txl.mebot.ui.common.AppHapticProvider(hapticState.loaded && hapticState.enabled) {
                        MebotApp(
                            homeModel = home, reflectionModel = reflection, homeComposerModel = homeComposer, pendingHomeEvent = pendingHomeEvent,
                            onHomeEventConsumed = { pendingHomeEvent = null; intent.removeExtra(RetiredReminderNotifications.EXTRA_DAYIMA_EVENT) },
                            onThemeSelected = appearance::selectTheme, themeSaveFailed = state.saveFailed,
                            appearanceModel = appearance,
                            onTradingSystemBarsChanged = { tradingSystemBars = it },
                            conversationModel = conversations,
                            scheduleModel = schedules, scheduleEditorModel = scheduleEditor,
                            flashnoteModel = flashnotes,
                            dayimaModel = dayima,
                            dayimaChatModel = dayimaChat,
                            subscriptionsModel = subscriptions,
                            attachmentModel = attachments,
                            modelSettings = modelSettings,
                            notificationSettings = notificationSettings,
                            hapticSettings = haptics,
                            pendingReminderId = pendingReminderId,
                            onReminderConsumed = {
                                pendingReminderId = null
                                intent.removeExtra(LegacyScheduleAlarms.EXTRA_OCCURRENCE)
                            },
                            pendingSubscriptionId = pendingSubscriptionId,
                            onSubscriptionReminderConsumed = {
                                pendingSubscriptionId = null
                                intent.removeExtra(RetiredReminderNotifications.EXTRA_SUBSCRIPTION)
                            },
                        )
                    }
                }
            }
        }
    }
}
