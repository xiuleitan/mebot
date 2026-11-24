package com.txl.mebot.data.models

import java.net.URI

data class ModelSettings(
    val languageModel: String = "qwen3.8-flash",
    val languageEndpoint: String = "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
    val imageModel: String = "z-image-turbo",
    val imageEndpoint: String = "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation",
    val imageSize: Int = 768,
) {
    fun validated(): ModelSettings {
        val language = languageModel.trim()
        val image = imageModel.trim()
        if (!ModelId.matches(language)) throw ModelConfigurationError(ModelConfigurationIssue.LANGUAGE_MODEL)
        if (!ModelId.matches(image)) throw ModelConfigurationError(ModelConfigurationIssue.IMAGE_MODEL)
        if (imageSize != 768) throw ModelConfigurationError(ModelConfigurationIssue.IMAGE_SIZE)
        return copy(
            languageModel = language, imageModel = image,
            languageEndpoint = validateEndpoint(languageEndpoint, ModelConfigurationIssue.LANGUAGE_ENDPOINT, true),
            imageEndpoint = validateEndpoint(imageEndpoint, ModelConfigurationIssue.IMAGE_ENDPOINT, false),
        )
    }
}

private val ModelId = Regex("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}")

private fun validateEndpoint(value: String, issue: ModelConfigurationIssue, language: Boolean): String {
    val trimmed = value.trim().trimEnd('/')
    val uri = try { URI(trimmed) } catch (_: Exception) { throw ModelConfigurationError(issue) }
    if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.rawUserInfo != null || uri.rawQuery != null ||
        uri.rawFragment != null || uri.port == 0 || uri.port > 65535 || uri.rawPath.isNullOrBlank()
    ) throw ModelConfigurationError(issue)
    // 允许粘贴百炼控制台给出的语言 Base URL，保存为实际请求地址。
    return if (language && uri.path.endsWith("/v1")) "$trimmed/chat/completions" else trimmed
}

enum class ModelConfigurationIssue {
    LANGUAGE_MODEL, IMAGE_MODEL, LANGUAGE_ENDPOINT, IMAGE_ENDPOINT, IMAGE_SIZE, API_KEY,
}

class ModelConfigurationError(val issue: ModelConfigurationIssue) : IllegalArgumentException("模型配置无效：$issue")
class ModelSettingsConflict : IllegalStateException("配置已更新，请重新加载")
class MissingApiKey : IllegalStateException("尚未保存 API Key")
class CredentialUnavailable : IllegalStateException("无法读取已保存的凭据，请重新输入 API Key")

data class StoredModelSettings(val settings: ModelSettings, val revision: Long, val hasApiKey: Boolean)

sealed interface ApiKeyChange {
    data object Keep : ApiKeyChange
    class Replace(val value: String) : ApiKeyChange {
        override fun toString() = "Replace([redacted])"
    }
}

internal fun validateApiKey(value: String): String {
    val key = value.trim()
    if (key.isEmpty() || key.length > 4096 || key.any { it.code !in 33..126 } || key.startsWith("Bearer", ignoreCase = true)) {
        throw ModelConfigurationError(ModelConfigurationIssue.API_KEY)
    }
    return key
}

/** 不把凭据放进自动生成的 toString 或可保存的 UI 状态。 */
class ModelAccess internal constructor(val settings: ModelSettings, internal val apiKey: String) {
    override fun toString() = "ModelAccess([redacted])"
}
