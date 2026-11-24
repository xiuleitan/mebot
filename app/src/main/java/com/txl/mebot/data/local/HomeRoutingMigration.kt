package com.txl.mebot.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object HomeRoutingMigration : Migration(24, 25) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `home_routing_settings` (`id` INTEGER NOT NULL, `endpoint` TEXT NOT NULL, `revision` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `home_dispatches` (`id` TEXT NOT NULL, `snapshotKey` TEXT NOT NULL, `botId` TEXT NOT NULL, `targetId` TEXT NOT NULL, `messageId` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `handedOff` INTEGER NOT NULL, `dismissed` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_home_dispatches_snapshotKey` ON `home_dispatches` (`snapshotKey`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_home_dispatches_botId_targetId` ON `home_dispatches` (`botId`, `targetId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_home_dispatches_handedOff_dismissed_createdAt` ON `home_dispatches` (`handedOff`, `dismissed`, `createdAt`)")
    }
}
