package com.txl.mebot.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** 20 已安装冻结；旧封面作为图库第一张，既有业务行和原图均不改动。 */
object SubscriptionImageMigration : Migration(20, 21) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS `subscription_images` (`subscriptionId` TEXT NOT NULL, `assetId` TEXT NOT NULL, `position` INTEGER NOT NULL, PRIMARY KEY(`subscriptionId`, `assetId`), FOREIGN KEY(`subscriptionId`) REFERENCES `subscriptions`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION , FOREIGN KEY(`assetId`) REFERENCES `media_assets`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS `index_subscription_images_assetId` ON `subscription_images` (`assetId`)""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS `index_subscription_images_subscriptionId_position` ON `subscription_images` (`subscriptionId`, `position`)""")
        db.execSQL("""INSERT INTO `subscription_images` (`subscriptionId`, `assetId`, `position`) SELECT `id`, `coverAssetId`, 0 FROM `subscriptions` WHERE `coverAssetId` IS NOT NULL""")
    }
}
