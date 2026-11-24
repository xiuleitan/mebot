package com.txl.mebot.ui.dayima

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.txl.mebot.ui.common.hapticAction
import com.txl.mebot.R
import com.txl.mebot.data.dayima.*
import com.txl.mebot.ui.schedule.scheduleColors
import java.time.YearMonth
import java.time.format.DateTimeFormatter

@Composable
internal fun DayimaCycleCalendarCard(overview: DayimaOverview) {
    val estimate = overview.phases
    val prediction = overview.statistics.prediction.prediction
    val base = dayimaCalendarFirstMonth(estimate, prediction)
    var shownMonth by rememberSaveable(base.toString(), estimate.startDate?.toString(), prediction?.predictedDate?.toString()) {
        mutableStateOf(base.toString())
    }
    var selectedPhase by rememberSaveable { mutableStateOf<String?>(null) }
    val first = YearMonth.parse(shownMonth)
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val dayStyle = MaterialTheme.typography.labelSmall
    val numberSize = measurer.measure(AnnotatedString("30"), dayStyle).size
    val cellHeight = maxOf(30.dp, with(density) { numberSize.height.toDp() + 12.dp })

    DayimaCard {
        DayimaCalendarSummary(estimate, prediction, Modifier.fillMaxWidth())
        AnimatedContent(first, transitionSpec = {
            val sign = if (targetState > initialState) 1 else -1
            (slideInHorizontally(tween(220)) { it / 8 * sign } + fadeIn(tween(180)))
                .togetherWith(slideOutHorizontally(tween(220)) { -it / 8 * sign } + fadeOut(tween(100)))
        }, label = "dayima_calendar_months") { month ->
            DayimaContinuousCalendar(month, estimate, prediction, cellHeight,
                onPrevious = { shownMonth = month.minusMonths(1).toString() },
                onNext = { shownMonth = month.plusMonths(1).toString() })
        }
        DayimaPhaseButtons(estimate.currentPhase, { selectedPhase = it.name }, Modifier.fillMaxWidth())
    }
    DayimaPhaseDetailsSheet(selectedPhase?.let(DayimaCyclePhase::valueOf), estimate) { selectedPhase = null }
}

