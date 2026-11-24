package com.txl.mebot.data.home

import com.txl.mebot.data.local.*

interface SubscriptionHomeBridge {
    suspend fun sync(plan: SubscriptionReminderPlanEntity, record: SubscriptionEntity)
    suspend fun cancel(plan: SubscriptionReminderPlanEntity)
    suspend fun handled(id: String, cycle: String): Boolean
}
class RoomSubscriptionHomeBridge(database: MebotDatabase) : SubscriptionHomeBridge {
    private val store = HomeEventStore(database)
    override suspend fun sync(plan: SubscriptionReminderPlanEntity, record: SubscriptionEntity) { store.sync(plan, record) }
    override suspend fun cancel(plan: SubscriptionReminderPlanEntity) { store.cancel(HomeEventStore.subscriptionId(plan.subscriptionId, plan.cycleId)) }
    override suspend fun handled(id: String, cycle: String) = store.isHandled(HomeEventStore.subscriptionId(id, cycle))
}
