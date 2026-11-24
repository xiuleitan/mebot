package com.txl.mebot.data.local

import androidx.sqlite.db.SupportSQLiteDatabase

/** schema 17 的追加 DDL；不改写已安装版本或其他 Bot 的结构。 */
object SubscriptionReminderStorageSchema {
    val tables = listOf("subscription_reminder_settings", "subscription_reminder_plans", "subscription_reminder_deliveries")
    fun createTables(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS subscription_reminder_settings (
            subscriptionId TEXT NOT NULL, enabled INTEGER NOT NULL, minuteOfDay INTEGER NOT NULL,
            revision INTEGER NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(subscriptionId),
            FOREIGN KEY(subscriptionId) REFERENCES subscriptions(id) ON UPDATE NO ACTION ON DELETE NO ACTION)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS subscription_reminder_plans (
            subscriptionId TEXT NOT NULL, cycleId TEXT NOT NULL, targetType TEXT NOT NULL, targetDate INTEGER NOT NULL,
            triggerAt INTEGER NOT NULL, zoneId TEXT NOT NULL, subscriptionRevision INTEGER NOT NULL,
            settingsRevision INTEGER NOT NULL, generation TEXT NOT NULL, state TEXT NOT NULL, failure TEXT,
            updatedAt INTEGER NOT NULL, PRIMARY KEY(subscriptionId),
            FOREIGN KEY(subscriptionId) REFERENCES subscriptions(id) ON UPDATE NO ACTION ON DELETE NO ACTION)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_subscription_reminder_plans_state_triggerAt ON subscription_reminder_plans(state, triggerAt)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS subscription_reminder_deliveries (
            subscriptionId TEXT NOT NULL, cycleId TEXT NOT NULL, state TEXT NOT NULL, attemptId TEXT NOT NULL,
            planGeneration TEXT NOT NULL, targetType TEXT NOT NULL, targetDate INTEGER NOT NULL, startedAt INTEGER NOT NULL,
            deliveredAt INTEGER, updatedAt INTEGER NOT NULL, PRIMARY KEY(subscriptionId, cycleId),
            FOREIGN KEY(subscriptionId) REFERENCES subscriptions(id) ON UPDATE NO ACTION ON DELETE NO ACTION)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_subscription_reminder_deliveries_state ON subscription_reminder_deliveries(state)")
    }
}
