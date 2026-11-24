package com.txl.mebot.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.txl.mebot.MebotApplication
import com.txl.mebot.R
import com.txl.mebot.data.models.ApiKeyChange
import com.txl.mebot.data.models.ConnectionResultKind
import com.txl.mebot.data.models.CredentialUnavailable
import com.txl.mebot.data.models.MissingApiKey
import com.txl.mebot.data.models.ModelConfigurationError
import com.txl.mebot.data.models.ModelConfigurationIssue
import com.txl.mebot.data.models.ModelConnectionChecker
import com.txl.mebot.data.models.ModelConnectionResult
import com.txl.mebot.data.models.ModelRole
import com.txl.mebot.data.models.ModelSettings
import com.txl.mebot.data.models.ModelSettingsConflict
import com.txl.mebot.data.models.ModelSettingsRepository
import com.txl.mebot.data.models.StoredModelSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ModelSettingsState(
    val settings: ModelSettings = ModelSettings(),
    val newApiKey: String = "",
    val hasApiKey: Boolean = false,
    val isLoaded: Boolean = false,
    val loading: Boolean = false,
    val saving: Boolean = false,
    val hasSavedSettings: Boolean = false,
    val dirty: Boolean = false,
    val checking: ModelRole? = null,
    val languageResult: ModelConnectionResult? = null,
    val imageResult: ModelConnectionResult? = null,
    val message: Int? = null,
    val isError: Boolean = false,
    val reloadRequired: Boolean = false,
) {
    val busy get() = loading || saving || checking != null
    override fun toString() = "ModelSettingsState(loaded=$isLoaded, dirty=$dirty, key=[redacted], checking=$checking)"
}

