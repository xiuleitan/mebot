package com.txl.mebot.ui.archive

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.txl.mebot.MebotApplication
import com.txl.mebot.data.archive.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class ArchiveSearchSettingsState(
    val loading: Boolean = true,
    val busy: Boolean = false,
    val settings: ArchiveSemanticSettings? = null,
    val summary: ArchiveIndexSummary = ArchiveIndexSummary(),
    val open: Boolean = false,
    val saved: Boolean = false,
    val formGeneration: Long = 0,
    val failure: ArchiveSemanticFailure? = null,
)

/** 设置页独立管理搜索配置，不创建档案、加载聊天或改动未发送的输入。 */
class ArchiveSearchSettingsViewModel internal constructor(
    application: Application, private val engine: ArchiveSemanticEngine,
) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, (application as MebotApplication).container.archiveSemantic)

    private val mutable = MutableStateFlow(ArchiveSearchSettingsState())
    internal val state = mutable.asStateFlow()
    private var monitor: Job? = null

    fun start() {
        if (monitor?.isActive == true) return
        monitor = viewModelScope.launch {
            try {
                engine.initialize()
                refresh(reloadSettings = !mutable.value.open)
                engine.index.changes().collect {
                    refresh(reloadSettings = !mutable.value.open && !mutable.value.busy)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutable.update { it.copy(loading = false, failure = semanticFailure(error)) } }
        }
    }

    fun stop() { monitor?.cancel(); monitor = null }

    fun open() = operation {
        refresh(reloadSettings = true)
        mutable.update { it.copy(open = true, saved = false, formGeneration = it.formGeneration + 1) }
    }

    fun close() {
        if (!mutable.value.busy) mutable.update { it.copy(open = false, saved = false, failure = null) }
    }

    fun reload() = operation {
        refresh(reloadSettings = true)
        mutable.update { it.copy(formGeneration = it.formGeneration + 1) }
    }

    fun save(values: ArchiveEmbeddingSettings, key: ArchiveEmbeddingKeyChange) {
        val original = mutable.value.settings ?: return
        operation {
            val stored = engine.saveSettings(values, key, original.revision)
            mutable.update { it.copy(settings = stored, saved = true) }
            refresh(reloadSettings = false)
        }
    }

    fun retry(rebuild: Boolean = false) = operation {
        if (rebuild) engine.rebuild() else engine.retryIndex()
        refresh(reloadSettings = false)
    }

    private fun operation(block: suspend () -> Unit) {
        if (mutable.value.busy) return
        mutable.update { it.copy(busy = true, failure = null) }
        viewModelScope.launch {
            try { engine.initialize(); block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutable.update { it.copy(failure = semanticFailure(error)) } }
            finally { mutable.update { it.copy(busy = false, loading = false) } }
        }
    }

    private suspend fun refresh(reloadSettings: Boolean) {
        val generation = mutable.value.formGeneration
        val stored = if (reloadSettings) engine.settings.load().let {
            if (it.revision == 0L) it.copy(values = engine.settings.suggested()) else it
        } else null
        val summary = engine.index.summary()
        mutable.update { current ->
            // 后台统计的旧读取不能覆盖已经打开／重载的表单或更高版本的配置。
            val fresh = stored?.takeIf { current.formGeneration == generation && it.revision >= (current.settings?.revision ?: -1L) }
            current.copy(settings = fresh ?: current.settings, summary = summary, loading = false)
        }
    }
}
