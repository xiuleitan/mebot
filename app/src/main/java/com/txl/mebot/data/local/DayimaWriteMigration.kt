package com.txl.mebot.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal object DayimaWriteMigration : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        addSpanReviewState(db)
        db.execSQL("""CREATE TABLE IF NOT EXISTS dayima_write_receipts (
            version INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, requestId TEXT NOT NULL,
            fingerprint TEXT NOT NULL, kind TEXT NOT NULL, targetId TEXT NOT NULL,
            targetRevision INTEGER, outcome TEXT NOT NULL, relatedNoteId TEXT,
            relatedNoteRevision INTEGER, committedAt INTEGER NOT NULL)""")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_dayima_write_receipts_requestId ON dayima_write_receipts(requestId)")
    }

    internal fun addSpanReviewState(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE dayima_span_markers ADD COLUMN needsReview INTEGER NOT NULL DEFAULT 0")
        db.execSQL("""UPDATE dayima_span_markers SET needsReview = 1, revision = revision + 1
            WHERE NOT EXISTS (
                SELECT 1 FROM dayima_periods s JOIN dayima_periods e
                ON e.id = dayima_span_markers.endPeriodId
                WHERE s.id = dayima_span_markers.startPeriodId
                AND s.startDate = dayima_span_markers.startDate AND e.startDate = dayima_span_markers.endDate
                AND s.revision = dayima_span_markers.startPeriodRevision AND e.revision = dayima_span_markers.endPeriodRevision
                AND s.startDate < e.startDate
                AND NOT EXISTS (SELECT 1 FROM dayima_periods p WHERE p.startDate > s.startDate AND p.startDate < e.startDate)
            )""")
    }
}

/** 开发期间曾安装过两种 schema 13；交给 Room 在升级后校验全部表，再更新结构标识。 */
internal object DayimaSchemaRepairMigration : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        val hasReviewState = db.query("PRAGMA table_info(`dayima_span_markers`)").use { cursor ->
            val name = cursor.getColumnIndexOrThrow("name")
            var found = false
            while (cursor.moveToNext()) {
                if (cursor.getString(name) == "needsReview") found = true
            }
            found
        }
        if (!hasReviewState) DayimaWriteMigration.addSpanReviewState(db)
    }
}
