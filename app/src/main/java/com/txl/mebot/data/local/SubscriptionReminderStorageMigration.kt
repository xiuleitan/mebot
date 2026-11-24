package com.txl.mebot.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** 经协调分配的 16→17，只追加提醒设置、可重建计划与每期交付回执。 */
object SubscriptionReminderStorageMigration : Migration(16, 17) {
    override fun migrate(db: SupportSQLiteDatabase) = SubscriptionReminderStorageSchema.createTables(db)
}
