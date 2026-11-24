package com.txl.mebot.data.preferences

import android.annotation.SuppressLint
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal interface HapticPreferenceStore {
    suspend fun read(): Boolean
    suspend fun save(enabled: Boolean): Boolean
}

/** 仅为界面偏好；不保存任何 Bot 业务状态或记录。 */
internal class HapticPreferences(context: Context) : HapticPreferenceStore {
    private val app = context.applicationContext
    private val preferences by lazy { app.getSharedPreferences("interaction", Context.MODE_PRIVATE) }
    override suspend fun read(): Boolean = withContext(Dispatchers.IO) { preferences.getBoolean("haptics_enabled", true) }
    @SuppressLint("UseKtx") // 需要确认落盘成功后才更新开关。
    override suspend fun save(enabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        preferences.edit().putBoolean("haptics_enabled", enabled).commit()
    }
}
