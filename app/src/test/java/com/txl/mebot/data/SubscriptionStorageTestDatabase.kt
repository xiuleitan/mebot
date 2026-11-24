package com.txl.mebot.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.TypeConverters
import com.txl.mebot.data.local.*
import com.txl.mebot.data.subscriptions.SubscriptionStorage

/** 仅在 JVM 测试内创建；测试版本 1 不占用、也不改写 mebot.db 的迁移版本。 */
@Database(entities = [
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
        HomeEventEntity::class, HomeFlashnoteDayEntity::class, DayimaReminderPlanEntity::class,
        HomeReflectionEntity::class,
        HomeReflectionHistoryEntity::class,
        HomeComposerDraftEntity::class, HomeComposerAttachmentEntity::class,
        HomeRoutingSettingsEntity::class, HomeDispatchEntity::class,
        HomeFlashnotePinEntity::class, ScheduleEditorDraftEntity::class, ScheduleEditorAttachmentEntity::class,
        TradingConnection::class, TradingFill::class, TradingLine::class, TradingCandle::class,
        TradingTrendEntity::class, TradingTrendHistoryEntity::class, TradingPositionPlanEntity::class,
        TradingClosedPosition::class, TradingFillSync::class, TradingWatchedContract::class, TradingNote::class,
        TradingAlertConnection::class, TradingPriceAlert::class, TradingPriceAlertEvent::class,
        TradingPerformanceDayRecord::class,
        TradingNewsRecord::class,
    ], version = 1, exportSchema = false)
@TypeConverters(ScheduleConverters::class)
abstract class SubscriptionStorageTestDatabase : MebotDatabase() {
    abstract override fun subscriptions(): SubscriptionDao
    abstract override fun subscriptionConversations(): SubscriptionConversationDao

    fun storage() = SubscriptionStorage(this, subscriptions(), subscriptionConversations(), mediaDao())

    companion object {
        const val TEST_FILE = "subscription-storage-fixture.db"
        fun create(context: Context) = Room.databaseBuilder(context, SubscriptionStorageTestDatabase::class.java, TEST_FILE).build()
    }
}
