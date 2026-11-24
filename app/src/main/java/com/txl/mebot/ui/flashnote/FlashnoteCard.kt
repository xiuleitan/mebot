package com.txl.mebot.ui.flashnote

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.txl.mebot.ui.common.hapticAction
import com.txl.mebot.R
import com.txl.mebot.data.flashnote.FlashnoteTitleState
import com.txl.mebot.ui.schedule.localPhoto
import com.txl.mebot.ui.schedule.scheduleColors

@Composable
internal fun FlashnoteCard(entry: FlashnoteEntry, onClick: () -> Unit, modifier: Modifier = Modifier,
    titleState: FlashnoteTitleState? = null) {
    val colors = scheduleColors()
    val summary = remember(entry.title, entry.detail) { entry.summary }
    val statusLabel = stringResource(if (entry.completed) R.string.flashnote_completed else R.string.flashnote_pending)
    Surface(
        onClick = hapticAction(onClick), modifier = modifier.fillMaxWidth().semantics { stateDescription = statusLabel }, shape = RoundedCornerShape(18.dp),
        color = if (entry.completed) colors.completedBottom else colors.surface,
        contentColor = colors.ink, shadowElevation = 3.dp,
        border = BorderStroke(0.5.dp, colors.divider.copy(alpha = 0.25f)),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(entry.title, Modifier.weight(1f), fontSize = 16.sp, lineHeight = 20.sp,
                        fontWeight = FontWeight.SemiBold, color = colors.ink,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    FlashnoteTitleIndicator(titleState)
                }
                if (summary.isNotBlank()) Text(summary, fontSize = 13.sp, lineHeight = 18.sp,
                    color = colors.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(entry.creationLabel(), Modifier.weight(1f),
                        color = colors.secondary, fontSize = 11.sp, lineHeight = 16.sp)
                    if (entry.attachmentCount > 0) {
                        Icon(painterResource(R.drawable.ic_attach), null, Modifier.size(12.dp), tint = colors.secondary)
                        Text(entry.attachmentCount.toString(), color = colors.secondary, fontSize = 11.sp,
                            modifier = Modifier.padding(start = 3.dp))
                    }
                }
            }
            if (entry.image != null || !entry.coverRelativePath.isNullOrBlank()) {
                FlashnoteImage(entry.image, Modifier.size(52.dp).clip(RoundedCornerShape(10.dp))
                    .graphicsLayer { alpha = if (entry.completed) 0.65f else 1f }, relativePath = entry.coverRelativePath)
            }
        }
    }
}

/** 与日程复用本地图片解码、方向校正及有界内存缓存。 */
@Composable
internal fun FlashnoteImage(resource: Int?, modifier: Modifier, maxDimension: Int = 256, relativePath: String? = null) {
    val colors = scheduleColors()
    val photo = localPhoto(relativePath, resource, maxDimension)
    Box(modifier.background(colors.past), contentAlignment = Alignment.Center) {
        if (photo != null) Image(photo, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(painterResource(R.drawable.ic_flashnote), null, Modifier.size(28.dp), tint = colors.secondary)
    }
}
