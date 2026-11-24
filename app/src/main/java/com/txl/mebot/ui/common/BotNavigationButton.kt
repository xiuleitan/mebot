package com.txl.mebot.ui.common

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@Composable
internal fun BotNavigationButton(
    @DrawableRes icon: Int,
    @StringRes label: Int,
    containerColor: Color,
    contentColor: Color,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = hapticAction(onClick),
        modifier = Modifier.size(48.dp),
        shape = CircleShape,
        colors = IconButtonDefaults.iconButtonColors(containerColor = containerColor, contentColor = contentColor),
    ) {
        Icon(painterResource(icon), stringResource(label), Modifier.size(24.dp))
    }
}
