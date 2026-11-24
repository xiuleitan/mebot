package com.txl.mebot.ui.navigation

import android.content.res.Configuration
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.txl.mebot.ui.common.hapticAction
import com.txl.mebot.R
import com.txl.mebot.ui.theme.AppTheme
import com.txl.mebot.ui.theme.LocalAppTheme
import com.txl.mebot.ui.theme.MebotTheme

@Composable
fun BotDrawer(
    modifier: Modifier = Modifier,
    destination: BotDestination = BotDestination.Home,
    onScheduleClick: () -> Unit = {},
    onFlashnoteClick: () -> Unit = {},
    onDayimaClick: () -> Unit = {},
    onSubscriptionsClick: () -> Unit = {},
    onArchiveClick: () -> Unit = {},
    onSettingsClick: () -> Unit = {},
    onTradingClick: () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    ModalDrawerSheet(
        modifier = modifier.fillMaxHeight().verticalScroll(rememberScrollState()),
        drawerShape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp),
        drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        drawerContentColor = MaterialTheme.colorScheme.onSurface,
        drawerTonalElevation = 0.dp,
        windowInsets = WindowInsets.safeDrawing,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 24.dp, top = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.app_name),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )
            IconButton(onClick = hapticAction(onSettingsClick), modifier = Modifier.size(48.dp)) {
                Icon(
                    painter = painterResource(R.drawable.ic_settings),
                    contentDescription = stringResource(R.string.open_settings),
                    modifier = Modifier.size(25.dp),
                )
            }
        }
        DrawerBotCard(
            icon = R.drawable.ic_calendar, title = R.string.schedule_title,
            isSelected = destination == BotDestination.Schedule, onClick = onScheduleClick,
        )
        DrawerBotCard(
            icon = R.drawable.ic_flashnote, title = R.string.flashnote_title,
            isSelected = destination == BotDestination.Flashnote, onClick = onFlashnoteClick,
        )
        DrawerBotCard(
            icon = R.drawable.ic_dayima, title = R.string.dayima_title,
            isSelected = destination == BotDestination.Dayima, onClick = onDayimaClick,
        )
        DrawerBotCard(
            icon = R.drawable.ic_subscriptions, title = R.string.subscriptions_title,
            isSelected = destination == BotDestination.Subscriptions, onClick = onSubscriptionsClick,
        )
        DrawerBotCard(
            icon = R.drawable.ic_archive, title = R.string.archive_title,
            isSelected = destination == BotDestination.Archive, onClick = onArchiveClick,
        )
        DrawerBotCard(
            icon = R.drawable.ic_trading, title = R.string.trading_title,
            isSelected = destination == BotDestination.Trading, onClick = onTradingClick,
        )
    }
}

@Composable
private fun DrawerBotCard(icon: Int, title: Int, isSelected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val theme = LocalAppTheme.current
    val baseCardColor = if (isSelected) colors.secondaryContainer else colors.surfaceContainer
    val secondary = if (theme == AppTheme.BasicDark) colors.onSurfaceVariant
        else lerp(colors.onSurfaceVariant, colors.onSurface, 0.35f)
    val targetCardColor = if (theme == AppTheme.BasicDark) {
        lerp(baseCardColor, colors.onSurface, 0.18f)
    } else {
        val background = colors.surfaceContainerLowest
        val selectedColor = readableDrawerCardColor(
            background, if (theme.isDark) colors.onSurface else colors.primary,
            colors.onSurface, secondary, if (theme.isDark) 0.14f else 0.16f,
        )
        if (isSelected) selectedColor else lerp(background, selectedColor, 0.6f)
    }
    val cardColor by animateColorAsState(targetCardColor, tween(200), label = "drawer_card_color")
    Surface(
        onClick = hapticAction(onClick),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth()
            .semantics { selected = isSelected },
        shape = RoundedCornerShape(20.dp), color = cardColor, contentColor = colors.onSurface,
        border = BorderStroke(if (isSelected) 1.5.dp else 1.dp,
            colors.primary.copy(alpha = if (isSelected) 0.3f else 0.12f)),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = CircleShape, color = colors.surfaceContainerLowest) {
                Icon(painterResource(icon), null, Modifier.padding(12.dp).size(24.dp), tint = colors.primary)
            }
            Spacer(Modifier.width(14.dp))
            Text(stringResource(title), Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

private fun readableDrawerCardColor(
    background: Color,
    tint: Color,
    content: Color,
    secondary: Color,
    fraction: Float,
): Color {
    var amount = fraction
    while (amount > 0f) {
        val candidate = lerp(background, tint, amount)
        if (drawerTextContrast(content, candidate) >= 4.5f && drawerTextContrast(secondary, candidate) >= 4.5f) {
            return candidate
        }
        amount = (amount - 0.01f).coerceAtLeast(0f)
    }
    return background
}

private fun drawerTextContrast(foreground: Color, background: Color): Float {
    val first = foreground.luminance()
    val second = background.luminance()
    return (maxOf(first, second) + 0.05f) / (minOf(first, second) + 0.05f)
}

@Preview(name = "侧边栏", showBackground = true)
@Preview(name = "深色侧边栏", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun BotDrawerPreview() {
    MebotTheme {
        BotDrawer()
    }
}
