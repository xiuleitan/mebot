package com.txl.mebot.ui.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.txl.mebot.ui.common.hapticAction
import com.txl.mebot.ui.common.hapticChange
import com.txl.mebot.R
import com.txl.mebot.data.home.*
import com.txl.mebot.data.local.HomeEventKind
import com.txl.mebot.ui.schedule.localPhoto
import com.txl.mebot.ui.schedule.ScheduleCard
import com.txl.mebot.ui.schedule.ScheduleEntry
import com.txl.mebot.ui.schedule.asEntry
import com.txl.mebot.ui.schedule.scheduleCardMetrics
import com.txl.mebot.ui.schedule.scheduleColors
import com.txl.mebot.ui.theme.LocalAppTheme
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlin.math.ceil

@Composable
internal fun HomeTaskSlot(slot: HomeSlot, open: (HomeCard) -> Unit, select: (String) -> Unit,
    modifier: Modifier, compact: Boolean = false, pin: (String, Boolean) -> Unit = { _, _ -> }) {
    val canPin = slot.selected.source == HomeSource.FLASHNOTE && !slot.selected.completed
    Box(modifier) {
        if (slot.cards.size == 1) HomeTaskCard(slot.selected, { open(slot.selected) }, Modifier.fillMaxWidth(), compact = compact, pinned = slot.pinned,
            onPinChange = if (canPin) { value -> pin(slot.selected.sourceId, value) } else null)
        else {
            val isDark = LocalAppTheme.current.isDark
            val shape = RoundedCornerShape(if (compact) 12.dp else 16.dp)
            val ids = slot.cards.map { it.sourceId }
            key(ids) {
                val index = ids.indexOf(slot.selectedId).coerceAtLeast(0)
                val base = Int.MAX_VALUE / 2 - (Int.MAX_VALUE / 2) % ids.size
                val pager = remember { PagerState(currentPage = base + index) { Int.MAX_VALUE } }
                com.txl.mebot.ui.common.PagerSelectionHaptics(pager) { ids[it % ids.size] }
                val selected by rememberUpdatedState(select)
                LaunchedEffect(pager, ids) {
                    // 初始化时选中的卡片已由首页状态决定，避免把旧页面写回数据库。
                    snapshotFlow { pager.settledPage }.drop(1).collect { selected(ids[it % ids.size]) }
                }
                LaunchedEffect(pager, index) {
                    val start = pager.settledPage
                    if (start % ids.size == index) return@LaunchedEffect
                    snapshotFlow { pager.isScrollInProgress }.first { !it }
                    // 若等待期间手动翻页已经落位，优先保留手动选择。
                    if (pager.settledPage != start) return@LaunchedEffect
                    val forward = Math.floorMod(index - start % ids.size, ids.size)
                    val offset = if (forward <= ids.size / 2) forward else forward - ids.size
                    val target = start + offset
                    pager.animateScrollToPage(if (target in 0 until Int.MAX_VALUE) target else base + index)
                }
                // 整个翻页窗口就是普通卡片的外框，阴影画在窗口外，内容只在卡片内上下切换。
                BoxWithConstraints(Modifier.fillMaxWidth().dropShadow(shape) {
                    radius = 5.dp.toPx()
                    offset = Offset(0f, 2.dp.toPx())
                    color = Color.Black.copy(alpha = if (isDark) 0.40f else 0.18f)
                }.clip(shape).pointerInput(Unit) {
                    // 改为纵向翻页后，横向拖动也应取消点击，避免手指仍在卡片内时误开详情。
                    detectHorizontalDragGestures { change, _ -> change.consume() }
                }.testTag("home_flashnote_deck")
                    .semantics { contentDescription = "未完成闪念，上下滑动切换" }) {
                    val cardHeight = scheduleCardMetrics(maxWidth, compact).height
                    VerticalPager(pager, Modifier.fillMaxWidth().height(cardHeight), beyondViewportPageCount = 1,
                        key = { it }, pageSpacing = 8.dp) { page ->
                        val card = slot.cards[page % ids.size]
                        HomeTaskCard(card, { open(card) }, Modifier.fillMaxWidth(), compact = compact,
                            cardHeight = cardHeight, showShadow = false, pinned = slot.pinned && card.sourceId == slot.selectedId,
                            onPinChange = if (canPin) { value -> pin(card.sourceId, value) } else null)
                    }
                }
            }
        }
    }
}

