package com.txl.mebot.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal object TradingClosedPositionMigration : Migration(31, 32) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `trading_connection` ADD COLUMN `fillsSyncedFrom` INTEGER")
        db.execSQL("CREATE TABLE IF NOT EXISTS `trading_closed_positions` (`scope` TEXT NOT NULL, `positionSide` TEXT NOT NULL, `closingTradeId` INTEGER NOT NULL, `side` TEXT NOT NULL, `firstTradeId` INTEGER NOT NULL, `firstTradeAt` INTEGER NOT NULL, `openingKnown` INTEGER NOT NULL, `closedAt` INTEGER NOT NULL, `openingQuantity` TEXT, `closingQuantity` TEXT, `realizedPnl` TEXT NOT NULL, `trendAtOpen` TEXT, PRIMARY KEY(`scope`, `positionSide`, `closingTradeId`))")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_trading_closed_positions_scope_closedAt` ON `trading_closed_positions` (`scope`, `closedAt`)")
    }
}
