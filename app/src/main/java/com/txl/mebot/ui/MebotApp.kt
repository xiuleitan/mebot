package com.txl.mebot.ui

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.txl.mebot.ui.home.HomeScreen
import com.txl.mebot.ui.flashnote.FlashnoteRoute
import com.txl.mebot.ui.flashnote.FlashnoteViewModel
import com.txl.mebot.ui.dayima.DayimaRoute
import com.txl.mebot.ui.dayima.DayimaViewModel
import com.txl.mebot.ui.dayima.DayimaChatViewModel
import com.txl.mebot.ui.navigation.BotDestination
import com.txl.mebot.ui.navigation.BotNavigationDrawer
import com.txl.mebot.ui.schedule.ScheduleRoute
import com.txl.mebot.ui.schedule.ScheduleConversationViewModel
import com.txl.mebot.ui.schedule.ScheduleViewModel
import com.txl.mebot.ui.schedule.ScheduleAttachmentsViewModel
import com.txl.mebot.ui.schedule.scheduleColors
import com.txl.mebot.ui.settings.SettingsScreen
import com.txl.mebot.ui.settings.AppearanceScreen
import com.txl.mebot.ui.settings.AppearanceViewModel
import com.txl.mebot.ui.settings.ModelSettingsScreen
import com.txl.mebot.ui.settings.ModelSettingsViewModel
import com.txl.mebot.ui.settings.NotificationSettingsScreen
import com.txl.mebot.ui.settings.NotificationSettingsViewModel
import com.txl.mebot.ui.settings.TradingSettingsScreen
import com.txl.mebot.ui.settings.settingsColors
import com.txl.mebot.ui.theme.MebotTheme
import com.txl.mebot.ui.theme.AppTheme
import com.txl.mebot.ui.subscriptions.SubscriptionsRoute
import com.txl.mebot.ui.archive.ArchiveRoute
import com.txl.mebot.ui.trading.TradingBackground
import com.txl.mebot.ui.trading.TradingRoute
import com.txl.mebot.ui.subscriptions.SubscriptionsViewModel
import java.time.LocalDate

private val SettingsSubpages = setOf(
    BotDestination.Appearance,
    BotDestination.Models,
    BotDestination.Notifications,
    BotDestination.TradingSettings,
)

