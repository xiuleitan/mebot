package com.txl.mebot.data.home

import androidx.room.withTransaction
import com.txl.mebot.data.dayima.DayimaReminderKind
import com.txl.mebot.data.local.*
import com.txl.mebot.data.subscriptions.SubscriptionMoney
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId

/** 首页提醒快照和处理结果；不修改付款事实，也不合成业务记录。 */
class HomeEventStore(private val database: MebotDatabase, private val clock: Clock = Clock.systemUTC(),
    private val zone: () -> ZoneId = ZoneId::systemDefault) {
    private val dao get() = database.home()
    suspend fun sync(plan: DayimaReminderPlanEntity) = database.withTransaction {
        val prepare = plan.kind == DayimaReminderKind.PREPARE
        put(HomeEventEntity(plan.id, if (prepare) HomeEventKind.DAYIMA_PREPARE else HomeEventKind.DAYIMA_MISSING,
            plan.periodId, plan.periodId, plan.reminderDate,
            if (prepare) "经期前准备" else "补记本次经期",
            if (prepare) "本次预测范围：${plan.rangeStart} 至 ${plan.rangeEnd}，可以提前做好准备。"
            else "本次预测范围已过（${plan.rangeStart} 至 ${plan.rangeEnd}），如果已经开始，请补记真实开始日期。",
            createdAt = clock.millis(), updatedAt = clock.millis()))
    }
    suspend fun sync(plan: SubscriptionReminderPlanEntity, record: SubscriptionEntity) = database.withTransaction {
        val date = java.time.Instant.ofEpochMilli(plan.triggerAt).atZone(ZoneId.of(plan.zoneId)).toLocalDate()
        put(HomeEventEntity(subscriptionId(plan.subscriptionId, plan.cycleId), HomeEventKind.SUBSCRIPTION,
            record.id, plan.cycleId, date, record.title,
            "预计 ${plan.targetDate} 扣费 ${SubscriptionMoney.format(record.amountMinor, record.currencyCode)}。完成仅表示已处理提醒，付款请在订阅中自行记录。",
            createdAt = clock.millis(), updatedAt = clock.millis()))
    }
    private suspend fun put(value: HomeEventEntity) {
        val old = dao.event(value.id)
        if (old == null) dao.insertEvent(value)
        else {
            // 已出现的事项留在原日期；未来计划可跟随设置改期，处理结果不因重算丢失。
            val day = if (old.eventDate <= LocalDate.now(clock.withZone(zone())) || old.completedAt != null) old.eventDate else value.eventDate
            val next = old.copy(eventDate = day, title = value.title, body = value.body, cancelled = false)
            if (old != next) dao.updateEvent(next.copy(updatedAt = clock.millis(), revision = old.revision + 1))
        }
    }
    suspend fun cancel(id: String) = database.withTransaction {
        dao.event(id)?.takeIf { !it.cancelled }?.let { dao.updateEvent(it.copy(cancelled = true,
            updatedAt = clock.millis(), revision = it.revision + 1)) }
    }
    suspend fun rememberSchedule(occurrence: ScheduleOccurrenceEntity) {
        val today = LocalDate.now(clock.withZone(zone()))
        for (reminder in database.scheduleDao().reminders(occurrence.id)) {
            if (reminder.occurrenceRevision != occurrence.revision || reminder.status == ReminderStatus.CANCELLED) continue
            val date = java.time.Instant.ofEpochMilli(reminder.triggerAt).atZone(zone()).toLocalDate()
            if (date > today) continue
            val id = "schedule:${occurrence.id}:$date"
            if (dao.event(id) == null) dao.insertEvent(HomeEventEntity(id, HomeEventKind.SCHEDULE, occurrence.id, null,
                date, "", "", createdAt = clock.millis(), updatedAt = clock.millis()))
        }
    }
    suspend fun isHandled(id: String) = dao.event(id)?.completedAt != null

    /** 在经期正式写入的同一事务调用，记录成功后才完成已出现的补记提醒。 */
    suspend fun resolvePeriods() {
        val periods = database.dayimaDao().periods()
        val today = LocalDate.now(clock.withZone(zone()))
        for (event in dao.dayimaEvents()) {
            val origin = periods.firstOrNull { it.id == event.sourceId }
            if (origin == null) { cancel(event.id); continue }
            val next = periods.filter { it.startDate > origin.startDate && it.startDate <= today }.minByOrNull { it.startDate }
            if (event.kind == HomeEventKind.DAYIMA_PREPARE && next != null) { cancel(event.id); continue }
            if (event.resolvedById != null && next == null) {
                dao.updateEvent(event.copy(completedAt = null, resolvedById = null, cancelled = false,
                    updatedAt = clock.millis(), revision = event.revision + 1))
            }
            if (next != null && event.completedAt == null) {
                val appeared = event.eventDate <= today
                dao.updateEvent(event.copy(completedAt = if (appeared) clock.millis() else null,
                    resolvedById = next.id, cancelled = !appeared, updatedAt = clock.millis(), revision = event.revision + 1))
            }
        }
    }
    companion object { fun subscriptionId(id: String, cycle: String) = "subscription:$id:$cycle" }
}
