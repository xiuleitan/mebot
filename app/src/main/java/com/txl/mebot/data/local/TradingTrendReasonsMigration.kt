package com.txl.mebot.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal object TradingTrendReasonsMigration : Migration(29, 30) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `trading_trend` ADD COLUMN `bullishReason` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `trading_trend` ADD COLUMN `bearishReason` TEXT NOT NULL DEFAULT ''")
        db.execSQL(
            """
            UPDATE `trading_trend`
            SET `bullishReason` = CASE WHEN `direction` = 'BULLISH' THEN `reason` ELSE '' END,
                `bearishReason` = CASE WHEN `direction` = 'BEARISH' THEN `reason` ELSE '' END,
                `reason` = CASE WHEN `direction` IN ('BULLISH', 'BEARISH') THEN '' ELSE `reason` END
            """.trimIndent(),
        )
    }
}
