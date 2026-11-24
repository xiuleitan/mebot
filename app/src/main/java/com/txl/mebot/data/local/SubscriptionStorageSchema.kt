package com.txl.mebot.data.local

import androidx.sqlite.db.SupportSQLiteDatabase

/** schema 15 新表定义，由正式 14→15 Migration 在事务内调用；不改写旧表和历史 identity。 */
internal object SubscriptionStorageSchema {
    val tables = listOf("subscriptions", "subscription_conversations", "subscription_drafts", "subscription_messages",
        "subscription_operations", "subscription_changes", "subscription_payments", "subscription_conversation_media", "subscription_message_media")

    fun createTables(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE subscriptions (
            id TEXT NOT NULL, title TEXT NOT NULL, amountMinor INTEGER NOT NULL, currencyCode TEXT NOT NULL,
            cycleUnit TEXT NOT NULL, cycleInterval INTEGER NOT NULL, anchorDate INTEGER, anchorAtMonthEnd INTEGER NOT NULL,
            nextBillingDate INTEGER, validUntil INTEGER, autoRenew INTEGER NOT NULL, status TEXT NOT NULL,
            account TEXT, paymentMethod TEXT, notes TEXT NOT NULL, coverAssetId TEXT,
            createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, revision INTEGER NOT NULL, PRIMARY KEY(id),
            FOREIGN KEY(coverAssetId) REFERENCES media_assets(id) ON UPDATE NO ACTION ON DELETE NO ACTION)""")
        db.execSQL("CREATE INDEX index_subscriptions_coverAssetId ON subscriptions(coverAssetId)")
        db.execSQL("CREATE INDEX index_subscriptions_status_nextBillingDate_id ON subscriptions(status,nextBillingDate,id)")
        db.execSQL("""CREATE TABLE subscription_conversations (
            id TEXT NOT NULL, subscriptionId TEXT, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(id),
            FOREIGN KEY(subscriptionId) REFERENCES subscriptions(id) ON UPDATE NO ACTION ON DELETE NO ACTION)""")
        db.execSQL("CREATE UNIQUE INDEX index_subscription_conversations_subscriptionId ON subscription_conversations(subscriptionId)")
        db.execSQL("CREATE INDEX index_subscription_conversations_updatedAt ON subscription_conversations(updatedAt)")
        db.execSQL("""CREATE TABLE subscription_drafts (
            conversationId TEXT NOT NULL, inputText TEXT NOT NULL, fieldsJson TEXT NOT NULL, formatVersion INTEGER NOT NULL,
            revision INTEGER NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(conversationId),
            FOREIGN KEY(conversationId) REFERENCES subscription_conversations(id) ON UPDATE NO ACTION ON DELETE NO ACTION)""")
        db.execSQL("""CREATE TABLE subscription_messages (
            id TEXT NOT NULL, conversationId TEXT NOT NULL, sequence INTEGER NOT NULL, role TEXT NOT NULL,
            content TEXT NOT NULL, createdAt INTEGER NOT NULL, PRIMARY KEY(id),
            FOREIGN KEY(conversationId) REFERENCES subscription_conversations(id) ON UPDATE NO ACTION ON DELETE NO ACTION)""")
        db.execSQL("CREATE UNIQUE INDEX index_subscription_messages_conversationId_sequence ON subscription_messages(conversationId,sequence)")
        db.execSQL("CREATE UNIQUE INDEX index_subscription_messages_id_conversationId ON subscription_messages(id,conversationId)")
        db.execSQL("""CREATE TABLE subscription_operations (
            id TEXT NOT NULL, conversationId TEXT, subscriptionId TEXT, sourceMessageId TEXT,
            kind TEXT NOT NULL, state TEXT NOT NULL, payloadJson TEXT NOT NULL, expectedRevision INTEGER,
            resultId TEXT, failure TEXT, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(id),
            FOREIGN KEY(conversationId) REFERENCES subscription_conversations(id) ON UPDATE NO ACTION ON DELETE NO ACTION,
            FOREIGN KEY(subscriptionId) REFERENCES subscriptions(id) ON UPDATE NO ACTION ON DELETE NO ACTION,
            FOREIGN KEY(sourceMessageId,conversationId) REFERENCES subscription_messages(id,conversationId) ON UPDATE NO ACTION ON DELETE NO ACTION)""")
        db.execSQL("CREATE INDEX index_subscription_operations_conversationId_state ON subscription_operations(conversationId,state)")
        db.execSQL("CREATE INDEX index_subscription_operations_subscriptionId ON subscription_operations(subscriptionId)")
        db.execSQL("CREATE INDEX index_subscription_operations_sourceMessageId_conversationId ON subscription_operations(sourceMessageId,conversationId)")
        db.execSQL("""CREATE TABLE subscription_changes (
            id TEXT NOT NULL, subscriptionId TEXT NOT NULL, operationId TEXT NOT NULL, kind TEXT NOT NULL,
            previousRevision INTEGER, revision INTEGER NOT NULL, beforeJson TEXT, afterJson TEXT NOT NULL,
            beforeCoverAssetId TEXT, afterCoverAssetId TEXT, createdAt INTEGER NOT NULL, PRIMARY KEY(id),
            FOREIGN KEY(subscriptionId) REFERENCES subscriptions(id) ON UPDATE NO ACTION ON DELETE NO ACTION,
            FOREIGN KEY(operationId) REFERENCES subscription_operations(id) ON UPDATE NO ACTION ON DELETE NO ACTION,
            FOREIGN KEY(beforeCoverAssetId) REFERENCES media_assets(id) ON UPDATE NO ACTION ON DELETE NO ACTION,
            FOREIGN KEY(afterCoverAssetId) REFERENCES media_assets(id) ON UPDATE NO ACTION ON DELETE NO ACTION)""")
        db.execSQL("CREATE UNIQUE INDEX index_subscription_changes_subscriptionId_revision ON subscription_changes(subscriptionId,revision)")
        db.execSQL("CREATE UNIQUE INDEX index_subscription_changes_operationId ON subscription_changes(operationId)")
        db.execSQL("CREATE INDEX index_subscription_changes_beforeCoverAssetId ON subscription_changes(beforeCoverAssetId)")
        db.execSQL("CREATE INDEX index_subscription_changes_afterCoverAssetId ON subscription_changes(afterCoverAssetId)")
        db.execSQL("""CREATE TABLE subscription_payments (
            id TEXT NOT NULL, subscriptionId TEXT NOT NULL, billingEventId TEXT NOT NULL,
            amountMinor INTEGER NOT NULL, currencyCode TEXT NOT NULL, paidOn INTEGER NOT NULL, notes TEXT NOT NULL,
            operationId TEXT NOT NULL, confirmedAt INTEGER NOT NULL, PRIMARY KEY(id),
            FOREIGN KEY(subscriptionId) REFERENCES subscriptions(id) ON UPDATE NO ACTION ON DELETE NO ACTION,
            FOREIGN KEY(operationId) REFERENCES subscription_operations(id) ON UPDATE NO ACTION ON DELETE NO ACTION)""")
        db.execSQL("CREATE UNIQUE INDEX index_subscription_payments_subscriptionId_billingEventId ON subscription_payments(subscriptionId,billingEventId)")
        db.execSQL("CREATE UNIQUE INDEX index_subscription_payments_operationId ON subscription_payments(operationId)")
        db.execSQL("CREATE INDEX index_subscription_payments_paidOn_currencyCode ON subscription_payments(paidOn,currencyCode)")
        db.execSQL("""CREATE TABLE subscription_conversation_media (
            conversationId TEXT NOT NULL, assetId TEXT NOT NULL, displayName TEXT NOT NULL, position INTEGER NOT NULL,
            pendingSend INTEGER NOT NULL, createdAt INTEGER NOT NULL, PRIMARY KEY(conversationId,assetId),
            FOREIGN KEY(conversationId) REFERENCES subscription_conversations(id) ON UPDATE NO ACTION ON DELETE NO ACTION,
            FOREIGN KEY(assetId) REFERENCES media_assets(id) ON UPDATE NO ACTION ON DELETE NO ACTION)""")
        db.execSQL("CREATE INDEX index_subscription_conversation_media_assetId ON subscription_conversation_media(assetId)")
        db.execSQL("CREATE INDEX index_subscription_conversation_media_conversationId_position ON subscription_conversation_media(conversationId,position)")
        db.execSQL("""CREATE TABLE subscription_message_media (
            messageId TEXT NOT NULL, conversationId TEXT NOT NULL, assetId TEXT NOT NULL, displayName TEXT NOT NULL,
            position INTEGER NOT NULL, PRIMARY KEY(messageId,assetId),
            FOREIGN KEY(messageId,conversationId) REFERENCES subscription_messages(id,conversationId) ON UPDATE NO ACTION ON DELETE NO ACTION,
            FOREIGN KEY(conversationId,assetId) REFERENCES subscription_conversation_media(conversationId,assetId) ON UPDATE NO ACTION ON DELETE NO ACTION)""")
        db.execSQL("CREATE INDEX index_subscription_message_media_messageId_conversationId ON subscription_message_media(messageId,conversationId)")
        db.execSQL("CREATE INDEX index_subscription_message_media_conversationId_assetId ON subscription_message_media(conversationId,assetId)")
    }
}
