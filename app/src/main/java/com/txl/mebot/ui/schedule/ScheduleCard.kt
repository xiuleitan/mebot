package com.txl.mebot.ui.schedule

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.txl.mebot.ui.common.hapticAction
import com.txl.mebot.R
import com.txl.mebot.ui.theme.LocalAppTheme
import kotlin.math.ceil

private val AlarmTint = Color(0xFFFF4D16)
private val CardShape = RoundedCornerShape(16.dp)
private val TitleLineHeight = 18.sp
private val MetadataLineHeight = 15.sp

internal data class ScheduleCardMetrics(val imageHeight: Dp, val titleHeight: Dp, val metadataHeight: Dp, val infoHeight: Dp) {
    val height get() = imageHeight + infoHeight
}

/** 首页其他 Bot 也按同一外框占位，历史三列使用紧凑尺寸。 */
@Composable
internal fun scheduleCardMetrics(width: Dp, compact: Boolean = false): ScheduleCardMetrics {
    val density = LocalDensity.current
    val titleHeight = with(density) { ceil((if (compact) 15.sp else TitleLineHeight).toPx()).toInt().toDp() }
    val metadataHeight = with(density) { ceil((if (compact) 13.sp else MetadataLineHeight).toPx()).toInt().toDp() }
    return ScheduleCardMetrics(maxOf(width * 9f / 16f, if (compact) 56.dp else 88.dp), titleHeight, metadataHeight,
        (if (compact) 12.dp else 16.dp) + titleHeight + (if (compact) 3.dp else 4.dp) + metadataHeight)
}

