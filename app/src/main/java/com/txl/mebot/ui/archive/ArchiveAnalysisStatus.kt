package com.txl.mebot.ui.archive

import com.txl.mebot.data.archive.ArchiveAnalysisFailure
import com.txl.mebot.data.archive.ArchiveSemanticFailure
import com.txl.mebot.data.archive.ArchiveSemanticException
import com.txl.mebot.data.archive.ArchiveConflict

internal fun semanticFailure(error: Exception): ArchiveSemanticFailure = when (error) {
    is ArchiveSemanticException -> error.failure
    is ArchiveConflict -> ArchiveSemanticFailure.CHANGED
    else -> ArchiveSemanticFailure.LOCAL_STORAGE
}
internal fun ArchiveSemanticFailure.semanticMessage() = when (this) {
    ArchiveSemanticFailure.CONFIGURATION -> "请在 App 设置 → 模型与 API → 档案语义搜索中启用并检查配置。"
    ArchiveSemanticFailure.CREDENTIAL -> "无法读取 API Key，请检查共用模型设置或重新填写独立密钥。"
    ArchiveSemanticFailure.AUTHORIZATION -> "向量接口鉴权失败，请检查 API Key、地域和模型权限。"
    ArchiveSemanticFailure.BILLING -> "向量服务账户暂不可用，请检查余额或额度。"
    ArchiveSemanticFailure.NETWORK -> "网络连接失败，可稍后重试搜索。"
    ArchiveSemanticFailure.TIMEOUT -> "向量请求超时，可稍后重试。"
    ArchiveSemanticFailure.RATE_LIMIT -> "向量请求较多，请稍后重试。"
    ArchiveSemanticFailure.SERVICE -> "向量服务暂不可用，请稍后重试。"
    ArchiveSemanticFailure.INVALID_RESULT -> "向量响应无效，请检查模型与维数后重试。"
    ArchiveSemanticFailure.INPUT_LIMIT -> "内容超出本次向量处理范围，请缩短查询，或调整过长的档案正文。"
    ArchiveSemanticFailure.LOCAL_STORAGE -> "本地索引暂未读写成功，原档案保留，可重试或重建索引。"
    ArchiveSemanticFailure.CHANGED -> "档案或索引配置已变化，请重新搜索。"
    ArchiveSemanticFailure.INTERRUPTED -> "本次操作已取消或中断，可重新尝试。"
}

internal fun ArchiveAnalysisFailure.userMessage() = when (this) {
    ArchiveAnalysisFailure.CONFIGURATION -> "请在模型设置中检查语言模型、API Key 和图像理解能力。原消息与图片已保留。"
    ArchiveAnalysisFailure.CREDENTIAL -> "无法读取模型凭据，请在设置中重新保存 API Key。"
    ArchiveAnalysisFailure.AUTHORIZATION -> "模型鉴权失败，请检查 API Key、地域和模型权限。"
    ArchiveAnalysisFailure.BILLING -> "模型账户暂不可用，请检查余额或额度。"
    ArchiveAnalysisFailure.NETWORK -> "网络连接失败，消息和附件已保存在本机，可重试或手动建档。"
    ArchiveAnalysisFailure.TIMEOUT -> "本次整理超时，消息与图片已保留。"
    ArchiveAnalysisFailure.RATE_LIMIT -> "模型请求较多，请稍后重试。"
    ArchiveAnalysisFailure.SERVICE -> "模型服务暂不可用，请稍后重试。"
    ArchiveAnalysisFailure.INVALID_RESULT -> "本次返回内容无法作为有效草稿，请重试或手动核对。"
    ArchiveAnalysisFailure.LOCAL_SAVE -> "整理结果暂未保存，请重试；原消息与图片已保留。"
    ArchiveAnalysisFailure.CHANGED -> "资料在整理期间发生变化，请发送最新输入重新整理；已保存档案变化时可在手动核对中重新载入。"
    ArchiveAnalysisFailure.INTERRUPTED -> "上次整理已中断，原消息与附件已恢复，可重新整理。"
    ArchiveAnalysisFailure.IMAGE -> "图片无法读取或过大，本次未继续请求模型。请换一张清晰图片，或手动填写。"
    ArchiveAnalysisFailure.IMAGE_LIMIT -> "每次最多整理 6 张图片；图片合计过大时请减少图片或裁剪后重新发送。原件已保留。"
    ArchiveAnalysisFailure.INPUT_LIMIT -> "本次文字或档案较长，请缩短本次资料，或使用手动编辑。原内容已保留。"
}
