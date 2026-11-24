package com.txl.mebot.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal object TradingWatchlistMigration : Migration(33, 34) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `trading_watchlist` (`symbol` TEXT NOT NULL, `asset` TEXT NOT NULL, " +
                "`priceDecimals` INTEGER NOT NULL, `addedAt` INTEGER NOT NULL, PRIMARY KEY(`symbol`))",
        )
    }
}
