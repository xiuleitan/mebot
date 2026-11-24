package com.txl.mebot.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.txl.mebot.R
import com.txl.mebot.data.local.MediaAssetEntity
import com.txl.mebot.ui.schedule.formatAttachmentSize
import com.txl.mebot.ui.schedule.localPhoto
import com.txl.mebot.ui.schedule.scheduleColors

/** 首页与日程直接复用附件缩略图，避免字号和尺寸逐渐分叉。 */
@Composable
internal fun ComposerMediaTile(asset: MediaAssetEntity, name: String, onClick: () -> Unit, modifier: Modifier) {
    val colors = scheduleColors()
    Surface(onClick = hapticAction(onClick), shape = RoundedCornerShape(13.dp), color = colors.past,
        border = BorderStroke(1.dp, colors.divider), modifier = modifier) {
        if (asset.mimeType.startsWith("image/") && asset.width > 0 && asset.height > 0) {
            val photo = localPhoto(asset.relativePath, maxDimension = 320)
            Box(contentAlignment = Alignment.Center) {
                if (photo != null) Image(photo, name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Icon(painterResource(R.drawable.ic_image), null, Modifier.size(26.dp), tint = colors.secondary)
            }
        } else Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.Center) {
            Icon(painterResource(R.drawable.ic_file), null, Modifier.size(23.dp), tint = colors.accent)
            Text(name, color = colors.ink, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(formatAttachmentSize(asset.byteCount), color = colors.secondary, fontSize = 9.sp)
        }
    }
}
