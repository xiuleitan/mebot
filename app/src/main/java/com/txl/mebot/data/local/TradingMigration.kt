package com.txl.mebot.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal object TradingMigration : Migration(27, 28) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `trading_connection` (`id` INTEGER NOT NULL, `scope` TEXT NOT NULL, `ciphertext` BLOB, `iv` BLOB, `fillsSyncedThrough` INTEGER, PRIMARY KEY(`id`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `trading_fills` (`scope` TEXT NOT NULL, `tradeId` INTEGER NOT NULL, `orderId` INTEGER NOT NULL, `time` INTEGER NOT NULL, `side` TEXT NOT NULL, `positionSide` TEXT NOT NULL, `price` TEXT NOT NULL, `quantity` TEXT NOT NULL, `realizedPnl` TEXT NOT NULL, `reason` TEXT, `reasonUpdatedAt` INTEGER, PRIMARY KEY(`scope`, `tradeId`))")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_trading_fills_scope_time` ON `trading_fills` (`scope`, `time`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `trading_lines` (`id` TEXT NOT NULL, `kind` TEXT NOT NULL, `time` INTEGER, `price` REAL NOT NULL, `endTime` INTEGER, `endPrice` REAL, PRIMARY KEY(`id`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `trading_candles` (`interval` TEXT NOT NULL, `time` INTEGER NOT NULL, `open` REAL NOT NULL, `high` REAL NOT NULL, `low` REAL NOT NULL, `close` REAL NOT NULL, `volume` REAL NOT NULL, PRIMARY KEY(`interval`, `time`))")
    }
}
