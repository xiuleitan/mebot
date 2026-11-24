package com.txl.mebot.ui.flashnote

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.txl.mebot.ui.common.hapticAction
import com.txl.mebot.R
import com.txl.mebot.data.local.FlashnoteStatus
import com.txl.mebot.ui.schedule.scheduleColors
import com.txl.mebot.ui.theme.LocalAppTheme
import kotlin.math.roundToInt

@Composable
internal fun FlashnoteStatisticsSection(
    statistics: FlashnoteStatisticsState, status: FlashnoteStatus,
    pending: Int?, completed: Int?, maxHeight: Dp,
    onStatus: (FlashnoteStatus) -> Unit, onToggle: () -> Unit,
    onMonth: (Int) -> Unit, onDay: (Int) -> Unit, onRetry: () -> Unit, modifier: Modifier = Modifier,
) {
    val colors = scheduleColors()
    val dark = LocalAppTheme.current.isDark
    val container by animateColorAsState(if (dark) Color(0xFF303B49) else Color(0xFFE3EAF3),
        tween(240), label = "flashnote_statistics_container")
    val expansion = animateFloatAsState(if (statistics.expanded) 1f else 0f,
        tween(if (statistics.expanded) 340 else 280), label = "flashnote_statistics_connection")
    var headerHeight by remember { mutableFloatStateOf(0f) }
    val shape = remember(headerHeight, expansion.value) { ConnectedStatisticsShape(headerHeight, expansion.value) }
    val toggleLabel = stringResource(if (statistics.expanded) R.string.flashnote_statistics_collapse else R.string.flashnote_statistics_expand)

    Box(modifier.fillMaxWidth().drawWithCache {
        val path = (shape.createOutline(size, layoutDirection, this) as Outline.Generic).path
        val borderWidth = 1.dp.toPx()
        onDrawBehind { drawStatisticsContainer(path, container, colors.accent, borderWidth) }
    }) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().onSizeChanged { headerHeight = it.height.toFloat() }.selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                FlashnoteStatusButton(stringResource(R.string.flashnote_pending), pending, status == FlashnoteStatus.PENDING,
                    { onStatus(FlashnoteStatus.PENDING) }, Modifier.weight(1f))
                IconButton(onToggle, Modifier.size(48.dp).semantics { stateDescription = toggleLabel }, shape = CircleShape,
                    colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Transparent, contentColor = colors.ink)) {
                    Icon(painterResource(R.drawable.ic_flashnote_statistics), toggleLabel, Modifier.size(23.dp))
                }
                FlashnoteStatusButton(stringResource(R.string.flashnote_completed), completed, status == FlashnoteStatus.COMPLETED,
                    { onStatus(FlashnoteStatus.COMPLETED) }, Modifier.weight(1f))
            }
            if (statistics.expanded || expansion.value > 0f) {
                // 按钮状态统一驱动高度、透明度与连体边框，列表滚动不参与开合。
                Box(Modifier.fillMaxWidth().clipToBounds().layout { measurable, constraints ->
                    val content = measurable.measure(constraints)
                    layout(content.width, (content.height * expansion.value).roundToInt()) { content.placeRelative(0, 0) }
                }.graphicsLayer { alpha = ((expansion.value - 0.4f) / 0.6f).coerceIn(0f, 1f) }
                    .drawWithContent {
                        val inset = size.width * (1f - expansion.value) / 2f
                        clipRect(left = inset, right = size.width - inset) { this@drawWithContent.drawContent() }
                    }) {
                    Box(Modifier.padding(top = StatisticsGap)) {
                        FlashnoteStatisticsPanel(statistics, maxHeight, onMonth, onDay, onRetry)
                    }
                }
            }
        }
    }
}

