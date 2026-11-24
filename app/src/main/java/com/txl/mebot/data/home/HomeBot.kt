package com.txl.mebot.data.home

/** 持久 ID、界面名称和决策选项共用一份定义；决策选项使用简洁的正向业务定义，避免在每项中混入其他类别。 */
enum class HomeBot(val id: String, val label: String, val criteria: String) {
    SCHEDULE("schedule", "日程", "Schedule: an ordinary action or reminder with an explicitly stated execution date, time, time window or recurrence. Time expressions include 今天, 明天, 后天, 明早, 今晚, 待会儿, 下周, 周末, 每天. Examples: 明天买菜, 今晚整理房间, 下周开会, 每天喝水."),
    FLASHNOTE("flashnote", "闪念", "Flashnote: an untimed idea, inspiration, wish or ordinary to-do. Its execution date/time is not specified. Examples: 想做一个工具, 记得整理房间, 有空研究一下这个想法."),
    DAYIMA("dayima", "大姨妈", "Menstrual tracking (大姨妈, 月经, 经期, 生理期): the user states, records, corrects or backfills a period starting, ending or cycle information."),
    SUBSCRIPTIONS("subscriptions", "订阅管理", "Subscription management: record purchased memberships, subscription services, prices, billing, auto-renewal and expiry dates."),
    ARCHIVE("archive", "档案", "Archive: save, organize or file documents, identity cards, bank card details, invoices and reference information for later retrieval."),
    ;
    companion object { fun fromId(id: String?) = entries.firstOrNull { it.id == id } }
}
