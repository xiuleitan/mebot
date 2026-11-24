package com.txl.mebot.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** 已安装的 1→23 结构与迁移冻结；仅追加首页草稿及附件关联。 */
object HomeComposerMigration : Migration(23, 24) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS `home_composer_drafts` (`id` INTEGER NOT NULL, `inputText` TEXT NOT NULL, `revision` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS `home_composer_attachments` (`draftId` INTEGER NOT NULL, `assetId` TEXT NOT NULL, `displayName` TEXT NOT NULL, `position` INTEGER NOT NULL, `sourceUri` TEXT, PRIMARY KEY(`draftId`, `assetId`), FOREIGN KEY(`draftId`) REFERENCES `home_composer_drafts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`assetId`) REFERENCES `media_assets`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )""")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_home_composer_attachments_assetId` ON `home_composer_attachments` (`assetId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_home_composer_attachments_draftId_position` ON `home_composer_attachments` (`draftId`, `position`)")
    }
}
