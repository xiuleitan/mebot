package com.txl.mebot.ui.subscriptions

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.txl.mebot.R
import com.txl.mebot.ui.schedule.scheduleColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun SubscriptionPicture(assetId: String?, model: SubscriptionsViewModel?, modifier: Modifier = Modifier,
    maxPixels: Int = 256, fit: Boolean = false) {
    val colors = scheduleColors()
    val photo = subscriptionPhoto(assetId, model, maxPixels)
    Surface(modifier, shape = RoundedCornerShape(12.dp), color = colors.past) {
        val image = photo.image
        if (image != null) Image(image, "订阅图片", Modifier.fillMaxSize(), contentScale = if (fit) ContentScale.Fit else ContentScale.Crop)
        else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_subscription_grid), "订阅", Modifier.size(28.dp), tint = colors.secondary)
        }
    }
}

internal data class SubscriptionPhoto(val image: ImageBitmap? = null, val loading: Boolean = true)

/** 缩略图和全屏共用本地解码；保留边长上限，避免在主线程读取原件。 */
@Composable
internal fun subscriptionPhoto(assetId: String?, model: SubscriptionsViewModel?, maxPixels: Int): SubscriptionPhoto = key(assetId, model, maxPixels) {
    val photo by produceState(SubscriptionPhoto()) {
        val image = try {
            withContext(Dispatchers.IO) {
                val file = assetId?.let { model?.imageFile(it) } ?: return@withContext null
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                var sample = 1
                while (bounds.outWidth / sample > maxPixels * 2 || bounds.outHeight / sample > maxPixels * 2) sample *= 2
                BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
            }
        } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
        value = SubscriptionPhoto(image, loading = false)
    }
    photo
}
