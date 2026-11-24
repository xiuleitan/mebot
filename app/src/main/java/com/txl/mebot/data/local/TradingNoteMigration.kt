package com.txl.mebot.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal object TradingNoteMigration : Migration(34, 35) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `trading_notes` (`symbol` TEXT NOT NULL, `text` TEXT NOT NULL, PRIMARY KEY(`symbol`))",
        )
    }
}
