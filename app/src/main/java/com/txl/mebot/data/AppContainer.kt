package com.txl.mebot.data

import android.content.Context
import com.txl.mebot.data.local.MebotDatabase
import com.txl.mebot.data.local.DayimaDao
import com.txl.mebot.data.dayima.DayimaRepository
import com.txl.mebot.data.flashnote.FlashnoteRepository
import com.txl.mebot.data.flashnote.FlashnoteAttachmentRepository
import com.txl.mebot.data.media.LocalMediaStore
import com.txl.mebot.data.schedule.ScheduleRepository
import com.txl.mebot.data.schedule.ScheduleConversationRepository
import com.txl.mebot.data.schedule.ScheduleAttachmentRepository
import com.txl.mebot.data.schedule.ScheduleCoverRepository
import com.txl.mebot.data.schedule.ScheduleCoverWorker
import com.txl.mebot.data.models.AndroidKeystoreCredentialCipher
import com.txl.mebot.data.trading.FearGreedRepository
import com.txl.mebot.data.trading.TradingPerformanceWorker
import com.txl.mebot.data.trading.TradingRepository
import com.txl.mebot.data.trading.alerts.ProbeAlertClients
import com.txl.mebot.data.trading.alerts.TradingAlertsRepository
import com.txl.mebot.data.trading.news.TradingNewsRepository
import com.txl.mebot.data.trading.news.TradingNewsRegistrationWorker
import com.txl.mebot.data.models.ModelSettingsRepository
import com.txl.mebot.data.models.ModelConnectionChecker
import com.txl.mebot.data.models.ZImageCoverGenerator
import com.txl.mebot.data.schedule.QwenScheduleTextInterpreter
import com.txl.mebot.data.schedule.ScheduleCreationCoordinator
import com.txl.mebot.data.reminders.LegacyScheduleAlarms
import com.txl.mebot.data.reminders.RetiredReminderNotifications
import com.txl.mebot.data.reminders.ScheduleCalendarReminders
import com.txl.mebot.data.reminders.ScheduleReminderEngine
import com.txl.mebot.data.reminders.ReminderMaintenanceWorker
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CancellationException

