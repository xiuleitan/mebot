package com.txl.mebot.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.txl.mebot.data.preferences.HapticPreferences
import com.txl.mebot.data.preferences.HapticPreferenceStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HapticSettingsState(val enabled: Boolean = true, val loaded: Boolean = false,
    val saving: Boolean = false, val error: String? = null)

class HapticSettingsViewModel internal constructor(app: Application, private val preferences: HapticPreferenceStore) : AndroidViewModel(app) {
    constructor(app: Application) : this(app, HapticPreferences(app))
    private val mutable = MutableStateFlow(HapticSettingsState())
    val state = mutable.asStateFlow()
    init { load() }
    fun load() {
        if (mutable.value.saving) return
        mutable.update { it.copy(saving = true) }
        viewModelScope.launch {
            try { mutable.value = HapticSettingsState(enabled = preferences.read(), loaded = true) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.update { it.copy(error = "触觉反馈设置读取失败，请重试") } }
            finally { mutable.update { it.copy(saving = false) } }
        }
    }
    fun select(enabled: Boolean) {
        val before = mutable.value
        if (!before.loaded || before.saving || enabled == before.enabled) return
        mutable.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            val saved = try { preferences.save(enabled) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { false }
            mutable.update { it.copy(enabled = if (saved) enabled else before.enabled, saving = false,
                error = if (saved) null else "设置保存失败，请再次点击重试") }
        }
    }
}
