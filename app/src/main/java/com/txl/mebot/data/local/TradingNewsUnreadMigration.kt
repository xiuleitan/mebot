package com.txl.mebot.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal object TradingNewsUnreadMigration : Migration(40, 41) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `trading_news` ADD COLUMN `unread` INTEGER NOT NULL DEFAULT 0")
    }
}
