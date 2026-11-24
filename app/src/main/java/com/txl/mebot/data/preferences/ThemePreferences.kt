package com.txl.mebot.data.preferences

import android.annotation.SuppressLint
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ThemePreferences(context: Context) {
    private val appContext = context.applicationContext
    private val preferences by lazy { appContext.getSharedPreferences("appearance", Context.MODE_PRIVATE) }

    suspend fun readThemeId(): String? = withContext(Dispatchers.IO) {
        preferences.getString("theme_id", null)
    }

    @SuppressLint("UseKtx") // 需要 commit 的布尔结果来报告保存失败，KTX edit 不返回该结果。
    suspend fun saveThemeId(themeId: String): Boolean = withContext(Dispatchers.IO) {
        preferences.edit().putString("theme_id", themeId).commit()
    }
}
