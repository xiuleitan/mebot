package com.txl.mebot.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.txl.mebot.data.preferences.LauncherIcon
import com.txl.mebot.data.preferences.LauncherIconSettings
import com.txl.mebot.data.preferences.ThemePreferences
import com.txl.mebot.ui.theme.AppTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AppearanceState(
    val theme: AppTheme? = null,
    val isLoaded: Boolean = false,
    val saveFailed: Boolean = false,
    val launcherIcon: LauncherIcon? = null,
    val launcherIconBusy: Boolean = true,
    val launcherIconSaveFailed: Boolean = false,
)

class AppearanceViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = ThemePreferences(application)
    private val launcherIcons = LauncherIconSettings(application)
    private val mutableState = MutableStateFlow(AppearanceState())
    val state = mutableState.asStateFlow()

    // 串行写入，连续切换时只保留最新待保存值，避免较旧的主题覆盖最后一次选择。
    private val changes = Channel<AppTheme>(Channel.CONFLATED)

    init {
        viewModelScope.launch {
            val savedTheme = try {
                AppTheme.fromId(preferences.readThemeId())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            mutableState.update { it.copy(theme = savedTheme, isLoaded = true) }

            for (theme in changes) {
                val saved = try {
                    preferences.saveThemeId(theme.id)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    false
                }
                mutableState.update { current ->
                    if (current.theme == theme) current.copy(saveFailed = !saved) else current
                }
            }
        }
        viewModelScope.launch {
            val selected = try {
                launcherIcons.selected()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            mutableState.update {
                it.copy(launcherIcon = selected, launcherIconBusy = false, launcherIconSaveFailed = selected == null)
            }
        }
    }

    fun selectTheme(theme: AppTheme) {
        val current = mutableState.value
        if (!current.isLoaded || (current.theme == theme && !current.saveFailed)) return
        mutableState.value = current.copy(theme = theme, saveFailed = false)
        changes.trySend(theme)
    }

    fun selectLauncherIcon(icon: LauncherIcon) {
        val current = mutableState.value
        if (current.launcherIconBusy || (current.launcherIcon == icon && !current.launcherIconSaveFailed)) return
        mutableState.update { it.copy(launcherIconBusy = true, launcherIconSaveFailed = false) }
        viewModelScope.launch {
            val saved = try {
                launcherIcons.select(icon)
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            mutableState.update {
                it.copy(launcherIcon = if (saved) icon else it.launcherIcon,
                    launcherIconBusy = false, launcherIconSaveFailed = !saved)
            }
        }
    }
}
