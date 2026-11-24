package com.txl.mebot.data.subscriptions

import androidx.room.withTransaction
import com.txl.mebot.data.home.SubscriptionHomeBridge
import com.txl.mebot.data.local.SubscriptionReminderDeliveryState
import com.txl.mebot.data.local.SubscriptionReminderPlanEntity
import com.txl.mebot.data.local.SubscriptionReminderPlanState
import com.txl.mebot.data.local.SubscriptionStatus
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

/** 同步自动续费提醒到首页，续费周期、处理记录和付款事实各自独立。 */
class SubscriptionReminderEngine(
    private val storage: SubscriptionReminderStorage,
    private val lifecycle: SubscriptionLifecycle,
    private val statistics: SubscriptionStatisticsRepository,
    private val settings: SubscriptionReminderSettingsRepository,
    private val home: SubscriptionHomeBridge,
    private val clock: Clock = Clock.systemUTC(),
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    private val dao = storage.reminders
    private val mutex = Mutex()
    private val started = AtomicBoolean(false)
    private val refreshes = Channel<Unit>(Channel.CONFLATED)

    fun start(scope: CoroutineScope) {
        if (!started.compareAndSet(false, true)) return
        scope.launch { storage.subscriptions.records.observeAll().collect { requestRefresh() } }
        scope.launch { dao.observeSettings().collect { requestRefresh() } }
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
        val now = clock.instant().atZone(zone())
        lifecycle.reconcile(now.toLocalDate())
        val input = statistics.read()
        val candidates = mutableSetOf<String>()
        var loadedLatest = true
        for (record in input.records) {
            if (!record.autoRenew || record.status != SubscriptionStatus.ACTIVE) continue
            val preference = settings.get(record.id)
            val candidate = SubscriptionReminderPlanner.next(
                record, input.changes[record.id].orEmpty(), input.payments, preference, now,
            ) ?: continue
            candidates += record.id
            val synced = storage.database.withTransaction {
                if (storage.subscriptions.records.subscription(record.id)?.revision != record.revision ||
                    dao.settings(record.id)?.revision != preference.revision) return@withTransaction false
                val old = dao.plan(record.id)
                val same = old != null && old.cycleId == candidate.cycleId && old.targetType == candidate.targetType &&
                    old.targetDate == candidate.targetDate && old.triggerAt == candidate.triggerAt && old.zoneId == candidate.zoneId &&
                    old.subscriptionRevision == candidate.subscriptionRevision && old.settingsRevision == candidate.settingsRevision
                val state = if (home.handled(record.id, candidate.cycleId)) SubscriptionReminderPlanState.HANDLED
                else when (dao.delivery(record.id, candidate.cycleId)?.state) {
                    SubscriptionReminderDeliveryState.DELIVERED -> SubscriptionReminderPlanState.DELIVERED
                    SubscriptionReminderDeliveryState.UNCERTAIN -> SubscriptionReminderPlanState.UNCERTAIN
                    else -> SubscriptionReminderPlanState.PENDING
                }
                val plan = SubscriptionReminderPlanEntity(
                    record.id, candidate.cycleId, candidate.targetType, candidate.targetDate, candidate.triggerAt, candidate.zoneId,
                    candidate.subscriptionRevision, candidate.settingsRevision,
                    if (same) requireNotNull(old).generation else UUID.randomUUID().toString(), state,
                    updatedAt = old?.updatedAt ?: clock.millis(),
                )
                if (old != plan) dao.savePlan(plan.copy(updatedAt = clock.millis()))
                home.sync(plan, record)
                true
            }
            if (!synced) loadedLatest = false
        }
        storage.database.withTransaction {
            for (old in dao.plans()) {
                if (old.subscriptionId in candidates) continue
                home.cancel(old)
                dao.deletePlan(old.subscriptionId)
            }
        }
        loadedLatest
    }
}
