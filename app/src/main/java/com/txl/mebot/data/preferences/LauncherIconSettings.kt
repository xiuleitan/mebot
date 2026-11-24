package com.txl.mebot.data.preferences

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.txl.mebot.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class LauncherIcon(
    @param:StringRes val title: Int,
    @param:DrawableRes val drawable: Int,
    internal val alias: String,
) {
    Pink(R.string.launcher_icon_pink, R.mipmap.ic_launcher_pink, ".PinkLauncher"),
    Classic(R.string.launcher_icon_classic, R.mipmap.ic_launcher, ".ClassicLauncher"),
    LuckyCat(R.string.launcher_icon_lucky_cat, R.mipmap.ic_launcher_lucky_cat, ".LuckyCatLauncher"),
}

internal class LauncherIconSettings(context: Context) {
    private val packageManager = context.packageManager
    private val packageName = context.packageName

    suspend fun selected(): LauncherIcon = withContext(Dispatchers.IO) {
        iconLock.withLock {
            // 旧版 Android 的分步切换中断后，用默认图标恢复单一桌面入口。
            LauncherIcon.entries.filter(::isEnabled).singleOrNull() ?: LauncherIcon.Pink.also(::applyIcon)
        }
    }

    suspend fun select(icon: LauncherIcon) = withContext(Dispatchers.IO) {
        iconLock.withLock { applyIcon(icon) }
    }

    private fun component(icon: LauncherIcon) = ComponentName.createRelative(packageName, icon.alias)

    private fun isEnabled(icon: LauncherIcon): Boolean = when (packageManager.getComponentEnabledSetting(component(icon))) {
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
        PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> icon == LauncherIcon.Pink
        else -> false
    }

    private fun applyIcon(icon: LauncherIcon) {
        val selected = component(icon)
        val enable = PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        val disable = PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        val changes = listOf(selected to enable) +
            LauncherIcon.entries.filter { it != icon }.map { component(it) to disable }
        val flags = PackageManager.DONT_KILL_APP or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) PackageManager.SYNCHRONOUS else 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.setComponentEnabledSettings(changes.map { (name, state) ->
                PackageManager.ComponentEnabledSetting(name, state, flags)
            })
        } else {
            // 先启用新入口，再关闭其他入口。回滚时优先恢复此前的入口。
            val previous = changes.map { (name, _) -> name to packageManager.getComponentEnabledSetting(name) }
            try {
                for ((name, state) in changes) packageManager.setComponentEnabledSetting(name, state, flags)
            } catch (failure: RuntimeException) {
                for ((name, state) in previous.asReversed()) {
                    runCatching { packageManager.setComponentEnabledSetting(name, state, flags) }
                        .exceptionOrNull()?.let(failure::addSuppressed)
                }
                throw failure
            }
        }
    }

    private companion object {
        val iconLock = Mutex()
    }
}
