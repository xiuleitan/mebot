package com.txl.mebot.data.subscriptions

import androidx.room.withTransaction
import com.txl.mebot.data.local.*
import java.time.Clock

class SubscriptionReminderStorage(val subscriptions: SubscriptionStorage, val reminders: SubscriptionReminderDao) {
    val database get() = subscriptions.database
}

class SubscriptionReminderSettingsRepository(private val storage: SubscriptionReminderStorage, private val clock: Clock = Clock.systemUTC()) {
    suspend fun get(id: String): SubscriptionReminderSettingsEntity = storage.database.withTransaction {
        requireNotNull(storage.subscriptions.records.subscription(id)) { "订阅不存在" }
        storage.reminders.insertSettings(SubscriptionReminderSettingsEntity(id, updatedAt = clock.millis()))
        requireNotNull(storage.reminders.settings(id))
    }
    suspend fun save(id: String, expectedRevision: Long, enabled: Boolean, minuteOfDay: Int) = storage.database.withTransaction {
        require(minuteOfDay in 0..1439) { "请选择有效的提醒时刻" }
        val old = get(id)
        if (old.revision != expectedRevision) throw SubscriptionConflict("提醒设置已改变，请重新读取")
        if (old.enabled != enabled || old.minuteOfDay != minuteOfDay) {
            if (storage.reminders.saveSettings(id, enabled, minuteOfDay, expectedRevision, clock.millis()) != 1) throw SubscriptionConflict()
        }
        requireNotNull(storage.reminders.settings(id))
    }
}
