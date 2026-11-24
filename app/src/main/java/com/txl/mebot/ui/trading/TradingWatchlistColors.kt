package com.txl.mebot.ui.trading

import android.content.SharedPreferences
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import androidx.core.content.edit
import com.txl.mebot.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal enum class WatchlistCategoryColor(@get:StringRes val label: Int, val background: Color) {
    BLUE(R.string.trading_watchlist_color_blue, Color(0xFF20374B)),
    TEAL(R.string.trading_watchlist_color_teal, Color(0xFF1C3D38)),
    GOLD(R.string.trading_watchlist_color_gold, Color(0xFF413523)),
    PURPLE(R.string.trading_watchlist_color_purple, Color(0xFF372E49)),
    ROSE(R.string.trading_watchlist_color_rose, Color(0xFF442D39)),
    OLIVE(R.string.trading_watchlist_color_olive, Color(0xFF2B3826)),
    INDIGO(R.string.trading_watchlist_color_indigo, Color(0xFF2B3251)),
    SLATE(R.string.trading_watchlist_color_slate, Color(0xFF31353E)),
}

internal fun availableWatchlistColor(
    used: Collection<WatchlistCategoryColor>,
    fallbackIndex: Int = used.size,
): WatchlistCategoryColor = WatchlistCategoryColor.entries.firstOrNull { it !in used }
    ?: WatchlistCategoryColor.entries[fallbackIndex % WatchlistCategoryColor.entries.size]

internal fun watchlistCategoryColors(
    names: List<String>,
    overrides: Map<String, WatchlistCategoryColor>,
): Map<String, WatchlistCategoryColor> = buildMap {
    val used = names.mapNotNull(overrides::get).toMutableSet()
    names.forEachIndexed { index, name ->
        val color = overrides[name] ?: availableWatchlistColor(used, index)
        put(name, color)
        used.add(color)
    }
}

private const val CategoryColorPreferencePrefix = "watchlist_category_color:"

// Category membership is stored in Room. These choices only control the drawer's appearance.
internal class TradingWatchlistColorPreferences(private val display: SharedPreferences) {
    private val saved = MutableStateFlow(buildMap {
        for ((key, value) in display.all) {
            if (!key.startsWith(CategoryColorPreferencePrefix)) continue
            val color = WatchlistCategoryColor.entries.firstOrNull { it.name == value } ?: continue
            put(key.removePrefix(CategoryColorPreferencePrefix), color)
        }
    })
    val overrides = saved.asStateFlow()

    fun setColor(category: String, color: WatchlistCategoryColor) {
        display.edit { putString(CategoryColorPreferencePrefix + category, color.name) }
        saved.update { it + (category to color) }
    }
}
