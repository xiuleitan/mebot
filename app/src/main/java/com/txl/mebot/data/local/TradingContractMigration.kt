package com.txl.mebot.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal object TradingContractMigration : Migration(32, 33) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Every row in the legacy trading tables belongs to BTCUSDT.
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `trading_fill_sync` (`scope` TEXT NOT NULL, `symbol` TEXT NOT " +
                "NULL, `fromTime` INTEGER NOT NULL, `throughTime` INTEGER NOT NULL, `historyPending` INTEGER " +
                "NOT NULL, PRIMARY KEY(`scope`, `symbol`))",
        )
        db.execSQL(
            "INSERT INTO trading_fill_sync (scope, symbol, fromTime, throughTime, historyPending) " +
                "SELECT scope, 'BTCUSDT', fillsSyncedFrom, fillsSyncedThrough, 1 " +
                "FROM trading_connection WHERE id = 1 AND fillsSyncedFrom <= fillsSyncedThrough",
        )
        rebuild(
            db, "trading_connection",
            "CREATE TABLE IF NOT EXISTS `trading_connection` (`id` INTEGER NOT NULL, `scope` TEXT NOT " +
                "NULL, `ciphertext` BLOB, `iv` BLOB, PRIMARY KEY(`id`))",
            "id, scope, ciphertext, iv",
        )
        rebuild(
            db, "trading_fills",
            "CREATE TABLE IF NOT EXISTS `trading_fills` (`scope` TEXT NOT NULL, `tradeId` INTEGER NOT " +
                "NULL, `orderId` INTEGER NOT NULL, `time` INTEGER NOT NULL, `side` TEXT NOT NULL, " +
                "`positionSide` TEXT NOT NULL, `price` TEXT NOT NULL, `quantity` TEXT NOT NULL, `realizedPnl` " +
                "TEXT NOT NULL, `reason` TEXT, `reasonUpdatedAt` INTEGER, `symbol` TEXT NOT NULL, PRIMARY " +
                "KEY(`scope`, `symbol`, `tradeId`))",
            "scope, tradeId, orderId, time, side, positionSide, price, quantity, realizedPnl, reason, " +
                "reasonUpdatedAt, 'BTCUSDT'",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_trading_fills_scope_symbol_time` ON `trading_fills` " +
                "(`scope`, `symbol`, `time`)",
        )
        rebuild(
            db, "trading_closed_positions",
            "CREATE TABLE IF NOT EXISTS `trading_closed_positions` (`scope` TEXT NOT NULL, `positionSide` " +
                "TEXT NOT NULL, `closingTradeId` INTEGER NOT NULL, `side` TEXT NOT NULL, `firstTradeId` " +
                "INTEGER NOT NULL, `firstTradeAt` INTEGER NOT NULL, `openingKnown` INTEGER NOT NULL, " +
                "`closedAt` INTEGER NOT NULL, `openingQuantity` TEXT, `closingQuantity` TEXT, `realizedPnl` " +
                "TEXT NOT NULL, `trendAtOpen` TEXT, `symbol` TEXT NOT NULL, PRIMARY KEY(`scope`, `symbol`, " +
                "`positionSide`, `closingTradeId`))",
            "scope, positionSide, closingTradeId, side, firstTradeId, firstTradeAt, openingKnown, " +
                "closedAt, openingQuantity, closingQuantity, realizedPnl, trendAtOpen, 'BTCUSDT'",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_trading_closed_positions_scope_symbol_closedAt` ON " +
                "`trading_closed_positions` (`scope`, `symbol`, `closedAt`)",
        )
        rebuild(
            db, "trading_lines",
            "CREATE TABLE IF NOT EXISTS `trading_lines` (`id` TEXT NOT NULL, `kind` TEXT NOT NULL, `time` " +
                "INTEGER, `price` REAL NOT NULL, `endTime` INTEGER, `endPrice` REAL, `symbol` TEXT NOT NULL " +
                "DEFAULT 'BTCUSDT', PRIMARY KEY(`id`))",
            "id, kind, time, price, endTime, endPrice, 'BTCUSDT'",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_trading_lines_symbol` ON `trading_lines` (`symbol`)",
        )
        rebuild(
            db, "trading_candles",
            "CREATE TABLE IF NOT EXISTS `trading_candles` (`interval` TEXT NOT NULL, `time` INTEGER NOT " +
                "NULL, `open` REAL NOT NULL, `high` REAL NOT NULL, `low` REAL NOT NULL, `close` REAL NOT " +
                "NULL, `volume` REAL NOT NULL, `symbol` TEXT NOT NULL, PRIMARY KEY(`symbol`, `interval`, " +
                "`time`))",
            "interval, time, open, high, low, close, volume, 'BTCUSDT'",
        )
    }

    private fun rebuild(
        db: SupportSQLiteDatabase,
        table: String,
        createSql: String,
        oldValues: String,
    ) {
        val previous = "${table}_32"
        db.execSQL("ALTER TABLE `$table` RENAME TO `$previous`")
        db.execSQL(createSql)
        db.execSQL("INSERT INTO `$table` SELECT $oldValues FROM `$previous`")
        db.execSQL("DROP TABLE `$previous`")
    }
}
