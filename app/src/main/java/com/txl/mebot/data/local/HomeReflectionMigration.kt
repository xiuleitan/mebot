package com.txl.mebot.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** 已安装21冻结；只追加寄语表，不改任何旧表或原始媒体。 */
object HomeReflectionMigration : Migration(21, 22) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS `home_reflection` (`id` INTEGER NOT NULL, `text` TEXT NOT NULL, `mode` TEXT NOT NULL, `imageAssetId` TEXT, `revision` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`imageAssetId`) REFERENCES `media_assets`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )""")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_home_reflection_imageAssetId` ON `home_reflection` (`imageAssetId`)")
    }
}
