package com.txl.mebot.ui.settings

import android.content.res.Configuration
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.txl.mebot.ui.common.hapticAction
import com.txl.mebot.R
import com.txl.mebot.ui.theme.LocalAppTheme
import com.txl.mebot.ui.theme.MebotTheme

private data class SettingsItem(
    val title: Int,
    val description: String?,
    val icon: Int,
    val accent: Color,
    val onClick: () -> Unit,
)

private val MonochromeIconFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onAppearanceClick: () -> Unit = {},
    onModelsClick: () -> Unit = {},
    onNotificationsClick: () -> Unit = {},
    hapticModel: HapticSettingsViewModel? = null,
    onTradingClick: () -> Unit = {},
) {
    val colors = settingsColors()
    val title = stringResource(R.string.settings_title)
    val currentTheme = LocalAppTheme.current
    val hapticState = hapticModel?.state?.collectAsStateWithLifecycle()?.value ?: HapticSettingsState()
    val cards = listOf(
        SettingsItem(
            R.string.settings_notifications_title, stringResource(R.string.settings_notifications_description),
            R.drawable.ic_settings_notifications, Color(0xFFFF6471), onNotificationsClick,
        ),
        SettingsItem(
            R.string.settings_appearance_title,
            stringResource(R.string.settings_current_theme, stringResource(currentTheme.title)),
            R.drawable.ic_settings_appearance, Color(0xFF9258FF), onAppearanceClick,
        ),
        SettingsItem(
            R.string.settings_models_title, stringResource(R.string.settings_models_description),
            R.drawable.ic_settings_models, Color(0xFF328B83), onModelsClick,
        ),
        SettingsItem(
            R.string.trading_title, null, R.drawable.ic_trading, Color(0xFF3D8DC9), onTradingClick,
        ),
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(colors.backgroundTop, colors.backgroundBottom)))
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .semantics { paneTitle = title },
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = 720.dp)
                .fillMaxSize(),
        ) {
            Row(modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 6.dp, end = 12.dp)) {
                IconButton(
                    onClick = hapticAction(onBack),
                    modifier = Modifier.size(48.dp),
                    shape = CircleShape,
                    colors = IconButtonDefaults.iconButtonColors(contentColor = colors.ink),
                ) {
                    Icon(
                        painterResource(R.drawable.ic_arrow_back),
                        contentDescription = stringResource(R.string.navigate_back),
                        modifier = Modifier.size(24.dp),
                    )
                }
            }

            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = 24.dp, top = 8.dp, end = 24.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item(key = "heading") {
                    Text(
                        text = title,
                        modifier = Modifier
                            .padding(start = 4.dp, top = 2.dp, bottom = 12.dp)
                            .semantics { heading() },
                        color = colors.ink,
                        fontSize = 34.sp,
                        lineHeight = 44.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                item(key = "haptics") {
                    HapticSettingsRow(hapticState, { hapticModel?.select(it) }, { hapticModel?.load() })
                }
                items(cards, key = { it.title }) { item ->
                    SettingsCard(item, colors)
                }
            }
        }
    }
}

@Composable
internal fun HapticSettingsRow(state: HapticSettingsState, change: (Boolean) -> Unit, retry: () -> Unit) {
    val colors = settingsColors()
    Surface(shape = RoundedCornerShape(18.dp), color = colors.surface, contentColor = colors.ink) {
        Column {
            Row(Modifier.fillMaxWidth().testTag("haptic_feedback_setting")
                .toggleable(state.enabled, enabled = state.loaded && !state.saving, role = Role.Switch, onValueChange = change)
                .padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("触觉反馈", fontSize = 16.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold)
                    Text("点击与切换时轻微震动，跟随系统设置", color = colors.secondary, fontSize = 12.sp, lineHeight = 18.sp)
                }
                Spacer(Modifier.width(12.dp))
                Switch(state.enabled, onCheckedChange = null, enabled = state.loaded && !state.saving, modifier = Modifier.clearAndSetSemantics { })
            }
            state.error?.let { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = androidx.compose.material3.MaterialTheme.colorScheme.error,
                fontSize = 12.sp, lineHeight = 18.sp) }
            if (!state.loaded && !state.saving) TextButton(retry, Modifier.padding(horizontal = 8.dp)) { Text("重试") }
        }
    }
}

@Composable
private fun SettingsCard(item: SettingsItem, colors: SettingsColors) {
    val shape = RoundedCornerShape(18.dp)
    val isMonochrome = LocalAppTheme.current.isMonochrome
    val iconAccent = if (isMonochrome) colors.ink else item.accent
    Surface(
        onClick = hapticAction(item.onClick),
        modifier = Modifier.fillMaxWidth().shadow(
            elevation = 12.dp,
            shape = shape,
            ambientColor = colors.shadow,
            spotColor = colors.shadow,
        ),
        shape = shape,
        color = colors.surface,
        contentColor = colors.ink,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(50.dp).background(
                    brush = Brush.linearGradient(
                        listOf(
                            iconAccent.copy(alpha = colors.iconBackgroundAlpha),
                            iconAccent.copy(alpha = colors.iconBackgroundAlpha * 0.6f),
                        ),
                    ),
                    shape = RoundedCornerShape(14.dp),
                ),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painterResource(item.icon), contentDescription = null, modifier = Modifier.size(30.dp),
                    colorFilter = if (isMonochrome) MonochromeIconFilter else null,
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(item.title),
                    color = colors.ink,
                    fontSize = 16.sp,
                    lineHeight = 23.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                item.description?.let { description ->
                    Text(
                        text = description,
                        color = colors.secondary,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Icon(
                painterResource(R.drawable.ic_chevron_right),
                contentDescription = null,
                tint = colors.chevron,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Preview(name = "设置", widthDp = 412, heightDp = 892, showBackground = true)
@Preview(name = "深色设置", widthDp = 412, heightDp = 892, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "大字体设置", widthDp = 360, heightDp = 800, fontScale = 1.5f)
@Composable
private fun SettingsScreenPreview() {
    MebotTheme { SettingsScreen(onBack = {}) }
}
