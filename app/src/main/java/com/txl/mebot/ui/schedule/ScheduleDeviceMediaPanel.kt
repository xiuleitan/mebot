package com.txl.mebot.ui.schedule

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.txl.mebot.ui.common.hapticAction
import com.txl.mebot.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext

private const val DeviceMediaColumns = 4
private const val DeviceMediaBatchSize = DeviceMediaColumns * 3

@Composable
internal fun ScheduleDeviceMediaPanel(
    model: ScheduleAttachmentsViewModel?, media: ScheduleAttachmentsState,
    actions: AttachmentPickerActions, enabled: Boolean, modifier: Modifier = Modifier,
) {
    val pending = media.items.filter { it.link.pendingSend }
    val selected = media.selectedSources.mapNotNull { (uri, id) ->
        pending.indexOfFirst { it.asset.id == id }.takeIf { it >= 0 }?.let { uri to it + 1 }
    }.toMap()
    DeviceMediaPanel(actions, enabled, selected, { model?.pickLocal(it) }, modifier)
}

@Composable
internal fun DeviceMediaPanel(
    actions: AttachmentPickerActions, enabled: Boolean, selected: Map<String, Int>, onPickLocal: (Uri) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val colors = scheduleColors()
    val selectionBadgeSize = with(LocalDensity.current) { maxOf(24.dp, 18.sp.toDp()) }
    val selectionBadgeColor = lerp(colors.accent, Color.White, 0.55f)
    val gridState = rememberLazyGridState()
    var refresh by remember { mutableIntStateOf(0) }
    var access by remember { mutableIntStateOf(galleryAccess(context)) }
    var limit by remember { mutableIntStateOf(DeviceMediaBatchSize) }
    var images by remember { mutableStateOf<List<DeviceImage>>(emptyList()) }
    var hasMore by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refresh++; limit = DeviceMediaBatchSize
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(context, lifecycle, refresh, limit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            fun checkAccess() {
                val latest = galleryAccess(context)
                if (access != latest) images = emptyList()
                access = latest
            }
            checkAccess()
            failed = false
            if (access == 0) { loading = false; return@repeatOnLifecycle }
            try {
                var firstRefresh = true
                deviceImageChanges(context).collectLatest {
                    if (firstRefresh) firstRefresh = false else delay(150)
                    checkAccess()
                    if (access == 0) { loading = false; return@collectLatest }
                    loading = images.isEmpty()
                    try {
                        val previousFirst = images.firstOrNull()?.uri
                        // 只多读取一条元数据来判断是否还有图片，不预加载其缩略图。
                        val updated = queryDeviceImages(context, limit + 1)
                        images = updated.take(limit)
                        hasMore = updated.size > limit
                        failed = false
                        loading = false
                        // 位于列表顶部时展示新照片；浏览旧照片时保留当前滚动位置。
                        gridState.followLatestDeviceImage(previousFirst, images.firstOrNull()?.uri)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { failed = true }
                    finally { loading = false }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failed = true }
            finally { loading = false }
        }
    }
    val requestAccess = { permissions.launch(galleryPermissions()) }
    Surface(modifier, shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp), color = colors.surface) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MediaAction(R.drawable.ic_camera, R.string.schedule_camera, enabled, actions.camera, Modifier.weight(1f))
                MediaAction(R.drawable.ic_image, R.string.schedule_album, enabled, actions.images, Modifier.weight(1f))
                MediaAction(R.drawable.ic_file, R.string.schedule_pick_files, enabled, actions.files, Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            if (access == 0 || failed && images.isEmpty()) Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(if (failed) R.string.schedule_media_read_failed else R.string.schedule_media_permission_hint),
                    color = colors.secondary, fontSize = 12.sp)
                FilledTonalButton(onClick = { if (access == 0) requestAccess() else refresh++ }) {
                    Text(stringResource(if (access == 0) R.string.schedule_media_permission else R.string.schedule_media_retry), fontSize = 12.sp)
                }
            } else if (loading) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(24.dp), color = colors.accent, strokeWidth = 2.dp)
            } else if (images.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.schedule_media_empty), color = colors.secondary, fontSize = 12.sp)
            } else LazyVerticalGrid(state = gridState, columns = GridCells.Fixed(DeviceMediaColumns), modifier = Modifier.weight(1f).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(images, key = { _, image -> image.uri.toString() }) { index, image ->
                    val selectedIndex = selected[image.uri.toString()]
                    Surface(onClick = { onPickLocal(image.uri) }, enabled = enabled,
                        shape = RoundedCornerShape(12.dp), color = colors.past, modifier = Modifier.aspectRatio(1f)) {
                        Box {
                            val thumbnail = deviceThumbnail(context, image)
                            if (thumbnail != null) Image(thumbnail, stringResource(R.string.schedule_selected_image, index + 1),
                                Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            else Icon(painterResource(R.drawable.ic_image), null, Modifier.align(Alignment.Center).size(26.dp), tint = colors.secondary)
                            if (selectedIndex != null) Surface(
                                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(selectionBadgeSize),
                                shape = CircleShape, color = selectionBadgeColor,
                                border = BorderStroke(1.dp, Color.White), shadowElevation = 2.dp,
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(selectedIndex.toString(), color = Color.Black,
                                        fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                                }
                            }
                        }
                    }
                }
                if (hasMore) item(span = { GridItemSpan(maxLineSpan) }) {
                    TextButton(onClick = { limit += DeviceMediaBatchSize }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.schedule_media_more)) }
                }
            }
        }
    }
}

