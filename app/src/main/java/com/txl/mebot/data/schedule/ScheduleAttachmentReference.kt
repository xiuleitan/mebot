package com.txl.mebot.data.schedule

import com.txl.mebot.data.local.MessageRole

/** 用户已给出可记录的附件指代活动时，附件内容不是创建日程的必填项。 */
internal fun unnecessaryAttachmentContentQuestion(context: ScheduleAnalysisContext, question: String): Boolean {
    if (context.attachments.totalCount == 0 && context.messageAttachments.values.none { it.totalCount > 0 }) return false
    val latest = context.messages.lastOrNull { it.role == MessageRole.USER }?.content ?: return false
    val reference = Regex("图片|图像|照片|截图|附件|文件|文档|资料|这.{0,6}(?:东西|物品|事[情儿]?)")
    val action = Regex("做|处理|完成|办理|安排|记录|记下|收取|领取|取|收|拿|打印|带上|带走")
    if (!reference.containsMatchIn(latest) || !action.containsMatchIn(latest)) return false
    // 明确要求识别或提取附件，仍可以说明限制并要求必要文字；不会假装已经读取。
    val reading = "(?:识别|读取|提取|分析|总结|翻译|转写|辨认|读出|看懂|看出)"
    val positiveReading = latest.replace(Regex("(?:不要|不用|不需要|无需|别)\\s*$reading"), "")
    if (Regex(reading).containsMatchIn(positiveReading)) return false
    val contents = Regex(
        "(?:图片|图像|照片|截图|附件|文件|文档|资料|东西).{0,40}(?:内容|是什么|是啥|有什么|哪些|什么事|做什么|要做的事)|" +
            "(?:描述|解释|说明|告知|列出|告诉我).{0,24}(?:图片|图像|照片|截图|附件|文件|文档|资料)",
        RegexOption.DOT_MATCHES_ALL,
    )
    return contents.containsMatchIn(question)
}
