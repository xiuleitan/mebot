package com.txl.mebot.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal object TradingPositionMigration : Migration(30, 31) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `trading_trend_history` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `symbol` TEXT NOT NULL, `direction` TEXT NOT NULL, `recordedAt` INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_trading_trend_history_symbol_recordedAt` ON `trading_trend_history` (`symbol`, `recordedAt`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `trading_position_plans` (`scope` TEXT NOT NULL, `symbol` TEXT NOT NULL, `positionKey` TEXT NOT NULL, `stopLoss` TEXT, `takeProfit` TEXT, PRIMARY KEY(`scope`, `symbol`, `positionKey`))")
        db.execSQL(
            "INSERT INTO trading_trend_history (symbol, direction, recordedAt) SELECT symbol, direction, ? FROM trading_trend",
            arrayOf(System.currentTimeMillis()),
        )
    }
}
