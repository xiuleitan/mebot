package com.txl.mebot.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.time.LocalDate
import java.time.LocalTime
import java.time.Instant
import java.time.ZoneId

class ScheduleConverters {
    @TypeConverter fun dateToLong(value: LocalDate?): Long? = value?.toEpochDay()
    @TypeConverter fun longToDate(value: Long?): LocalDate? = value?.let(LocalDate::ofEpochDay)
    @TypeConverter fun timeToInt(value: LocalTime?): Int? = value?.let {
        require(it.second == 0 && it.nano == 0)
        it.hour * 60 + it.minute
    }
    @TypeConverter fun intToTime(value: Int?): LocalTime? = value?.let {
        require(it in 0..1439)
        LocalTime.of(it / 60, it % 60)
    }
}

/** 各 Bot 共用的应用主库；后续以保留数据的 migration 扩展此库。 */
@Database(
    entities = [
        MediaAssetEntity::class, ScheduleSessionEntity::class, ScheduleMessageEntity::class,
        ScheduleTaskEntity::class, ScheduleTaskVersionEntity::class,
        ScheduleRecurrenceEntity::class, ScheduleReminderLeadEntity::class,
        ScheduleOccurrenceEntity::class, ScheduleReminderEntity::class,
        ScheduleAttachmentEntity::class, ScheduleOperationEntity::class,
        ScheduleConversationDraftEntity::class, SchedulePendingQuestionEntity::class,
        ModelSettingsEntity::class,
        ScheduleExclusionEntity::class, ScheduleOccurrenceLeadEntity::class, MediaCleanupEntity::class,
        ScheduleLocalAttachmentEntity::class,
        ScheduleCoverJobEntity::class,
        ScheduleNotificationSettingsEntity::class,
        FlashnoteEntity::class, FlashnoteDraftEntity::class,
        FlashnoteAttachmentEntity::class, FlashnoteDraftAttachmentEntity::class,
        FlashnoteCompletionEntity::class, FlashnoteCompletionArchiveEntity::class,
        DayimaProfileEntity::class, DayimaPeriodEntity::class, DayimaNoteEntity::class,
        DayimaSpanMarkerEntity::class, DayimaChatSessionEntity::class, DayimaChatMessageEntity::class,
        DayimaChatDraftEntity::class, DayimaChatOperationEntity::class, DayimaReminderSettingsEntity::class,
        DayimaWriteReceiptEntity::class, DayimaDraftMediaEntity::class, DayimaMessageMediaEntity::class, DayimaPeriodMediaEntity::class,
        SubscriptionEntity::class, SubscriptionConversationEntity::class, SubscriptionDraftEntity::class,
        SubscriptionMessageEntity::class, SubscriptionOperationEntity::class, SubscriptionChangeEntity::class,
        SubscriptionPaymentEntity::class, SubscriptionConversationMediaEntity::class, SubscriptionMessageMediaEntity::class, SubscriptionImageEntity::class,
        ArchiveRecordEntity::class, ArchiveSessionEntity::class, ArchiveDraftEntity::class, ArchiveMessageEntity::class,
        ArchiveOperationEntity::class, ArchiveAssetEntity::class, ArchiveSessionAssetEntity::class, ArchiveMessageAssetEntity::class,
        ArchiveRecordAssetEntity::class, ArchiveEmbeddingEntity::class, ArchiveIndexJobEntity::class,
        ArchiveIndexSettingsEntity::class, ArchiveFileCleanupEntity::class,
        SubscriptionReminderSettingsEntity::class, SubscriptionReminderPlanEntity::class, SubscriptionReminderDeliveryEntity::class,
        HomeEventEntity::class, HomeFlashnoteDayEntity::class, HomeFlashnotePinEntity::class, DayimaReminderPlanEntity::class,
        HomeReflectionEntity::class,
        HomeReflectionHistoryEntity::class,
        HomeComposerDraftEntity::class, HomeComposerAttachmentEntity::class,
        HomeRoutingSettingsEntity::class, HomeDispatchEntity::class,
        ScheduleEditorDraftEntity::class, ScheduleEditorAttachmentEntity::class,
        TradingConnection::class, TradingFill::class, TradingLine::class, TradingCandle::class,
        TradingTrendEntity::class, TradingTrendHistoryEntity::class, TradingPositionPlanEntity::class,
        TradingClosedPosition::class, TradingFillSync::class, TradingWatchedContract::class, TradingNote::class,
        TradingAlertConnection::class, TradingPriceAlert::class, TradingPriceAlertEvent::class,
        TradingPerformanceDayRecord::class,
        TradingNewsRecord::class,
    ],
    version = MebotDatabase.VERSION,
    exportSchema = true,
)
@TypeConverters(ScheduleConverters::class)
abstract class MebotDatabase : RoomDatabase() {
    abstract fun scheduleDao(): ScheduleDao
    abstract fun mediaDao(): MediaDao
    abstract fun conversationDao(): ScheduleConversationDao
    abstract fun modelSettingsDao(): ModelSettingsDao
    abstract fun scheduleMediaDao(): ScheduleMediaDao
    abstract fun scheduleCoverDao(): ScheduleCoverDao
    abstract fun reminderDao(): ScheduleReminderDao
    abstract fun flashnoteDao(): FlashnoteDao
    abstract fun flashnoteMediaDao(): FlashnoteMediaDao
    abstract fun flashnoteStatisticsDao(): FlashnoteStatisticsDao
    abstract fun dayimaDao(): DayimaDao
    abstract fun dayimaWriteDao(): DayimaWriteDao
    abstract fun dayimaMediaDao(): DayimaMediaDao
    abstract fun subscriptions(): SubscriptionDao
    abstract fun subscriptionConversations(): SubscriptionConversationDao
    abstract fun archives(): ArchiveDao
    abstract fun subscriptionReminders(): SubscriptionReminderDao