@Composable
internal fun HomeCover(card: HomeCard, modifier: Modifier, completion: Float = 0f,
    compact: Boolean = false, coverResource: Int? = null) {
    val colors = scheduleColors()
    val drawable = coverResource ?: card.coverDrawable()
    val photo = localPhoto(card.coverRelativePath, drawable, maxDimension = if (compact) 256 else 512)
    val filter = remember(completion) { ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(1f - completion) }) }
    if (photo != null) Image(photo, null, modifier, contentScale = ContentScale.Crop, colorFilter = filter)
    else Box(modifier.background(lerp(colors.selected.copy(alpha = .3f), colors.past, completion)), contentAlignment = Alignment.Center) {
        Icon(painterResource(when (card.source) {
            HomeSource.SCHEDULE -> R.drawable.ic_calendar
            HomeSource.FLASHNOTE -> R.drawable.ic_flashnote
            HomeSource.DAYIMA -> R.drawable.ic_calendar
            HomeSource.SUBSCRIPTION -> R.drawable.ic_subscription_grid
        }), null, Modifier.size(if (compact) 26.dp else 32.dp), tint = colors.secondary.copy(alpha = .8f))
    }
}

private fun HomeCard.coverDrawable(): Int? = when (eventKind) {
    HomeEventKind.DAYIMA_PREPARE -> R.drawable.dayima_reminder_prepare
    HomeEventKind.DAYIMA_MISSING -> R.drawable.dayima_reminder_missing
    else -> when (builtinKey) {
        "meeting" -> R.drawable.schedule_meeting
        "groceries" -> R.drawable.schedule_groceries
        "review" -> R.drawable.schedule_review
        "dinner" -> R.drawable.schedule_dinner
        "fitness" -> R.drawable.schedule_fitness
        "medical" -> R.drawable.schedule_medical
        else -> null
    }
}