@Composable
internal fun ScheduleCard(
    entry: ScheduleEntry,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    cardModifier: Modifier = Modifier,
    coverBadge: (@Composable () -> Unit)? = null,
) {
    val colors = scheduleColors()
    val density = LocalDensity.current
    val isDark = LocalAppTheme.current.isDark
    val pendingTextColor = if (isDark) colors.ink else Color.Black
    val completedTextColor = if (isDark) Color(0xFFD0D0D0) else Color(0xFF666666)
    val completion by animateFloatAsState(if (entry.completed) 1f else 0f, tween(260), label = "schedule_completion")
    val bottomColor = lerp(colors.surface, colors.completedBottom, completion)
    val titleTextColor = lerp(pendingTextColor, completedTextColor, completion)
    val dateTextColor = lerp(pendingTextColor, completedTextColor, completion)
    val secondaryTextColor = lerp(colors.secondary, completedTextColor, completion)
    val stateLabel = stringResource(if (entry.completed) R.string.schedule_completed else R.string.schedule_pending)
    val photoFilter = remember(completion) {
        ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(1f - completion) })
    }
    val shape = if (compact) RoundedCornerShape(12.dp) else CardShape
    val titleLineHeight = if (compact) 15.sp else TitleLineHeight
    val metadataLineHeight = if (compact) 13.sp else MetadataLineHeight

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val dimension = scheduleThumbnailDimension(with(density) { maxWidth.roundToPx() })
        val photo = schedulePhoto(entry, dimension)
        val photoAlpha by animateFloatAsState(if (photo == null) 0f else 1f, tween(200), label = "schedule_photo")
        // 图像与实色信息区各自占位，高度不随具体标题或附件变化。
        val (imageHeight, titleHeight, metadataHeight, infoHeight) = scheduleCardMetrics(maxWidth, compact)
        Column(
            cardModifier.fillMaxWidth().dropShadow(shape) {
                radius = 5.dp.toPx()
                offset = Offset(0f, 2.dp.toPx())
                color = Color.Black.copy(alpha = if (isDark) 0.40f else 0.18f)
            }.clip(shape).background(bottomColor)
                .clickable(role = Role.Button, onClick = hapticAction(onClick)).semantics {
                    stateDescription = stateLabel
                },
        ) {
            Box(Modifier.fillMaxWidth().height(imageHeight).background(colors.past)) {
                if (photo != null) Image(
                    bitmap = photo, contentDescription = null, contentScale = ContentScale.Crop,
                    colorFilter = photoFilter, alpha = photoAlpha, modifier = Modifier.matchParentSize(),
                ) else Icon(painterResource(R.drawable.ic_calendar), null,
                    tint = colors.accent.copy(alpha = 0.25f), modifier = Modifier.align(Alignment.Center).size(if (compact) 26.dp else 40.dp))

                if (completion > 0f) Box(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = if (compact) 6.dp else 12.dp, vertical = if (compact) 4.dp else 6.dp)
                        .height(if (compact) 22.dp else 28.dp).graphicsLayer { alpha = completion }
                        .background(colors.surface.copy(alpha = 0.85f), RoundedCornerShape(8.dp))
                        .clearAndSetSemantics { },
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(stringResource(R.string.schedule_completed), Modifier.fillMaxWidth(),
                        style = TextStyle(color = completedTextColor, fontWeight = FontWeight.Bold,
                            fontStyle = FontStyle.Italic, letterSpacing = 1.sp, textAlign = TextAlign.Center),
                        autoSize = TextAutoSize.StepBased(minFontSize = 12.sp, maxFontSize = 22.sp, stepSize = 0.5.sp),
                        maxLines = 1, softWrap = false)
                }
                if (entry.isRecurring) Box(
                    Modifier.align(Alignment.TopStart).padding(if (compact) 6.dp else 10.dp)
                        .size(if (compact) 22.dp else 30.dp).background(Color.White, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(painterResource(R.drawable.ic_repeat),
                        stringResource(if (entry.recurrenceStopped) R.string.schedule_recurrence_stopped else R.string.schedule_recurring),
                        Modifier.size(if (compact) 15.dp else 19.dp), tint = if (entry.completed || entry.recurrenceStopped) Color(0xFF666666) else colors.accent)
                }
                if (coverBadge != null) Box(Modifier.align(Alignment.TopEnd).padding(end = if (compact) 6.dp else 10.dp)) {
                    coverBadge()
                }
            }
            Column(Modifier.fillMaxWidth().height(infoHeight).background(bottomColor).padding(horizontal = if (compact) 7.dp else 10.dp, vertical = if (compact) 6.dp else 8.dp),
                verticalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 4.dp)) {
                Row(
                    Modifier.fillMaxWidth().height(titleHeight),
                    horizontalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (entry.time != null) Icon(
                        painterResource(R.drawable.ic_alarm), stringResource(R.string.exact_time_schedule),
                        Modifier.size(if (compact) 13.dp else 16.dp), tint = AlarmTint,
                    )
                    Text(
                        text = entry.title, modifier = Modifier.weight(1f).height(titleHeight),
                        color = titleTextColor, fontWeight = if (entry.completed) FontWeight.SemiBold else FontWeight.Bold,
                        fontSize = if (compact) 11.sp else 13.sp, lineHeight = titleLineHeight,
                        maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(Modifier.fillMaxWidth().height(metadataHeight), verticalAlignment = Alignment.CenterVertically) {
                    BasicText(
                        text = entry.date.scheduleLabel() + (entry.time?.let { " ${it.scheduleLabel()}" } ?: ""),
                        modifier = Modifier.weight(1f),
                        style = TextStyle(color = dateTextColor, lineHeight = metadataLineHeight,
                            fontWeight = if (entry.completed) FontWeight.Normal else FontWeight.Bold),
                        autoSize = TextAutoSize.StepBased(minFontSize = if (compact) 7.sp else 8.sp, maxFontSize = if (compact) 9.sp else 11.sp, stepSize = 0.5.sp),
                        maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
                    )
                    if (entry.attachmentCount > 0) Row(Modifier.padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        Icon(painterResource(R.drawable.ic_attach), stringResource(R.string.schedule_attachments_count, entry.attachmentCount), Modifier.size(if (compact) 10.dp else 12.dp), tint = secondaryTextColor)
                        Text(entry.attachmentCount.toString(), color = secondaryTextColor, fontSize = if (compact) 9.sp else 11.sp, lineHeight = metadataLineHeight, maxLines = 1)
                    }
                }
            }
        }
    }
}
