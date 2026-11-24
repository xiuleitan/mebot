package com.txl.mebot.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal object TradingNewsMigration : Migration(39, 40) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `trading_news` (" +
                "`deviceId` TEXT NOT NULL, `channel` TEXT NOT NULL, `newsId` TEXT NOT NULL, " +
                "`title` TEXT NOT NULL, `publishedAt` INTEGER NOT NULL, `url` TEXT, " +
                "`noticeClaimed` INTEGER NOT NULL, PRIMARY KEY(`deviceId`, `channel`, `newsId`))",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_trading_news_deviceId_publishedAt` ON `trading_news` (`deviceId`, `publishedAt`)")
    }
}
