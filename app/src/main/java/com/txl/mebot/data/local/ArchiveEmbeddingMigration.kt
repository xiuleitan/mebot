package com.txl.mebot.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** 只追加档案加密配置列，保留全部记录、关系、旧索引与不可换绑触发器。 */
object ArchiveEmbeddingMigration : Migration(17, 18) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `archive_index_settings` ADD COLUMN `configurationPayload` BLOB")
    }
}