@Composable
internal fun HomeTaskCard(card: HomeCard, open: () -> Unit, modifier: Modifier,
    compact: Boolean = false, cardHeight: Dp? = null, coverResource: Int? = null, showShadow: Boolean = true, pinned: Boolean = false, onPinChange: ((Boolean) -> Unit)? = null) {
    if (card.source == HomeSource.SCHEDULE) {
        // 正式数据沿用日程页的完整快照与映射；示例卡只补齐预览所需字段。
        val entry = card.schedule?.asEntry() ?: ScheduleEntry(card.sourceId, card.title, card.day,
            image = coverResource ?: card.coverDrawable(), detail = card.body, completed = card.completed,
            sessionId = card.sessionId, revision = card.revision, coverRelativePath = card.coverRelativePath)
        ScheduleCard(entry, open, modifier, compact = compact,
            cardModifier = Modifier.testTag("home_card_${card.id}"),
            coverBadge = { HomeCardTypeLabel(card, compact) })
        return
    }
    val colors = scheduleColors()
    val isDark = LocalAppTheme.current.isDark
    val completedText = if (isDark) Color(0xFFD0D0D0) else Color(0xFF666666)
    val completion by animateFloatAsState(if (card.completed) 1f else 0f, tween(260), label = "home_completion")
    val background = lerp(colors.surface, colors.completedBottom, completion)
    val titleColor = lerp(colors.ink, completedText, completion)
    val stateLabel = stringResource(if (card.completed) R.string.schedule_completed else R.string.schedule_pending)
    val shape = RoundedCornerShape(if (compact) 12.dp else 16.dp)
    val shadow = if (card.source == HomeSource.FLASHNOTE && showShadow) Modifier.dropShadow(shape) {
        radius = 5.dp.toPx()
        offset = Offset(0f, 2.dp.toPx())
        color = Color.Black.copy(alpha = if (isDark) 0.40f else 0.18f)
    } else Modifier
    Surface(hapticAction(open), modifier.fillMaxWidth().then(shadow).testTag("home_card_${card.id}").semantics { stateDescription = stateLabel },
        shape = shape, color = background, contentColor = titleColor,
        border = BorderStroke(1.dp, colors.divider.copy(alpha = .65f))) {
        BoxWithConstraints {
            val density = LocalDensity.current
            val cardWidth = maxWidth
            val lineHeight = if (compact) 17.sp else 20.sp
            val footerHeight = with(density) { ceil(lineHeight.toPx()).toInt().toDp() } + if (compact) 14.dp else 18.dp
            val imageHeight = ((cardHeight ?: scheduleCardMetrics(maxWidth, compact).height) - footerHeight).coerceAtLeast(1.dp)
            val textOnly = card.source == HomeSource.FLASHNOTE && card.coverRelativePath.isNullOrBlank() &&
                card.builtinKey.isNullOrBlank() && coverResource == null
            Box(Modifier.fillMaxWidth().height(imageHeight + footerHeight)) {
                if (textOnly) {
                    val bottom = if (card.completed) { if (compact) 30.dp else 40.dp } else if (compact) 8.dp else 12.dp
                    val contentTop = 12.dp
                    val textMeasurer = rememberTextMeasurer()
                    val titleStyle = LocalTextStyle.current.merge(TextStyle(fontSize = if (compact) 12.sp else 14.sp,
                        lineHeight = lineHeight, fontWeight = FontWeight.Medium))
                    val labelWidth = textMeasurer.measure(card.source.label, style = homeCardTypeLabelStyle(compact)).size.width
                    val firstLineWidth = with(density) {
                        // 标签实测宽度、内边距及文字间距只从首行右侧扣除。
                        (cardWidth - (if (compact) 8.dp else 12.dp) - (if (compact) 6.dp else 10.dp) -
                            (if (compact) 10.dp else 12.dp) - 6.dp).roundToPx() - labelWidth
                    }.coerceAtLeast(1)
                    val firstLine = textMeasurer.measure(card.title, style = titleStyle, maxLines = 2,
                        constraints = Constraints(maxWidth = firstLineWidth))
                    val breakAt = firstLine.getLineEnd(0)
                    val displayTitle = when {
                        firstLine.getLineRight(0) - firstLine.getLineLeft(0) > firstLineWidth -> "\n${card.title}"
                        breakAt >= card.title.length || card.title.getOrNull(breakAt - 1) == '\n' -> card.title
                        else -> card.title.substring(0, breakAt) + "\n" + card.title.substring(breakAt)
                    }
                    var titleLayout by remember(displayTitle, compact, cardWidth, density.fontScale) { mutableStateOf<TextLayoutResult?>(null) }
                    val body = remember(card.title, card.body) {
                        val content = card.body.trim()
                        if (content.substringBefore('\n').trim() == card.title.trim()) content.substringAfter('\n', "").trim() else content
                    }
                    val bodyLineHeight = if (compact) 14.sp else 18.sp
                    val gap = if (compact) 4.dp else 6.dp
                    val measuredTitle = titleLayout
                    val bodyLines = with(density) {
                        ((imageHeight + footerHeight - contentTop - bottom - gap).toPx() - (measuredTitle?.size?.height ?: 0)) /
                            ceil(bodyLineHeight.toPx())
                    }.toInt().coerceAtMost(if (compact) 4 else 6)
                    Column(Modifier.fillMaxSize().padding(start = if (compact) 8.dp else 12.dp,
                        end = if (compact) 8.dp else 12.dp, top = contentTop, bottom = bottom)) {
                        Text(displayTitle, Modifier.fillMaxWidth().testTag("home_flashnote_title_${card.id}")
                            .clearAndSetSemantics { text = AnnotatedString(card.title) }, style = titleStyle,
                            maxLines = Int.MAX_VALUE, overflow = TextOverflow.Ellipsis,
                            onTextLayout = { titleLayout = it })
                        if (body.isNotBlank() && measuredTitle != null && !measuredTitle.hasVisualOverflow && bodyLines > 0) {
                            Spacer(Modifier.height(gap))
                            Text(body, Modifier.fillMaxWidth().testTag("home_flashnote_body_${card.id}"),
                                color = lerp(colors.secondary, completedText, completion),
                                fontSize = if (compact) 10.sp else 12.sp, lineHeight = bodyLineHeight,
                                maxLines = bodyLines, overflow = TextOverflow.Ellipsis)
                        }
                    }
                } else Column {
                    Box(Modifier.fillMaxWidth().height(imageHeight)) {
                        HomeCover(card, Modifier.fillMaxSize(), completion, compact, coverResource)
                    }
                    Text(card.title, Modifier.fillMaxWidth().padding(horizontal = if (compact) 8.dp else 12.dp,
                        vertical = if (compact) 7.dp else 9.dp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        fontSize = if (compact) 12.sp else 14.sp, lineHeight = lineHeight, fontWeight = FontWeight.Medium)
                }
                if (completion > 0f) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .padding(start = if (compact) 6.dp else 12.dp, end = if (compact) 6.dp else 12.dp,
                        top = if (compact) 4.dp else 6.dp,
                        bottom = (if (textOnly) 0.dp else footerHeight) + if (compact) 4.dp else 6.dp)
                    .height(if (compact) 22.dp else 28.dp).graphicsLayer { alpha = completion }
                    .background(colors.surface.copy(alpha = .85f), RoundedCornerShape(if (compact) 6.dp else 8.dp))
                    .clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
                    BasicText(stringResource(R.string.schedule_completed), Modifier.fillMaxWidth(),
                        style = TextStyle(color = completedText, fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic,
                            letterSpacing = 1.sp, textAlign = TextAlign.Center),
                        autoSize = TextAutoSize.StepBased(minFontSize = if (compact) 10.sp else 12.sp,
                            maxFontSize = if (compact) 16.sp else 22.sp, stepSize = .5.sp),
                        maxLines = 1, softWrap = false)
                }
                HomeCardTypeLabel(card, compact,
                    Modifier.align(Alignment.TopEnd).padding(end = if (compact) 6.dp else 10.dp), pinned, onPinChange)
            }
        }
    }
}

