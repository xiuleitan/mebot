package com.txl.mebot.data.dayima

import androidx.room.withTransaction
import com.txl.mebot.data.home.HomeEventStore
import com.txl.mebot.data.local.DayimaDeliveryState
import com.txl.mebot.data.local.DayimaReminderPlanEntity
import com.txl.mebot.data.local.DayimaReminderSettingsEntity
import com.txl.mebot.data.local.MebotDatabase
import java.time.Clock
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 将本地周期预测同步到首页，处理结果和旧版投递记录保存在 Room。 */
class DayimaReminderEngine(
    private val database: MebotDatabase,
    private val repository: DayimaRepository,
    private val clock: Clock = Clock.systemUTC(),
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    private val dao = database.dayimaReminders()
    private val events = HomeEventStore(database, clock, zone)
    private val mutex = Mutex()
    private val started = AtomicBoolean(false)
    private val refreshes = Channel<Unit>(Channel.CONFLATED)

    fun start(scope: CoroutineScope) {
        if (!started.compareAndSet(false, true)) return
        scope.launch { repository.observeDataVersion().collect { requestRefresh() } }
        scope.launch { database.dayimaDao().observeReminderSettings().collect { requestRefresh() } }
        scope.launch {
            for (ignored in refreshes) {
                try { reconcile() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* 由定期维护或下次进入应用恢复。 */ }
            }
        }
        requestRefresh()
    }

    fun requestRefresh() { refreshes.trySend(Unit) }

    suspend fun reconcile() = mutex.withLock {
        database.withTransaction {
            events.resolvePeriods()
            val now = clock.instant().atZone(zone())
            val settings = database.dayimaDao().reminderSettings() ?: DayimaReminderSettingsEntity(updatedAt = 0)
            val input = DayimaReminderPlanner.candidates(repository.recordsSnapshot(), settings, now)
            val activeIds = input.mapTo(mutableSetOf()) { it.id }
            for (candidate in input) {
                val old = dao.plan(candidate.id)
                val same = old != null && old.periodRevision == candidate.periodRevision && old.triggerAt == candidate.triggerAt &&
                    old.dataVersion == candidate.dataVersion && old.ruleFingerprint == candidate.ruleFingerprint && old.zoneId == candidate.zoneId
                val plan = DayimaReminderPlanEntity(
                    candidate.id, candidate.periodId, candidate.periodDate, candidate.periodRevision, candidate.kind,
                    candidate.predictedDate, candidate.rangeStart, candidate.rangeEnd, candidate.reminderDate,
                    candidate.minuteOfDay, candidate.triggerAt, candidate.zoneId, candidate.dataVersion, candidate.ruleFingerprint,
                    if (same) requireNotNull(old).generation else UUID.randomUUID().toString(),
                    deliveryState = old?.deliveryState ?: DayimaDeliveryState.PENDING,
                    attemptId = old?.attemptId, attemptStartedAt = old?.attemptStartedAt, deliveredAt = old?.deliveredAt,
                    failure = old?.failure, createdAt = old?.createdAt ?: clock.millis(), updatedAt = old?.updatedAt ?: clock.millis(),
                )
                if (old != plan) dao.savePlan(plan.copy(updatedAt = clock.millis()))
                events.sync(plan)
            }
            for (old in dao.plans()) {
                if (old.id in activeIds) continue
                if (old.active) dao.savePlan(old.copy(active = false, updatedAt = clock.millis()))
                if (!events.isHandled(old.id)) events.cancel(old.id)
            }
        }
    }
}
