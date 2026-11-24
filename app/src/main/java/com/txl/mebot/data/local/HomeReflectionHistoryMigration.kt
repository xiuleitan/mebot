package com.txl.mebot.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.security.MessageDigest

/** 已安装22冻结；只追加历史，并把仍保留的文字和图片加入历史，不改当前展示。 */
object HomeReflectionHistoryMigration : Migration(22, 23) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS `home_reflection_history` (`id` TEXT NOT NULL, `mode` TEXT NOT NULL, `text` TEXT NOT NULL, `imageAssetId` TEXT, `firstUsedAt` INTEGER NOT NULL, `lastUsedAt` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`imageAssetId`) REFERENCES `media_assets`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )""")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_home_reflection_history_imageAssetId` ON `home_reflection_history` (`imageAssetId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_home_reflection_history_lastUsedAt` ON `home_reflection_history` (`lastUsedAt`)")
        db.query("SELECT text,imageAssetId,updatedAt FROM home_reflection WHERE id=1").use { row ->
            if (row.moveToFirst()) {
                val text = row.getString(0)
                val time = row.getLong(2)
                if (text.isNotBlank()) {
                    val id = "text:" + MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
                        .joinToString("") { "%02x".format(it) }
                    db.execSQL("INSERT INTO home_reflection_history VALUES (?,?,?,?,?,?)", arrayOf<Any?>(id, "TEXT", text, null, time, time))
                }
                if (!row.isNull(1)) {
                    val image = row.getString(1)
                    db.execSQL("INSERT INTO home_reflection_history VALUES (?,?,?,?,?,?)",
                        arrayOf<Any?>("image:$image", "IMAGE", "", image, time, time))
                }
            }
        }
    }
}
