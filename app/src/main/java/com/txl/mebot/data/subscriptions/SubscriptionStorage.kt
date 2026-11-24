package com.txl.mebot.data.subscriptions

import androidx.room.RoomDatabase
import com.txl.mebot.data.local.MediaDao
import com.txl.mebot.data.local.SubscriptionConversationDao
import com.txl.mebot.data.local.SubscriptionDao

/** 由同一个 MebotDatabase 提供这组 DAO；不创建第二个业务数据库。 */
class SubscriptionStorage(
    val database: RoomDatabase,
    val records: SubscriptionDao,
    val conversations: SubscriptionConversationDao,
    val media: MediaDao,
)
