package com.txl.mebot.data.archive

import com.txl.mebot.data.models.*
import java.net.URI
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/** 独立配置在主库加密保存；可复用共用凭据，但绝不改写日程/其他 Bot 设置。 */
class ArchiveEmbeddingSettingsRepository(private val storage: ArchiveStorage, private val index: ArchiveIndexRepository,
    private val shared: ModelSettingsRepository) {
    private data class Secret(val values: ArchiveEmbeddingSettings, val key: String?) { override fun toString() = "Secret([protected])" }
    suspend fun load(): ArchiveSemanticSettings = storage.read {
        val row = storage.dao.settings()
        val secret = row?.configurationPayload?.let(::decode)
        ArchiveSemanticSettings(secret?.values ?: ArchiveEmbeddingSettings(), row?.revision ?: 0, secret?.key != null)
    }
    suspend fun suggested(): ArchiveEmbeddingSettings {
        val language = URI(shared.load().settings.languageEndpoint)
        val isBailian = language.host?.endsWith(".aliyuncs.com") == true
        val prefix = language.path.substringBeforeLast("/chat/completions", language.path)
        val path = if (isBailian) prefix.substringBefore("/compatible-mode") + "/api/v1/services/embeddings/text-embedding/text-embedding" else prefix + "/embeddings"
        return ArchiveEmbeddingSettings(endpoint = URI("https", null, language.host, language.port, path, null, null).toString(),
            protocol = if (isBailian) ArchiveEmbeddingProtocol.DASHSCOPE else ArchiveEmbeddingProtocol.OPENAI_COMPATIBLE)
    }
    suspend fun save(values: ArchiveEmbeddingSettings, keyChange: ArchiveEmbeddingKeyChange, expectedRevision: Long): ArchiveSemanticSettings = storage.write {
        val config = values.validated(); val old = storage.dao.settings()
        if ((old?.revision ?: 0) != expectedRevision) throw ArchiveConflict()
        val previous = old?.configurationPayload?.let(::decode)
        val key = when (keyChange) {
            ArchiveEmbeddingKeyChange.Keep -> previous?.key
            ArchiveEmbeddingKeyChange.UseShared -> null
            is ArchiveEmbeddingKeyChange.Replace -> try { validateApiKey(keyChange.value) }
                catch (_: ModelConfigurationError) { throw ArchiveSemanticException(ArchiveSemanticFailure.CONFIGURATION) }
        }
        val payload = storage.encrypt(JSONObject().put("version", 1).put("enabled", config.enabled).put("endpoint", config.endpoint)
            .put("model", config.model).put("dimensions", config.dimensions).put("protocol", config.protocol.name).put("key", key ?: JSONObject.NULL), Purpose)
        val row = index.configure(if (config.enabled) config.configuration() else null, expectedRevision)
        storage.dao.updateSettings(row.copy(revision = expectedRevision + 1, configurationPayload = payload))
        ArchiveSemanticSettings(config, expectedRevision + 1, key != null)
    }
    internal suspend fun access(): ArchiveEmbeddingAccess {
        val pair = storage.read {
            val row = storage.dao.settings() ?: throw ArchiveSemanticException(ArchiveSemanticFailure.CONFIGURATION)
            val secret = row.configurationPayload?.let(::decode) ?: throw ArchiveSemanticException(ArchiveSemanticFailure.CONFIGURATION)
            if (!secret.values.enabled) throw ArchiveSemanticException(ArchiveSemanticFailure.CONFIGURATION)
            if (row.fingerprint != fingerprint(secret.values) || row.dimensions != secret.values.dimensions) throw ArchiveSemanticException(ArchiveSemanticFailure.CHANGED)
            row.revision to secret
        }
        val key = pair.second.key ?: try {
            val stored = shared.load(); shared.access(stored.revision).apiKey
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: MissingApiKey) { throw ArchiveSemanticException(ArchiveSemanticFailure.CREDENTIAL) }
        catch (_: CredentialUnavailable) { throw ArchiveSemanticException(ArchiveSemanticFailure.CREDENTIAL) }
        catch (_: ModelSettingsConflict) { throw ArchiveSemanticException(ArchiveSemanticFailure.CHANGED) }
        catch (_: ModelConfigurationError) { throw ArchiveSemanticException(ArchiveSemanticFailure.CONFIGURATION) }
        return ArchiveEmbeddingAccess(pair.second.values, pair.first, key)
    }
    private fun decode(bytes: ByteArray): Secret {
        val json = storage.decrypt(bytes, Purpose)
        check(json.getInt("version") == 1) { "不支持的索引配置格式，原数据已保留" }
        val values = ArchiveEmbeddingSettings(json.getBoolean("enabled"), json.getString("endpoint"), json.getString("model"), json.getInt("dimensions"),
            ArchiveEmbeddingProtocol.valueOf(json.getString("protocol"))).validated()
        return Secret(values, if (json.isNull("key")) null else json.getString("key"))
    }
    companion object {
        private const val Purpose = "archive-index-settings:1:v1"
        internal fun fingerprint(values: ArchiveEmbeddingSettings): String = values.configuration().let { ArchiveIndexRepository.fingerprint(it.endpoint, it.model, it.dimensions, it.encoding) }
    }
}
