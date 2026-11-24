package com.txl.mebot.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** 已协调的唯一 18→19；SQL 与首次 Room 19 导出一致，旧表及原件不变。 */
object DayimaMediaMigration : Migration(18, 19) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS `dayima_draft_media` (`sessionId` TEXT NOT NULL, `assetId` TEXT NOT NULL, `displayName` TEXT NOT NULL, `position` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`sessionId`, `assetId`), FOREIGN KEY(`sessionId`) REFERENCES `dayima_chat_sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`assetId`) REFERENCES `media_assets`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS `index_dayima_draft_media_assetId` ON `dayima_draft_media` (`assetId`)""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS `index_dayima_draft_media_sessionId_position` ON `dayima_draft_media` (`sessionId`, `position`)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS `dayima_message_media` (`messageId` TEXT NOT NULL, `assetId` TEXT NOT NULL, `displayName` TEXT NOT NULL, `position` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`messageId`, `assetId`), FOREIGN KEY(`messageId`) REFERENCES `dayima_chat_messages`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`assetId`) REFERENCES `media_assets`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS `index_dayima_message_media_assetId` ON `dayima_message_media` (`assetId`)""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS `index_dayima_message_media_messageId_position` ON `dayima_message_media` (`messageId`, `position`)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS `dayima_period_media` (`periodId` TEXT NOT NULL, `assetId` TEXT NOT NULL, `displayName` TEXT NOT NULL, `position` INTEGER NOT NULL, `sourceMessageId` TEXT, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`periodId`, `assetId`), FOREIGN KEY(`periodId`) REFERENCES `dayima_periods`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`assetId`) REFERENCES `media_assets`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION , FOREIGN KEY(`sourceMessageId`) REFERENCES `dayima_chat_messages`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS `index_dayima_period_media_assetId` ON `dayima_period_media` (`assetId`)""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS `index_dayima_period_media_sourceMessageId` ON `dayima_period_media` (`sourceMessageId`)""")
        db.execSQL("""CREATE INDEX IF NOT EXISTS `index_dayima_period_media_periodId_position` ON `dayima_period_media` (`periodId`, `position`)""")
    }
}