/** 请求下一次布局定位，不能等待尚未显示的网格完成首次布局。 */
internal fun LazyGridState.followLatestDeviceImage(previousFirst: Uri?, latestFirst: Uri?) {
    if (latestFirst != null && latestFirst != previousFirst &&
        firstVisibleItemIndex == 0 && firstVisibleItemScrollOffset == 0) requestScrollToItem(0)
}

@Composable
private fun MediaAction(icon: Int, label: Int, enabled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val colors = scheduleColors()
    Surface(onClick = hapticAction(onClick), enabled = enabled, shape = RoundedCornerShape(16.dp),
        color = if (enabled) lerp(colors.surface, colors.accent, 0.12f) else colors.past,
        border = BorderStroke(1.dp, colors.divider), shadowElevation = 3.dp, modifier = modifier) {
        Column(Modifier.padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(painterResource(icon), null, Modifier.size(25.dp), tint = if (enabled) colors.accent else colors.pastInk)
            Text(stringResource(label), color = if (enabled) colors.ink else colors.pastInk, fontSize = 12.sp, lineHeight = 16.sp)
        }
    }
}

internal fun galleryPermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

/** 2=全部、1=用户选择的部分、0=尚未授权；每次返回前台重新检查。 */
internal fun galleryAccess(context: Context): Int {
    fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    if (granted(if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE)) return 2
    return if (Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)) 1 else 0
}

@Composable
private fun deviceThumbnail(context: Context, image: DeviceImage): ImageBitmap? {
    val thumbnail by produceState<ImageBitmap?>(null, image.uri, image.added, image.modified) {
        value = withContext(Dispatchers.IO) {
            try {
                if (Build.VERSION.SDK_INT >= 29) context.contentResolver.loadThumbnail(image.uri, Size(192, 192), null).asImageBitmap()
                else {
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    context.contentResolver.openInputStream(image.uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                    options.inSampleSize = 1
                    while (options.outWidth / options.inSampleSize > 256 || options.outHeight / options.inSampleSize > 256) options.inSampleSize *= 2
                    options.inJustDecodeBounds = false
                    context.contentResolver.openInputStream(image.uri)?.use { BitmapFactory.decodeStream(it, null, options)?.asImageBitmap() }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        }
    }
    return thumbnail
}