@Composable
private fun FlashnoteStatusButton(label: String, count: Int?, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val colors = scheduleColors()
    val background by animateColorAsState(if (selected) colors.accent else colors.surface, tween(200), label = "flashnote_filter_background")
    val foreground by animateColorAsState(if (selected) colors.onAccent else colors.secondary, tween(200), label = "flashnote_filter_text")
    Surface(hapticAction(onClick), modifier.heightIn(min = 46.dp).semantics { this.selected = selected; role = Role.Tab },
        shape = CircleShape, color = background, shadowElevation = if (selected) 3.dp else 1.dp,
        border = BorderStroke(0.5.dp, colors.divider.copy(alpha = 0.35f))) {
        Box(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), contentAlignment = Alignment.Center) {
            Text(if (count == null) label else stringResource(R.string.flashnote_status_count, label, count), color = foreground,
                fontSize = 14.sp, lineHeight = 21.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium)
        }
    }
}

/** 按钮和卡片共用一个封闭轮廓和主题色描边，连接处无需叠加边框。 */
internal class ConnectedStatisticsShape(private val measuredHeader: Float, private val expansion: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline = with(density) {
        val p = expansion.coerceIn(0f, 1f)
        val header = measuredHeader.takeIf { it > 0f } ?: 48.dp.toPx()
        val cx = size.width / 2f
        val cy = header / 2f
        val radius = minOf(24.dp.toPx(), cx, cy)
        if (p == 0f) return Outline.Generic(Path().apply {
            addOval(Rect(cx - radius, cy - radius, cx + radius, cy + radius))
        })
        val circleBottom = cy + radius
        val top = lerp(circleBottom, header + StatisticsGap.toPx(), p)
        val bottom = circleBottom + (size.height - header).coerceAtLeast(0f) + (header - circleBottom) * p
        val left = cx * (1f - p)
        val right = size.width - left
        val corner = minOf(22.dp.toPx() * p, (bottom - top).coerceAtLeast(0f) / 2f, (right - left) / 2f)
        val shoulder = minOf(36.dp.toPx(), (cx - radius - 22.dp.toPx()).coerceAtLeast(0f))
        val k = 0.55228475f
        val path = Path().apply {
            moveTo(cx, cy - radius)
            cubicTo(cx + k * radius, cy - radius, cx + radius, cy - k * radius, cx + radius, cy)
            cubicTo(cx + radius, cy + lerp(k * radius, 20.dp.toPx(), p),
                cx + lerp(k * radius, radius, p), top, cx + (radius + shoulder) * p, top)
            lineTo(right - corner, top)
            cubicTo(right - corner + k * corner, top, right, top + corner - k * corner, right, top + corner)
            lineTo(right, bottom - corner)
            cubicTo(right, bottom - corner + k * corner, right - corner + k * corner, bottom, right - corner, bottom)
            lineTo(left + corner, bottom)
            cubicTo(left + corner - k * corner, bottom, left, bottom - corner + k * corner, left, bottom - corner)
            lineTo(left, top + corner)
            cubicTo(left, top + corner - k * corner, left + corner - k * corner, top, left + corner, top)
            lineTo(cx - (radius + shoulder) * p, top)
            cubicTo(cx - lerp(k * radius, radius, p), top,
                cx - radius, cy + lerp(k * radius, 20.dp.toPx(), p), cx - radius, cy)
            cubicTo(cx - radius, cy - k * radius, cx - k * radius, cy - radius, cx, cy - radius)
            close()
        }
        Outline.Generic(path)
    }
}

/** 直接绘制矢量边框，不将居中的局部轮廓另存为可变尺寸的边框位图。 */
internal fun DrawScope.drawStatisticsContainer(path: Path, container: Color, border: Color, borderWidth: Float) {
    drawPath(path, container)
    // 两倍描边向内裁切，保证圆钮顶边、图表侧边都完整处于绘制区域内。
    clipPath(path) { drawPath(path, border, style = Stroke(width = borderWidth * 2f)) }
}

private val StatisticsGap = 14.dp
