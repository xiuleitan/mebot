package com.txl.mebot.ui.settings

import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.txl.mebot.R
import com.txl.mebot.data.preferences.LauncherIcon
import com.txl.mebot.ui.common.hapticAction
import com.txl.mebot.ui.theme.AppTheme
import com.txl.mebot.ui.theme.LocalAppTheme
import com.txl.mebot.ui.theme.MebotTheme

@Composable
fun AppearanceScreen(onBack: () -> Unit, onThemeSelected: (AppTheme) -> Unit, saveFailed: Boolean = false,
    model: AppearanceViewModel? = null) {
    val colors = settingsColors()
    val title = stringResource(R.string.settings_appearance_title)
    val selectedTheme = LocalAppTheme.current
    val iconState = model?.state?.collectAsStateWithLifecycle()?.value

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(colors.backgroundTop, colors.backgroundBottom)))
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .semantics { paneTitle = title },
    ) {
        Column(modifier = Modifier.align(Alignment.TopCenter).widthIn(max = 720.dp).fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 6.dp, end = 20.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = hapticAction(onBack),
                    modifier = Modifier.size(48.dp),
                    colors = IconButtonDefaults.iconButtonColors(contentColor = colors.ink),
                ) {
                    Icon(
                        painterResource(R.drawable.ic_arrow_back),
                        stringResource(R.string.navigate_back),
                        modifier = Modifier.size(24.dp),
                    )
                }
                Text(
                    title, modifier = Modifier.padding(start = 8.dp),
                    color = colors.ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                )
            }

            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = 20.dp, top = 12.dp, end = 20.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                item(key = "themes") {
                    ThemeOptions(
                        themes = AppTheme.entries,
                        selectedTheme = selectedTheme, onThemeSelected = onThemeSelected,
                    )
                }
                if (saveFailed) {
                    item(key = "save_error") {
                        Text(
                            stringResource(R.string.appearance_save_failed),
                            color = MaterialTheme.colorScheme.error, fontSize = 13.sp, lineHeight = 20.sp,
                        )
                    }
                }
                item(key = "launcher_icons") {
                    LauncherIconOptions(iconState) { model?.selectLauncherIcon(it) }
                }
            }
        }
    }
}

@Composable
private fun ThemeOptions(themes: List<AppTheme>, selectedTheme: AppTheme, onThemeSelected: (AppTheme) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        for (row in themes.chunked(2)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for (theme in row) {
                    ThemeOption(theme, theme == selectedTheme, { onThemeSelected(theme) }, Modifier.weight(1f))
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ThemeOption(theme: AppTheme, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val outline = MaterialTheme.colorScheme.outlineVariant
    val swatchShape = RoundedCornerShape(8.dp)
    AppearanceOption(theme.title, selected, onClick, modifier) {
        Column(modifier = Modifier.fillMaxWidth().height(80.dp)
            .clip(swatchShape).border(1.dp, outline, swatchShape)) {
            val proportions = if (theme.isDark) {
                listOf(0.34f, 0.22f, 0.17f, 0.15f, 0.12f)
            } else {
                listOf(0.41f, 0.26f, 0.18f, 0.15f)
            }
            theme.swatches.forEachIndexed { index, color ->
                Box(Modifier.fillMaxWidth().weight(proportions[index]).background(color))
            }
        }
    }
}

@Composable
private fun LauncherIconOptions(state: AppearanceState?, onSelect: (LauncherIcon) -> Unit) {
    val selected = if (state == null) LauncherIcon.Pink else state.launcherIcon
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.appearance_launcher_icon), modifier = Modifier.semantics { heading() },
            color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            for (row in LauncherIcon.entries.chunked(2)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (icon in row) {
                        AppearanceOption(icon.title, icon == selected, { onSelect(icon) }, Modifier.weight(1f),
                            enabled = state?.launcherIconBusy == false) {
                            val context = LocalContext.current
                            val size = 56.dp
                            val pixels = with(LocalDensity.current) { size.roundToPx() }
                            val preview = remember(context, icon, pixels) {
                                requireNotNull(context.getDrawable(icon.drawable)).toBitmap(pixels, pixels).asImageBitmap()
                            }
                            Image(preview, contentDescription = null, modifier = Modifier.size(size))
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        if (state?.launcherIconSaveFailed == true) Text(stringResource(R.string.launcher_icon_change_failed),
            color = MaterialTheme.colorScheme.error, fontSize = 13.sp, lineHeight = 20.sp)
    }
}

@Composable
private fun AppearanceOption(@StringRes title: Int, selected: Boolean, onClick: () -> Unit,
    modifier: Modifier, enabled: Boolean = true, preview: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    val border by animateColorAsState(
        if (selected) colors.primary else colors.outlineVariant,
        animationSpec = tween(200), label = "theme_selection",
    )
    val background by animateColorAsState(
        if (selected) colors.primaryContainer else colors.surface,
        animationSpec = tween(200), label = "theme_option_background",
    )
    Surface(
        modifier = modifier.clip(shape)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = hapticAction(onClick)),
        shape = shape,
        color = background,
        border = BorderStroke(if (selected) 2.dp else 1.dp, border),
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            preview()
            Text(
                stringResource(title), color = colors.onSurface,
                fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Preview(name = "基础亮色外观", widthDp = 412, heightDp = 892)
@Composable
private fun BasicLightAppearancePreview() {
    MebotTheme(theme = AppTheme.BasicLight) { AppearanceScreen(onBack = {}, onThemeSelected = {}) }
}

@Preview(name = "基础暗色外观", widthDp = 412, heightDp = 892)
@Composable
private fun BasicDarkAppearancePreview() {
    MebotTheme(theme = AppTheme.BasicDark) { AppearanceScreen(onBack = {}, onThemeSelected = {}) }
}

@Preview(name = "基础暗色大字体", widthDp = 320, heightDp = 800, fontScale = 2f)
@Composable
private fun BasicDarkLargeFontAppearancePreview() {
    MebotTheme(theme = AppTheme.BasicDark) { AppearanceScreen(onBack = {}, onThemeSelected = {}) }
}