    abstract fun home(): HomeDao
    abstract fun dayimaReminders(): DayimaReminderDao
    abstract fun homeReflection(): HomeReflectionDao
    abstract fun homeReflectionHistory(): HomeReflectionHistoryDao
    abstract fun homeComposer(): HomeComposerDao
    abstract fun scheduleEditor(): ScheduleEditorDao
    abstract fun homeRouting(): HomeRoutingDao
    internal abstract fun trading(): TradingDao
    internal abstract fun tradingAlerts(): TradingAlertDao
    internal abstract fun tradingNews(): TradingNewsDao

    companion object {
        const val FILE_NAME = "mebot.db"
        const val VERSION = 41

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_schedule_sessions_updatedAt ON schedule_sessions (updatedAt)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS schedule_conversation_drafts (
                        sessionId TEXT NOT NULL, inputText TEXT NOT NULL, revision INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL, PRIMARY KEY(sessionId),
                        FOREIGN KEY(sessionId) REFERENCES schedule_sessions(id) ON UPDATE NO ACTION ON DELETE NO ACTION)
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO schedule_conversation_drafts (sessionId, inputText, revision, updatedAt)
                    SELECT id, '', 0, updatedAt FROM schedule_sessions
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS schedule_pending_questions (
                        id TEXT NOT NULL, sessionId TEXT NOT NULL, question TEXT NOT NULL, createdAt INTEGER NOT NULL,
                        answeredAt INTEGER, answerMessageId TEXT, PRIMARY KEY(id),
                        FOREIGN KEY(sessionId) REFERENCES schedule_sessions(id) ON UPDATE NO ACTION ON DELETE NO ACTION,
                        FOREIGN KEY(answerMessageId) REFERENCES schedule_messages(id) ON UPDATE NO ACTION ON DELETE NO ACTION)
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_schedule_pending_questions_sessionId_answeredAt ON schedule_pending_questions (sessionId, answeredAt)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_schedule_pending_questions_answerMessageId ON schedule_pending_questions (answerMessageId)")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS model_settings (
                        id INTEGER NOT NULL, languageModel TEXT NOT NULL, languageEndpoint TEXT NOT NULL,
                        imageModel TEXT NOT NULL, imageEndpoint TEXT NOT NULL, imageSize INTEGER NOT NULL,
                        keyCiphertext BLOB, keyIv BLOB, revision INTEGER NOT NULL, updatedAt INTEGER NOT NULL,
                        PRIMARY KEY(id))
                """.trimIndent())
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE schedule_messages ADD COLUMN occurrenceContextId TEXT")
                db.execSQL("ALTER TABLE schedule_occurrences ADD COLUMN isException INTEGER NOT NULL DEFAULT 0")
                db.execSQL("""CREATE TABLE IF NOT EXISTS schedule_exclusions (
                    taskId TEXT NOT NULL, occurrenceKey TEXT NOT NULL, PRIMARY KEY(taskId, occurrenceKey),
                    FOREIGN KEY(taskId) REFERENCES schedule_tasks(id) ON UPDATE NO ACTION ON DELETE NO ACTION)""")
                db.execSQL("""CREATE TABLE IF NOT EXISTS schedule_occurrence_leads (
                    occurrenceId TEXT NOT NULL, minutesBefore INTEGER NOT NULL, PRIMARY KEY(occurrenceId, minutesBefore),
                    FOREIGN KEY(occurrenceId) REFERENCES schedule_occurrences(id) ON UPDATE NO ACTION ON DELETE NO ACTION)""")
                db.execSQL("CREATE TABLE IF NOT EXISTS media_cleanup (relativePath TEXT NOT NULL, PRIMARY KEY(relativePath))")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE IF NOT EXISTS schedule_local_attachments (
                    sessionId TEXT NOT NULL, assetId TEXT NOT NULL, displayName TEXT NOT NULL,
                    position INTEGER NOT NULL, addedAt INTEGER NOT NULL, isCover INTEGER NOT NULL,
                    PRIMARY KEY(sessionId, assetId),
                    FOREIGN KEY(sessionId) REFERENCES schedule_sessions(id) ON UPDATE NO ACTION ON DELETE NO ACTION,
                    FOREIGN KEY(assetId) REFERENCES media_assets(id) ON UPDATE NO ACTION ON DELETE NO ACTION)""")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_schedule_local_attachments_assetId ON schedule_local_attachments(assetId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_schedule_local_attachments_sessionId_position ON schedule_local_attachments(sessionId, position)")
                db.execSQL("""INSERT INTO schedule_local_attachments (sessionId, assetId, displayName, position, addedAt, isCover)
                    SELECT a.sessionId, a.assetId, COALESCE(m.originalName, '附件'), MIN(a.addedAt), MIN(a.addedAt),
                      EXISTS(SELECT 1 FROM schedule_tasks t WHERE t.sessionId = a.sessionId AND t.coverAssetId = a.assetId)
                    FROM schedule_attachments a JOIN media_assets m ON m.id = a.assetId GROUP BY a.sessionId, a.assetId""")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE IF NOT EXISTS schedule_cover_jobs (
                    taskId TEXT NOT NULL, sessionId TEXT NOT NULL, attemptId TEXT NOT NULL,
                    prompt TEXT NOT NULL, state TEXT NOT NULL, remoteUrl TEXT, generatedAt INTEGER,
                    generator TEXT, failure TEXT, updatedAt INTEGER NOT NULL, PRIMARY KEY(taskId),
                    FOREIGN KEY(taskId) REFERENCES schedule_tasks(id) ON UPDATE NO ACTION ON DELETE CASCADE)""")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_schedule_cover_jobs_sessionId ON schedule_cover_jobs(sessionId)")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE schedule_local_attachments ADD COLUMN pendingSend INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE schedule_local_attachments ADD COLUMN draftOnly INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE schedule_attachments ADD COLUMN position INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE schedule_attachments ADD COLUMN displayName TEXT")
                db.execSQL("""UPDATE schedule_local_attachments SET pendingSend = 0, draftOnly = 0
                    WHERE sessionId IN (SELECT sessionId FROM schedule_tasks)
                    OR EXISTS (SELECT 1 FROM schedule_attachments a
                        WHERE a.sessionId = schedule_local_attachments.sessionId AND a.assetId = schedule_local_attachments.assetId)""")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE IF NOT EXISTS schedule_notification_settings (
                    id INTEGER NOT NULL, ringtonePath TEXT, ringtoneName TEXT,
                    byteCount INTEGER NOT NULL, durationMillis INTEGER NOT NULL,
                    updatedAt INTEGER NOT NULL, PRIMARY KEY(id))""")
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE IF NOT EXISTS flashnotes (
                    id TEXT NOT NULL, title TEXT NOT NULL, content TEXT NOT NULL, status TEXT NOT NULL,
                    createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, completedAt INTEGER,
                    revision INTEGER NOT NULL, PRIMARY KEY(id))""")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_flashnotes_status_createdAt_id ON flashnotes(status, createdAt, id)")
                db.execSQL("""CREATE TABLE IF NOT EXISTS flashnote_drafts (
                    `key` TEXT NOT NULL, title TEXT NOT NULL, content TEXT NOT NULL, editingNoteId TEXT,
                    baseRevision INTEGER, revision INTEGER NOT NULL, updatedAt INTEGER NOT NULL,
                    PRIMARY KEY(`key`), FOREIGN KEY(editingNoteId) REFERENCES flashnotes(id)
                    ON UPDATE NO ACTION ON DELETE CASCADE)""")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_flashnote_drafts_editingNoteId ON flashnote_drafts(editingNoteId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_flashnote_drafts_updatedAt ON flashnote_drafts(updatedAt)")
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE flashnote_drafts ADD COLUMN attachmentSignature TEXT NOT NULL DEFAULT ''")
                db.execSQL("""CREATE TABLE IF NOT EXISTS flashnote_attachments (
                    noteId TEXT NOT NULL, assetId TEXT NOT NULL, displayName TEXT NOT NULL, position INTEGER NOT NULL,
                    PRIMARY KEY(noteId, assetId), FOREIGN KEY(noteId) REFERENCES flashnotes(id) ON UPDATE NO ACTION ON DELETE CASCADE,
                    FOREIGN KEY(assetId) REFERENCES media_assets(id) ON UPDATE NO ACTION ON DELETE NO ACTION)""")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_flashnote_attachments_assetId ON flashnote_attachments(assetId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_flashnote_attachments_noteId_position ON flashnote_attachments(noteId, position)")
                db.execSQL("""CREATE TABLE IF NOT EXISTS flashnote_draft_attachments (
                    draftKey TEXT NOT NULL, assetId TEXT NOT NULL, displayName TEXT NOT NULL, position INTEGER NOT NULL,
                    PRIMARY KEY(draftKey, assetId), FOREIGN KEY(draftKey) REFERENCES flashnote_drafts(`key`) ON UPDATE NO ACTION ON DELETE CASCADE,
                    FOREIGN KEY(assetId) REFERENCES media_assets(id) ON UPDATE NO ACTION ON DELETE NO ACTION)""")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_flashnote_draft_attachments_assetId ON flashnote_draft_attachments(assetId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_flashnote_draft_attachments_draftKey_position ON flashnote_draft_attachments(draftKey, position)")
            }
        }

        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""CREATE TABLE IF NOT EXISTS flashnote_completions (
                    noteId TEXT NOT NULL, day INTEGER NOT NULL, PRIMARY KEY(noteId),
                    FOREIGN KEY(noteId) REFERENCES flashnotes(id) ON UPDATE NO ACTION ON DELETE CASCADE)""")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_flashnote_completions_day ON flashnote_completions(day)")
                db.execSQL("CREATE TABLE IF NOT EXISTS flashnote_completion_archive (day INTEGER NOT NULL, count INTEGER NOT NULL, PRIMARY KEY(day))")
                val zone = ZoneId.systemDefault()
                db.query("SELECT id, completedAt FROM flashnotes WHERE status = 'COMPLETED' AND completedAt IS NOT NULL").use { cursor ->
                    while (cursor.moveToNext()) {
                        val day = Instant.ofEpochMilli(cursor.getLong(1)).atZone(zone).toLocalDate().toEpochDay()
                        db.execSQL("INSERT INTO flashnote_completions(noteId, day) VALUES (?, ?)", arrayOf(cursor.getString(0), day))
                    }
                }
            }
        }

        val MIGRATION_11_12: Migration = DayimaStorageMigration
        val MIGRATION_12_13: Migration = DayimaWriteMigration
        val MIGRATION_13_14: Migration = DayimaSchemaRepairMigration
        val MIGRATION_14_15: Migration = SubscriptionStorageMigration
        val MIGRATION_15_16: Migration = ArchiveStorageMigration
        val MIGRATION_16_17: Migration = SubscriptionReminderStorageMigration
        val MIGRATION_17_18: Migration = ArchiveEmbeddingMigration
        val MIGRATION_18_19: Migration = DayimaMediaMigration
        val MIGRATION_19_20: Migration = HomeReminderMigration
        val MIGRATION_20_21: Migration = SubscriptionImageMigration
        val MIGRATION_21_22: Migration = HomeReflectionMigration
        val MIGRATION_22_23: Migration = HomeReflectionHistoryMigration
        val MIGRATION_23_24: Migration = HomeComposerMigration
        val MIGRATION_24_25: Migration = HomeRoutingMigration
        val MIGRATION_25_26: Migration = HomeFlashnotePinMigration

        val MIGRATION_26_27: Migration = ScheduleEditorMigration

        fun create(context: Context): MebotDatabase = Room.databaseBuilder(
            context.applicationContext, MebotDatabase::class.java, FILE_NAME,
        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23, MIGRATION_23_24, MIGRATION_24_25, MIGRATION_25_26, MIGRATION_26_27, TradingMigration, TradingTrendMigration, TradingTrendReasonsMigration, TradingPositionMigration, TradingClosedPositionMigration, TradingContractMigration, TradingWatchlistMigration, TradingNoteMigration, TradingWatchlistEditMigration, TradingAlertMigration, TradingWatchlistCategoryMigration, TradingPerformanceMigration, TradingNewsMigration, TradingNewsUnreadMigration)
            .addCallback(object : Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) = ArchiveStorageSchema.createTriggers(db)
            }).setJournalMode(JournalMode.WRITE_AHEAD_LOGGING).build()
    }
}
