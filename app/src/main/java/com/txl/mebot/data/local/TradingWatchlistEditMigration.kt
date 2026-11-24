package com.txl.mebot.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal object TradingWatchlistEditMigration : Migration(35, 36) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `trading_watchlist` RENAME TO `trading_watchlist_previous`")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `trading_watchlist` (`symbol` TEXT NOT NULL, `asset` TEXT NOT NULL, " +
                "`priceDecimals` INTEGER NOT NULL, `addedAt` INTEGER NOT NULL, `sortOrder` INTEGER, " +
                "`hidden` INTEGER NOT NULL DEFAULT 0, `iconAssetId` TEXT, PRIMARY KEY(`symbol`), " +
                "FOREIGN KEY(`iconAssetId`) REFERENCES `media_assets`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
        )
        db.execSQL(
            "INSERT INTO `trading_watchlist` (`symbol`, `asset`, `priceDecimals`, `addedAt`) " +
                "SELECT `symbol`, `asset`, `priceDecimals`, `addedAt` FROM `trading_watchlist_previous`",
        )
        db.execSQL("DROP TABLE `trading_watchlist_previous`")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_trading_watchlist_iconAssetId` ON `trading_watchlist` (`iconAssetId`)")
    }
}
