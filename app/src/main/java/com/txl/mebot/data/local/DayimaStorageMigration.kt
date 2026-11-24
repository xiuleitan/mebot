package com.txl.mebot.data.local

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** 仅增加大姨妈数据表，不改写已有业务表或本地文件。 */
internal object DayimaStorageMigration : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS dayima_profile (
            id INTEGER NOT NULL, birthDate INTEGER, updatedAt INTEGER NOT NULL,
            revision INTEGER NOT NULL, PRIMARY KEY(id))""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS dayima_periods (
            id TEXT NOT NULL, startDate INTEGER NOT NULL, createdAt INTEGER NOT NULL,
            updatedAt INTEGER NOT NULL, revision INTEGER NOT NULL, PRIMARY KEY(id))""")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_dayima_periods_startDate ON dayima_periods(startDate)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS dayima_notes (
            id TEXT NOT NULL, occurredDate INTEGER NOT NULL, content TEXT NOT NULL, periodId TEXT,
            createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, revision INTEGER NOT NULL, PRIMARY KEY(id),
            FOREIGN KEY(periodId) REFERENCES dayima_periods(id) ON UPDATE NO ACTION ON DELETE SET NULL)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_dayima_notes_periodId ON dayima_notes(periodId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_dayima_notes_occurredDate_id ON dayima_notes(occurredDate, id)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS dayima_span_markers (
            id TEXT NOT NULL, startPeriodId TEXT, endPeriodId TEXT, startDate INTEGER NOT NULL,
            endDate INTEGER NOT NULL, startPeriodRevision INTEGER NOT NULL, endPeriodRevision INTEGER NOT NULL,
            reason TEXT NOT NULL, detail TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL,
            revision INTEGER NOT NULL, PRIMARY KEY(id),
            FOREIGN KEY(startPeriodId) REFERENCES dayima_periods(id) ON UPDATE NO ACTION ON DELETE SET NULL,
            FOREIGN KEY(endPeriodId) REFERENCES dayima_periods(id) ON UPDATE NO ACTION ON DELETE SET NULL)""")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_dayima_span_markers_startPeriodId_endPeriodId ON dayima_span_markers(startPeriodId, endPeriodId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_dayima_span_markers_endPeriodId ON dayima_span_markers(endPeriodId)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS dayima_chat_sessions (
            id TEXT NOT NULL, title TEXT NOT NULL, status TEXT NOT NULL, createdAt INTEGER NOT NULL,
            updatedAt INTEGER NOT NULL, revision INTEGER NOT NULL, PRIMARY KEY(id))""")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_dayima_chat_sessions_status_updatedAt_id ON dayima_chat_sessions(status, updatedAt, id)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS dayima_chat_messages (
            id TEXT NOT NULL, sessionId TEXT NOT NULL, sequence INTEGER NOT NULL, role TEXT NOT NULL,
            content TEXT NOT NULL, createdAt INTEGER NOT NULL, PRIMARY KEY(id),
            FOREIGN KEY(sessionId) REFERENCES dayima_chat_sessions(id) ON UPDATE NO ACTION ON DELETE CASCADE)""")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_dayima_chat_messages_sessionId_sequence ON dayima_chat_messages(sessionId, sequence)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_dayima_chat_messages_sessionId_id ON dayima_chat_messages(sessionId, id)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS dayima_chat_drafts (
            sessionId TEXT NOT NULL, inputText TEXT NOT NULL, revision INTEGER NOT NULL,
            updatedAt INTEGER NOT NULL, PRIMARY KEY(sessionId),
            FOREIGN KEY(sessionId) REFERENCES dayima_chat_sessions(id) ON UPDATE NO ACTION ON DELETE CASCADE)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS dayima_chat_operations (
            requestMessageId TEXT NOT NULL, sessionId TEXT NOT NULL, state TEXT NOT NULL, action TEXT,
            question TEXT, resultJson TEXT, failure TEXT, attemptId TEXT, createdAt INTEGER NOT NULL,
            updatedAt INTEGER NOT NULL, revision INTEGER NOT NULL, PRIMARY KEY(requestMessageId),
            FOREIGN KEY(sessionId, requestMessageId) REFERENCES dayima_chat_messages(sessionId, id)
            ON UPDATE NO ACTION ON DELETE CASCADE)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_dayima_chat_operations_sessionId_requestMessageId ON dayima_chat_operations(sessionId, requestMessageId)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_dayima_chat_operations_sessionId_state_createdAt ON dayima_chat_operations(sessionId, state, createdAt)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS dayima_reminder_settings (
            id INTEGER NOT NULL, prepareEnabled INTEGER NOT NULL, missingRecordEnabled INTEGER NOT NULL,
            reminderTime INTEGER NOT NULL, prepareDaysBefore INTEGER NOT NULL, updatedAt INTEGER NOT NULL,
            revision INTEGER NOT NULL, PRIMARY KEY(id))""")
    }
}