/** Application 持有一套主库与数据接口，后续 ViewModel 按需使用。 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val database: MebotDatabase by lazy { MebotDatabase.create(appContext) }
    internal val trading by lazy {
        TradingRepository(
            database,
            AndroidKeystoreCredentialCipher("mebot.binance.v1", "mebot:binance:v1"),
            media = media,
        )
    }
    internal val tradingFearGreed by lazy {
        FearGreedRepository(File(appContext.cacheDir, "trading-fear-greed-v1"))
    }
    internal val tradingAlerts by lazy {
        val clients = ProbeAlertClients(appContext)
        TradingAlertsRepository(
            database, AndroidKeystoreCredentialCipher("mebot.trading-alerts.v1", "mebot:trading-alerts:v1"),
            appContext.packageName, clients::client,
        )
    }
    internal val tradingNews by lazy { TradingNewsRepository(database, tradingAlerts::recentNews) }
    val dayimaStorage: DayimaDao by lazy { database.dayimaDao() }
    val dayima: DayimaRepository by lazy { DayimaRepository(database) }
    val dayimaAttachments by lazy { com.txl.mebot.data.dayima.DayimaAttachmentRepository(appContext, database, media) }
    val dayimaConversations: com.txl.mebot.data.dayima.DayimaConversationRepository by lazy {
        com.txl.mebot.data.dayima.DayimaConversationRepository(database)
    }
    val dayimaChat: com.txl.mebot.data.dayima.DayimaChatCoordinator by lazy {
        com.txl.mebot.data.dayima.DayimaChatCoordinator(database, dayima, dayimaConversations,
            com.txl.mebot.data.dayima.QwenDayimaInterpreter(modelSettings))
    }
    val dayimaReminders by lazy { com.txl.mebot.data.dayima.DayimaReminderEngine(database, dayima) }
    val flashnotes: FlashnoteRepository by lazy { FlashnoteRepository(database) }
    val flashnoteAttachments: FlashnoteAttachmentRepository by lazy { FlashnoteAttachmentRepository(appContext, flashnotes, media) }
    val schedules: ScheduleRepository by lazy { ScheduleRepository(database) }
    val home by lazy { com.txl.mebot.data.home.HomeRepository(database, schedules, flashnotes, dayima) }
    val homeReflection by lazy { com.txl.mebot.data.home.HomeReflectionRepository(appContext, database, media) }
    val homeComposer by lazy { com.txl.mebot.data.home.HomeComposerRepository(appContext, database, media) }
    val homeRoutingSettings by lazy { com.txl.mebot.data.home.HomeRoutingSettings(database, com.txl.mebot.BuildConfig.DEFAULT_DECISION_ENDPOINT) }
    val homeTextRouter by lazy { com.txl.mebot.data.home.BailianHomeTextRouter(homeRoutingSettings, modelSettings) }
    val homeDispatch by lazy { com.txl.mebot.data.home.HomeDispatchCoordinator(appContext, this) }
    val scheduleCalendar: ScheduleCalendarReminders by lazy { ScheduleCalendarReminders(appContext) }
    val reminders: ScheduleReminderEngine by lazy {
        ScheduleReminderEngine(database, schedules, scheduleCalendar, LegacyScheduleAlarms(appContext)::cancel)
    }
    val conversations: ScheduleConversationRepository by lazy { ScheduleConversationRepository(database, schedules) }
    val media: LocalMediaStore by lazy { LocalMediaStore(appContext, database) }
    val subscriptionStorage by lazy { com.txl.mebot.data.subscriptions.SubscriptionStorage(
        database, database.subscriptions(), database.subscriptionConversations(), database.mediaDao()) }
    val subscriptions by lazy { com.txl.mebot.data.subscriptions.SubscriptionRepository(subscriptionStorage) }
    val subscriptionConversations by lazy { com.txl.mebot.data.subscriptions.SubscriptionConversationRepository(subscriptionStorage) }
    val subscriptionAttachments by lazy { com.txl.mebot.data.subscriptions.SubscriptionAttachmentRepository(appContext, subscriptionStorage, media) }
    val subscriptionLifecycle by lazy { com.txl.mebot.data.subscriptions.SubscriptionLifecycle(subscriptionStorage, subscriptions) }
    internal val subscriptionChat by lazy { com.txl.mebot.data.subscriptions.SubscriptionChatCoordinator(subscriptionStorage, subscriptions,
        subscriptionConversations, subscriptionLifecycle, com.txl.mebot.data.subscriptions.QwenSubscriptionInterpreter(modelSettings), media::fileFor) }
    val subscriptionReminderStorage by lazy { com.txl.mebot.data.subscriptions.SubscriptionReminderStorage(subscriptionStorage, database.subscriptionReminders()) }
    val subscriptionReminderSettings by lazy { com.txl.mebot.data.subscriptions.SubscriptionReminderSettingsRepository(subscriptionReminderStorage) }
    val subscriptionReminders by lazy { com.txl.mebot.data.subscriptions.SubscriptionReminderEngine(subscriptionReminderStorage,
        subscriptionLifecycle, com.txl.mebot.data.subscriptions.SubscriptionStatisticsRepository(subscriptionStorage),
        subscriptionReminderSettings, com.txl.mebot.data.home.RoomSubscriptionHomeBridge(database)) }
    val archiveStorage by lazy { com.txl.mebot.data.archive.ArchiveStorage(
        database, database.archives(), com.txl.mebot.data.archive.ArchiveCipher.androidKeystore()) }
    val archives by lazy { com.txl.mebot.data.archive.ArchiveRepository(archiveStorage) }
    val archiveAttachments by lazy { com.txl.mebot.data.archive.ArchiveAttachmentRepository(appContext, archiveStorage) }
    val archiveIndex by lazy { com.txl.mebot.data.archive.ArchiveIndexRepository(archiveStorage) }
    val archiveSemantic by lazy { com.txl.mebot.data.archive.ArchiveSemanticEngine(
        com.txl.mebot.data.archive.ArchiveEmbeddingSettingsRepository(archiveStorage, archiveIndex, modelSettings), archiveIndex, archives,
        enqueue = { com.txl.mebot.data.archive.ArchiveIndexWorker.enqueue(appContext) }) }
    val scheduleEditor by lazy { com.txl.mebot.data.schedule.ScheduleEditorRepository(appContext, database, schedules, scheduleAttachments, media) }
    val scheduleAttachments: ScheduleAttachmentRepository by lazy { ScheduleAttachmentRepository(appContext, database, media) }
    val modelSettings: ModelSettingsRepository by lazy { ModelSettingsRepository(database, AndroidKeystoreCredentialCipher()) }
    val modelConnectionChecker: ModelConnectionChecker by lazy { ModelConnectionChecker() }
    val scheduleCovers: ScheduleCoverRepository by lazy {
        ScheduleCoverRepository(database, media, ZImageCoverGenerator(modelSettings),
            enqueue = { ScheduleCoverWorker.enqueue(appContext, it) })
    }
    val scheduleCreation: ScheduleCreationCoordinator by lazy {
        ScheduleCreationCoordinator(database, schedules, conversations, QwenScheduleTextInterpreter(modelSettings),
            cleanMedia = media::cleanPendingDeletions, covers = scheduleCovers)
    }

    fun startMaintenance() {
        applicationScope.launch {
            runCatching { RetiredReminderNotifications(appContext).clear() }
        }
        applicationScope.launch {
            try {
                tradingNews.configuredDevice.collect { deviceId ->
                    if (deviceId != null) TradingNewsRegistrationWorker.enqueue(appContext)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) { /* The trading screen can still retry registration. */ }
        }
        applicationScope.launch {
            try {
                var previousScope: String? = null
                trading.performanceAccount.collect { scope ->
                    if (scope == null) TradingPerformanceWorker.cancel(appContext)
                    else TradingPerformanceWorker.schedule(appContext, replaceCatchUp = previousScope != null)
                    previousScope = scope
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) { /* 其他 Bot 的本地维护照常运行。 */ }
        }
        applicationScope.launch {
            try {
                dayimaReminders.start(applicationScope)
                com.txl.mebot.data.dayima.DayimaReminderWorker.keepPeriodic(appContext)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* 经期提醒维护失败不阻塞其他本地功能。 */ }
        }
        applicationScope.launch {
            try { archiveSemantic.start() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* 档案页面显示本地索引状态；不阻塞其他功能。 */ }
        }
        applicationScope.launch {
            try {
                subscriptionReminders.start(applicationScope)
                com.txl.mebot.data.subscriptions.SubscriptionReminderWorker.keepPeriodic(appContext)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* 订阅页会显示本地读取错误；不阻塞其他 Bot 的维护。 */ }
        }
        applicationScope.launch {
            reminders.start(applicationScope)
            runCatching { ReminderMaintenanceWorker.keepPeriodic(appContext) }
            runCatching { media.cleanPendingDeletions() }
            runCatching { archiveAttachments.recoverFiles() }
            runCatching { scheduleCovers.resumePending() }
        }
    }

    fun refreshReminders() {
        applicationScope.launch {
            try { dayimaReminders.start(applicationScope); dayimaReminders.reconcile() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* 系统恢复与下次进入时重新校验。 */ }
        }
        applicationScope.launch {
            try {
                subscriptionReminders.start(applicationScope)
                subscriptionReminders.reconcile()
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* 下次应用恢复时继续重试。 */ }
        }
        applicationScope.launch {
            reminders.start(applicationScope)
            reminders.requestRefresh()
        }
    }

    fun stopMaintenance() { applicationScope.cancel() }
}
