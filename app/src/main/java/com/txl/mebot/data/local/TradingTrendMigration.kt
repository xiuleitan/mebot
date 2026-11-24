package com.txl.mebot.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal object TradingTrendMigration : Migration(28, 29) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `trading_trend` (`symbol` TEXT NOT NULL, `direction` TEXT NOT NULL, `reason` TEXT NOT NULL, PRIMARY KEY(`symbol`))")
    }
}