@Composable
fun MebotApp(
    homeModel: com.txl.mebot.ui.home.HomeViewModel? = null,
    reflectionModel: com.txl.mebot.ui.home.HomeReflectionViewModel? = null,
    homeComposerModel: com.txl.mebot.ui.home.HomeComposerViewModel? = null,
    pendingHomeEvent: String? = null, onHomeEventConsumed: () -> Unit = {},
    onThemeSelected: (AppTheme) -> Unit = {}, themeSaveFailed: Boolean = false,
    conversationModel: ScheduleConversationViewModel? = null,
    scheduleModel: ScheduleViewModel? = null,
    scheduleEditorModel: com.txl.mebot.ui.schedule.ScheduleEditorViewModel? = null,
    flashnoteModel: FlashnoteViewModel? = null,
    dayimaModel: DayimaViewModel? = null,
    dayimaChatModel: DayimaChatViewModel? = null,
    subscriptionsModel: SubscriptionsViewModel? = null,
    attachmentModel: ScheduleAttachmentsViewModel? = null,
    modelSettings: ModelSettingsViewModel? = null,
    notificationSettings: NotificationSettingsViewModel? = null,
    hapticSettings: com.txl.mebot.ui.settings.HapticSettingsViewModel? = null,
    pendingReminderId: String? = null,
    onReminderConsumed: () -> Unit = {},
    pendingSubscriptionId: String? = null,
    onSubscriptionReminderConsumed: () -> Unit = {},
    appearanceModel: AppearanceViewModel? = null,
    onTradingSystemBarsChanged: (Boolean) -> Unit = {},
) {
    var archiveQuery by rememberSaveable { mutableStateOf<String?>(null) }
    var destination by rememberSaveable { mutableStateOf(BotDestination.Home) }
    var settingsParent by rememberSaveable { mutableStateOf(BotDestination.Home) }
    var closeDrawerRequest by rememberSaveable { mutableStateOf(0L) }
    val pageStateHolder = rememberSaveableStateHolder()
    SideEffect { onTradingSystemBarsChanged(destination == BotDestination.Trading) }
    val schedulePalette = scheduleColors()
    val settingsPalette = settingsColors()
    val navigation = updateTransition(destination, label = "bot_page")
    val returnFromSettings: () -> Unit = { destination = settingsParent }

    LaunchedEffect(pendingHomeEvent) {
        pendingHomeEvent?.let { homeModel?.openEvent(it); closeDrawerRequest++; destination = BotDestination.Home; onHomeEventConsumed() }
    }
    LaunchedEffect(pendingReminderId) {
        pendingReminderId?.let { id ->
            scheduleModel?.openFromReminder(id)
            closeDrawerRequest++
            destination = BotDestination.Schedule
            onReminderConsumed()
        }
    }

    LaunchedEffect(pendingSubscriptionId) {
        pendingSubscriptionId?.let { id ->
            subscriptionsModel?.openFromReminder(id)
            closeDrawerRequest++
            destination = BotDestination.Subscriptions
            onSubscriptionReminderConsumed()
        }
    }

    BackHandler(enabled = destination != BotDestination.Home) {
        destination = when (destination) {
            in SettingsSubpages -> BotDestination.Settings
            BotDestination.Settings -> settingsParent
            else -> BotDestination.Home
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = when (destination) {
            BotDestination.Schedule, BotDestination.Flashnote, BotDestination.Dayima, BotDestination.Subscriptions, BotDestination.Archive -> schedulePalette.backgroundTop
            BotDestination.Trading -> TradingBackground
            BotDestination.Settings, BotDestination.Appearance, BotDestination.Models,
            BotDestination.Notifications, BotDestination.TradingSettings -> settingsPalette.backgroundTop
            BotDestination.Home -> MaterialTheme.colorScheme.background
        },
    ) {
        BotNavigationDrawer(
            destination = destination,
            closeRequest = closeDrawerRequest,
            onDestinationSelected = { next ->
                if (next == BotDestination.Settings &&
                    destination != BotDestination.Settings && destination !in SettingsSubpages
                ) {
                    settingsParent = destination
                }
                if (next == BotDestination.Schedule) scheduleModel?.selectDate(LocalDate.now())
                destination = next
            },
        ) { openDrawer ->
            navigation.AnimatedContent(
                modifier = Modifier.fillMaxSize(),
                transitionSpec = {
                    val direction = when {
                        targetState in SettingsSubpages -> 1
                        initialState in SettingsSubpages -> -1
                        targetState == BotDestination.Settings -> 1
                        initialState == BotDestination.Settings -> -1
                        targetState in setOf(BotDestination.Schedule, BotDestination.Flashnote, BotDestination.Dayima, BotDestination.Subscriptions, BotDestination.Archive, BotDestination.Trading) -> 1
                        else -> -1
                    }
                    (fadeIn(tween(280)) + slideInHorizontally(tween(320)) { it / 12 * direction })
                        .togetherWith(
                            fadeOut(tween(180)) + slideOutHorizontally(tween(320)) { -it / 12 * direction },
                        )
                },
            ) { currentDestination ->
                pageStateHolder.SaveableStateProvider(currentDestination.name) {
                    when (currentDestination) {
                        BotDestination.Notifications -> NotificationSettingsScreen(
                            onBack = { destination = BotDestination.Settings },
                            onOpenSchedule = { date -> scheduleModel?.selectDate(date); destination = BotDestination.Schedule },
                            model = notificationSettings,
                        )
                        BotDestination.Models -> ModelSettingsScreen(
                            onBack = { destination = BotDestination.Settings }, model = modelSettings,
                        )
                        BotDestination.Appearance -> AppearanceScreen(
                            onBack = { destination = BotDestination.Settings },
                            onThemeSelected = onThemeSelected,
                            saveFailed = themeSaveFailed,
                            model = appearanceModel,
                        )
                        BotDestination.TradingSettings -> TradingSettingsScreen(
                            onBack = { destination = BotDestination.Settings },
                        )
                        BotDestination.Settings -> SettingsScreen(
                            onBack = returnFromSettings,
                            hapticModel = hapticSettings,
                            onAppearanceClick = { destination = BotDestination.Appearance },
                            onModelsClick = { destination = BotDestination.Models },
                            onNotificationsClick = { destination = BotDestination.Notifications },
                            onTradingClick = { destination = BotDestination.TradingSettings },
                        )
                        BotDestination.Schedule -> ScheduleRoute(
                            model = scheduleModel, editorModel = scheduleEditorModel,
                            onOpenDrawer = openDrawer,
                            onReturnHome = { destination = BotDestination.Home },
                            conversationModel = conversationModel,
                            attachmentModel = attachmentModel,
                        )
                        BotDestination.Flashnote -> FlashnoteRoute(
                            model = flashnoteModel,
                            onOpenDrawer = openDrawer,
                            onReturnHome = { destination = BotDestination.Home },
                        )
                        BotDestination.Dayima -> DayimaRoute(
                            model = dayimaModel,
                            chatModel = dayimaChatModel,
                            onOpenDrawer = openDrawer,
                            onReturnHome = { destination = BotDestination.Home },
                        )
                        BotDestination.Subscriptions -> SubscriptionsRoute(
                            model = subscriptionsModel,
                            onOpenDrawer = openDrawer,
                            onReturnHome = { destination = BotDestination.Home },
                        )
                        BotDestination.Trading -> TradingRoute(onReturnHome = { destination = BotDestination.Home })
                        BotDestination.Archive -> ArchiveRoute(
                            pendingSearch = archiveQuery, onSearchConsumed = { archiveQuery = null },
                            onOpenDrawer = openDrawer,
                            onReturnHome = { destination = BotDestination.Home },
                        )
                        BotDestination.Home -> if (homeModel != null) com.txl.mebot.ui.home.HomeRoute(homeModel,
                            onOpenDrawer = openDrawer,
                            onOpenTrading = { destination = BotDestination.Trading },
                            onSearchArchive = { query -> archiveQuery = query; destination = BotDestination.Archive },
                            scheduleModel = scheduleModel, scheduleEditorModel = scheduleEditorModel, conversationModel = conversationModel, attachmentModel = attachmentModel,
                            flashnoteModel = flashnoteModel, subscriptionsModel = subscriptionsModel, dayimaModel = dayimaModel,
                            reflectionModel = reflectionModel,
                            composerModel = homeComposerModel,
                            dayimaChatModel = dayimaChatModel,
                            detailsEnabled = !navigation.isRunning && navigation.currentState == BotDestination.Home) else HomeScreen(
                            modifier = Modifier.fillMaxSize(), onOpenDrawer = openDrawer,
                                onOpenTrading = { destination = BotDestination.Trading },
                        )
                    }
                }
            }
        }
    }
}

@Preview(name = "主界面", showBackground = true)
@Preview(name = "深色主界面", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun MebotAppPreview() {
    MebotTheme {
        MebotApp()
    }
}
