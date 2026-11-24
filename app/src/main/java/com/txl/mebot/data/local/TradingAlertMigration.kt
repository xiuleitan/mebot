package com.txl.mebot.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal object TradingAlertMigration : Migration(36, 37) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `trading_alert_connection` (`id` INTEGER NOT NULL, `endpoint` TEXT NOT NULL, " +
                "`deviceId` TEXT NOT NULL, `tokenCiphertext` BLOB NOT NULL, `tokenIv` BLOB NOT NULL, PRIMARY KEY(`id`))",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `trading_price_alerts` (`requestId` TEXT NOT NULL, `deviceId` TEXT NOT NULL, " +
                "`instanceId` TEXT NOT NULL, `symbol` TEXT NOT NULL, `kind` TEXT NOT NULL, `threshold` TEXT NOT NULL, " +
                "`minutes` INTEGER, `unit` TEXT, `createdAt` INTEGER NOT NULL, `serverId` TEXT, " +
                "`direction` TEXT, `status` TEXT NOT NULL, PRIMARY KEY(`requestId`))",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_trading_price_alerts_deviceId_symbol_createdAt` " +
                "ON `trading_price_alerts` (`deviceId`, `symbol`, `createdAt`)",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `trading_price_alert_events` (`deviceId` TEXT NOT NULL, `instanceId` TEXT NOT NULL, " +
                "`eventId` INTEGER NOT NULL, `ruleId` TEXT NOT NULL, `kind` TEXT NOT NULL, `symbol` TEXT NOT NULL, " +
                "`markPrice` TEXT NOT NULL, `triggeredAt` INTEGER NOT NULL, PRIMARY KEY(`deviceId`, `instanceId`, `eventId`))",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_trading_price_alert_events_deviceId_symbol_triggeredAt` " +
                "ON `trading_price_alert_events` (`deviceId`, `symbol`, `triggeredAt`)",
        )
    }
}
