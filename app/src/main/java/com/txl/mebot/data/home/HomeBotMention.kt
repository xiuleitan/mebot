package com.txl.mebot.data.home

/** 首页输入开头的路由标记；正文中的 @ 不参与路由。 */
data class HomeBotMention(val bot: HomeBot?, val tokenEnd: Int, val bodyStart: Int, val body: String) {
    val hasMention: Boolean get() = tokenEnd > 0

    fun withBot(bot: HomeBot): String = "@${bot.label} $body"

    companion object {
        fun parse(text: String): HomeBotMention {
            if (!text.startsWith('@')) return HomeBotMention(null, 0, 0, text)
            val end = text.indexOfFirst { it.isWhitespace() }.let { if (it < 0) text.length else it }
            val start = if (end < text.length) end + 1 else end
            val bot = HomeBot.entries.firstOrNull { text.substring(1, end) == it.label }
            return HomeBotMention(bot, end, start, text.substring(start))
        }
    }
}