@Composable
private fun DayimaContinuousCalendar(first: YearMonth, estimate: DayimaPhaseEstimate, prediction: DayimaPrediction?, cellHeight: Dp,
    onPrevious: () -> Unit, onNext: () -> Unit) {
    val colors = scheduleColors()
    val cells = remember(first, estimate, prediction) { dayimaCalendarDays(first, estimate, prediction) }
    val bands = remember(cells) { dayimaCalendarMonthBands(cells) }
    val weekdays = stringArrayResource(R.array.dayima_calendar_weekdays)
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelSmall
    val monthSample = stringResource(R.string.dayima_calendar_month, 12)
    val monthWidth = maxOf(36.dp, with(density) { measurer.measure(AnnotatedString(monthSample), style).size.width.toDp() + 8.dp })
    val weekGap = 2.dp
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth().background(colors.past, RoundedCornerShape(10.dp)), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = hapticAction(onPrevious), enabled = first > DayimaFirstMonth, modifier = Modifier.size(48.dp)) {
                Icon(painterResource(R.drawable.ic_chevron_right), stringResource(R.string.dayima_calendar_previous), Modifier.size(18.dp).rotate(180f))
            }
            val last = first.plusMonths(1)
            Text(if (first.year == last.year) stringResource(R.string.dayima_calendar_year, first.year)
                else stringResource(R.string.dayima_calendar_year_range, first.year, last.year),
                Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = colors.secondary,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            IconButton(onClick = hapticAction(onNext), enabled = first < DayimaLastFirstMonth, modifier = Modifier.size(48.dp)) {
                Icon(painterResource(R.drawable.ic_chevron_right), stringResource(R.string.dayima_calendar_next), Modifier.size(18.dp))
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = monthWidth + 8.dp)) {
            weekdays.forEach { Text(it, Modifier.weight(1f), color = colors.secondary, style = style,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.width(monthWidth), verticalArrangement = Arrangement.spacedBy(weekGap)) {
                bands.forEach { band ->
                    val monthDescription = stringResource(R.string.dayima_calendar_year, band.month.year) +
                        stringResource(R.string.dayima_calendar_month, band.month.monthValue)
                    Box(Modifier.fillMaxWidth().height(cellHeight * band.weekCount + weekGap * (band.weekCount - 1))
                        .background(colors.past, RoundedCornerShape(10.dp)).clearAndSetSemantics { contentDescription = monthDescription },
                        contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.dayima_calendar_month, band.month.monthValue), color = colors.secondary, style = style)
                    }
                }
            }
            BoxWithConstraints(Modifier.weight(1f)) {
                val dayWidth = maxWidth / 7
                Column(verticalArrangement = Arrangement.spacedBy(weekGap)) {
                    for (row in cells.chunked(7)) Row(Modifier.fillMaxWidth()) {
                        row.forEachIndexed { index, day ->
                            if (day == null) Spacer(Modifier.weight(1f).height(cellHeight)) else {
                                val palette = day.phase?.colors()
                                val starts = index == 0 || row[index - 1]?.phase != day.phase
                                val ends = index == 6 || row[index + 1]?.phase != day.phase
                                val shape = RoundedCornerShape(topStart = if (starts) 8.dp else 0.dp, bottomStart = if (starts) 8.dp else 0.dp,
                                    topEnd = if (ends) 8.dp else 0.dp, bottomEnd = if (ends) 8.dp else 0.dp)
                                val description = listOfNotNull(day.date.dayimaDate(), day.phase?.let {
                                    stringResource(R.string.dayima_phase_estimated, stringResource(it.label())) },
                                    stringResource(R.string.dayima_calendar_today).takeIf { day.today },
                                    stringResource(R.string.dayima_recent_start).takeIf { day.start },
                                    stringResource(R.string.dayima_calendar_predicted).takeIf { day.predicted }).joinToString("，")
                                val predictedColor = DayimaCyclePhase.MENSTRUAL.colors().foreground
                                val monthBoundary = day.date.dayOfMonth == 1 && YearMonth.from(day.date) != first && index != 0
                                Box(Modifier.weight(1f).height(cellHeight).padding(vertical = 2.dp)
                                    .background(palette?.background ?: Color.Transparent, shape)
                                    .clearAndSetSemantics { contentDescription = description }, contentAlignment = Alignment.Center) {
                                    if (day.today || day.predicted) Box(Modifier.size(minOf(dayWidth - 2.dp, cellHeight - 6.dp))
                                        .border(if (day.today) 1.5.dp else 1.dp, if (day.predicted) predictedColor else colors.accent, CircleShape))
                                    Text(if (monthBoundary && !day.today && !day.predicted)
                                        stringResource(R.string.dayima_calendar_month_day, day.date.monthValue, 1) else day.date.dayOfMonth.toString(),
                                        style = style, fontWeight = if (day.today || day.start || day.predicted || monthBoundary) FontWeight.Bold else FontWeight.Normal,
                                        color = palette?.foreground ?: if (day.predicted) predictedColor else colors.ink, maxLines = 1)
                                    if (day.start) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 1.dp).size(3.dp)
                                        .background(palette?.foreground ?: colors.accent, CircleShape))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayimaCalendarSummary(estimate: DayimaPhaseEstimate, prediction: DayimaPrediction?, modifier: Modifier) {
    val colors = scheduleColors()
    val usable = dayimaCalendarPrediction(estimate, prediction)
    val caption = stringResource(if (usable != null && usable.predictedDate < estimate.asOfDate)
        R.string.dayima_calendar_previous_prediction else R.string.dayima_calendar_prediction)
    val date = usable?.predictedDate?.format(DateTimeFormatter.ofPattern(
        if (usable.predictedDate.year == estimate.asOfDate.year) "M月d日" else "yyyy年M月d日"))
        ?: stringResource(if (estimate.status == DayimaPhaseEstimateStatus.AWAITING_RECORD)
            R.string.dayima_calendar_missing else R.string.dayima_calendar_no_prediction)
    val predictionText = stringResource(R.string.dayima_calendar_prediction_summary, caption, date)
    val phase = estimate.currentPhase?.let { stringResource(it.label()) } ?: stringResource(when (estimate.status) {
        DayimaPhaseEstimateStatus.NO_RECORDS -> R.string.dayima_phase_none
        DayimaPhaseEstimateStatus.AWAITING_RECORD -> R.string.dayima_phase_awaiting
        else -> R.string.dayima_phase_unknown
    })
    val currentText = stringResource(R.string.dayima_calendar_current, phase)
    val phaseColor = estimate.currentPhase?.colors()?.foreground
    val currentLabel = AnnotatedString.Builder(currentText).apply {
        val start = currentText.indexOf(phase)
        if (phaseColor != null && start >= 0) addStyle(SpanStyle(color = phaseColor), start, start + phase.length)
    }.toAnnotatedString()
    val currentDescription = estimate.currentPhase?.let {
        stringResource(R.string.dayima_phase_estimated, stringResource(it.currentLabel()))
    } ?: currentText
    val style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium)
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val textWidth = with(density) {
        (measurer.measure(AnnotatedString(predictionText), style).size.width +
            measurer.measure(AnnotatedString(currentText), style).size.width).toDp()
    }
    BoxWithConstraints(modifier) {
        val rowWidth = maxOf(maxWidth, textWidth + 12.dp)
        Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            Row(Modifier.width(rowWidth), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(predictionText, color = colors.ink, style = style, maxLines = 1, softWrap = false)
                Text(currentLabel, Modifier.clearAndSetSemantics { contentDescription = currentDescription },
                    color = colors.ink, style = style, maxLines = 1, softWrap = false)
            }
        }
    }
}

@Composable
private fun DayimaPhaseButtons(current: DayimaCyclePhase?, onSelect: (DayimaCyclePhase) -> Unit, modifier: Modifier) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val style = MaterialTheme.typography.labelMedium
    val names = DayimaCyclePhase.entries.map { stringResource(it.label()) }
    val minimumWidth = maxOf(48.dp, with(density) {
        names.maxOf { measurer.measure(AnnotatedString(it), style).size.width }.toDp() + 12.dp
    })
    BoxWithConstraints(modifier) {
        val gap = 6.dp
        val buttonWidth = maxOf((maxWidth - gap * 3) / 4, minimumWidth)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(gap)) {
            DayimaCyclePhase.entries.forEachIndexed { index, phase ->
                val palette = phase.colors()
                val name = names[index]
                val description = if (phase == current) stringResource(R.string.dayima_phase_current_description, name) else name
                Button(onClick = hapticAction { onSelect(phase) }, modifier = Modifier.width(buttonWidth).heightIn(min = 36.dp).semantics { contentDescription = description },
                    shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = palette.background, contentColor = palette.foreground),
                    border = BorderStroke(if (phase == current) 1.5.dp else 0.5.dp, palette.foreground.copy(alpha = if (phase == current) 1f else 0.25f))) {
                    Text(name, style = style, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}