/** 密码只在内存输入态中存在，不使用 SavedStateHandle、Bundle 或日志保存明文。 */
class ModelSettingsViewModel(
    application: Application,
    private val repository: ModelSettingsRepository,
    private val checker: ModelConnectionChecker,
) : AndroidViewModel(application) {
    constructor(application: Application) : this(
        application, (application as MebotApplication).container.modelSettings,
        application.container.modelConnectionChecker,
    )

    private val mutableState = MutableStateFlow(ModelSettingsState())
    val state = mutableState.asStateFlow()
    private var stored: StoredModelSettings? = null
    private var checkJob: Job? = null
    private var checkGeneration = 0

    fun loadIfNeeded() {
        if (!mutableState.value.isLoaded) reload()
    }

    fun reload() {
        if (mutableState.value.busy) return
        mutableState.update { it.copy(loading = true, message = null) }
        viewModelScope.launch {
            try {
                val loaded = repository.load()
                stored = loaded
                mutableState.value = ModelSettingsState(
                    settings = loaded.settings, hasApiKey = loaded.hasApiKey, isLoaded = true,
                    hasSavedSettings = loaded.revision > 0,
                )
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(loading = false, message = R.string.models_load_failed, isError = true, reloadRequired = true) }
            }
        }
    }

    fun changeSettings(settings: ModelSettings) = change { it.copy(settings = settings) }
    fun changeApiKey(value: String) = change { it.copy(newApiKey = value) }

    private fun change(edit: (ModelSettingsState) -> ModelSettingsState) {
        if (!mutableState.value.isLoaded || mutableState.value.busy) return
        mutableState.update {
            val updated = edit(it)
            updated.copy(
                dirty = updated.settings != stored?.settings || updated.newApiKey.isNotEmpty(),
                message = null, isError = false, languageResult = null, imageResult = null,
            )
        }
    }

    fun save() {
        val current = mutableState.value
        if (!current.isLoaded || current.busy || current.reloadRequired) return
        mutableState.update { it.copy(saving = true, message = null) }
        viewModelScope.launch {
            try {
                val key = if (current.newApiKey.isEmpty()) ApiKeyChange.Keep else ApiKeyChange.Replace(current.newApiKey)
                val result = repository.save(current.settings, key, requireNotNull(stored).revision)
                stored = result
                mutableState.value = ModelSettingsState(
                    settings = result.settings, hasApiKey = result.hasApiKey, isLoaded = true,
                    hasSavedSettings = true, message = R.string.models_saved,
                )
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: ModelConfigurationError) {
                fail(error.issue.messageResource())
            } catch (_: ModelSettingsConflict) {
                fail(R.string.models_conflict, reload = true)
            } catch (_: Exception) { fail(R.string.models_save_failed) }
        }
    }

    fun removeApiKey() {
        val current = mutableState.value
        if (!current.isLoaded || current.busy || !current.hasApiKey) return
        mutableState.update { it.copy(saving = true, message = null) }
        viewModelScope.launch {
            try {
                val result = repository.removeApiKey(requireNotNull(stored).revision)
                stored = result
                mutableState.update { it.copy(
                    newApiKey = "", hasApiKey = false, saving = false,
                    dirty = it.settings != result.settings, message = R.string.models_key_removed, isError = false,
                    languageResult = null, imageResult = null,
                ) }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: ModelSettingsConflict) { fail(R.string.models_conflict, reload = true)
            } catch (_: Exception) { fail(R.string.models_save_failed) }
        }
    }

    fun check(role: ModelRole) {
        val current = mutableState.value
        if (!current.isLoaded || current.busy || current.dirty || current.reloadRequired) return
        if (!current.hasApiKey) {
            fail(R.string.models_missing_key)
            return
        }
        val generation = ++checkGeneration
        mutableState.update { it.withResult(role, null).copy(checking = role, message = null, isError = false) }
        checkJob = viewModelScope.launch {
            try {
                val access = repository.access(requireNotNull(stored).revision)
                val result = checker.check(access, role)
                if (generation == checkGeneration) mutableState.update { it.withResult(role, result) }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: MissingApiKey) {
                if (generation == checkGeneration) fail(R.string.models_missing_key)
            } catch (_: CredentialUnavailable) {
                if (generation == checkGeneration) fail(R.string.models_key_unavailable)
            } catch (_: ModelSettingsConflict) {
                if (generation == checkGeneration) fail(R.string.models_conflict, reload = true)
            } catch (_: Exception) {
                if (generation == checkGeneration) fail(R.string.models_check_failed)
            } finally {
                if (generation == checkGeneration) {
                    mutableState.update { it.copy(checking = null) }
                    checkJob = null
                }
            }
        }
    }

    fun stopWaiting() {
        val role = mutableState.value.checking ?: return
        ++checkGeneration
        checkJob?.cancel()
        checkJob = null
        mutableState.update { it.withResult(role, ModelConnectionResult(ConnectionResultKind.CANCELLED)).copy(checking = null) }
    }

    private fun fail(message: Int, reload: Boolean = false) {
        mutableState.update { it.copy(saving = false, message = message, isError = true, reloadRequired = reload || it.reloadRequired) }
    }
}

private fun ModelSettingsState.withResult(role: ModelRole, result: ModelConnectionResult?) = when (role) {
    ModelRole.LANGUAGE -> copy(languageResult = result)
    ModelRole.IMAGE -> copy(imageResult = result)
}

private fun ModelConfigurationIssue.messageResource() = when (this) {
    ModelConfigurationIssue.LANGUAGE_MODEL -> R.string.models_invalid_language_model
    ModelConfigurationIssue.IMAGE_MODEL -> R.string.models_invalid_image_model
    ModelConfigurationIssue.LANGUAGE_ENDPOINT -> R.string.models_invalid_language_endpoint
    ModelConfigurationIssue.IMAGE_ENDPOINT -> R.string.models_invalid_image_endpoint
    ModelConfigurationIssue.IMAGE_SIZE -> R.string.models_invalid_image_size
    ModelConfigurationIssue.API_KEY -> R.string.models_invalid_key
}
