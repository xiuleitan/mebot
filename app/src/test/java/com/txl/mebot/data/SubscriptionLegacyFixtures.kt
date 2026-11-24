package com.txl.mebot.data

import com.txl.mebot.data.local.*
import com.txl.mebot.data.subscriptions.*

/** 仅构造旧版已归档数据，验证升级后仍能读取；生产代码不再提供归档入口。 */
internal suspend fun legacyArchived(records: SubscriptionRepository, requestId: String, id: String, revision: Long) =
    records.update(requestId, id, revision, SubscriptionValues.from(requireNotNull(records.get(id))).copy(
        status = SubscriptionStatus.ARCHIVED, autoRenew = false), kind = SubscriptionOperationKind.ARCHIVE)