@Composable
private fun HomeCardTypeLabel(card: HomeCard, compact: Boolean, modifier: Modifier = Modifier,
    pinned: Boolean = false, onPinChange: ((Boolean) -> Unit)? = null) {
    val colors = scheduleColors()
    val corner = if (compact) 4.dp else 6.dp
    val shape = RoundedCornerShape(bottomStart = corner, bottomEnd = corner)
    val canPin = card.source == HomeSource.FLASHNOTE && !card.completed && onPinChange != null
    val selected = canPin && pinned
    val scheme = MaterialTheme.colorScheme
    val background by animateColorAsState(if (selected) scheme.primaryContainer else colors.surface.copy(alpha = .94f),
        tween(180), label = "home_label_background")
    val border by animateColorAsState(if (selected) scheme.primary else colors.divider.copy(alpha = .65f),
        tween(180), label = "home_label_border")
    val toggle = if (canPin) Modifier.clip(shape).toggleable(pinned, role = Role.Checkbox,
        onValueChange = hapticChange(requireNotNull(onPinChange))).semantics {
            contentDescription = if (pinned) "取消固定闪念" else "固定闪念"
            stateDescription = if (pinned) "已固定" else "自动轮换"
        } else Modifier
    Text(
        text = if (card.source == HomeSource.SUBSCRIPTION) "订阅" else card.source.label,
        modifier = modifier.testTag("home_card_type_${card.id}").then(toggle)
            .background(background, shape)
            .border(1.dp, border, shape)
            .padding(horizontal = if (compact) 5.dp else 6.dp, vertical = if (compact) 2.dp else 3.dp),
        color = if (selected) scheme.onPrimaryContainer else colors.ink, style = homeCardTypeLabelStyle(compact),
        maxLines = 1, softWrap = false,
    )
}

@Composable
private fun homeCardTypeLabelStyle(compact: Boolean) = LocalTextStyle.current.merge(TextStyle(
    fontSize = if (compact) 9.sp else 10.sp, lineHeight = if (compact) 12.sp else 14.sp,
    fontWeight = FontWeight.Medium,
))
