package com.txl.mebot.ui.home

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.txl.mebot.R

/** 仅供首页 UI 验收的只读示例，与各 Bot 的正式记录分开。 */
internal data class HomePreviewEntry(
    val id: String,
    val dayOffset: Long,
    @param:StringRes val title: Int,
    @param:DrawableRes val cover: Int,
    @param:StringRes val source: Int,
    val completed: Boolean = false,
)

internal val homePreviewEntries = listOf(
    HomePreviewEntry("meeting", 0, R.string.home_sample_meeting, R.drawable.schedule_meeting, R.string.schedule_title),
    HomePreviewEntry("groceries", 0, R.string.home_sample_groceries, R.drawable.schedule_groceries, R.string.schedule_title),
    HomePreviewEntry("desk", 0, R.string.home_sample_desk, R.drawable.schedule_review, R.string.flashnote_title),
    HomePreviewEntry("dinner", 0, R.string.home_sample_dinner, R.drawable.schedule_dinner, R.string.schedule_title),
    HomePreviewEntry("membership", 0, R.string.home_sample_membership, R.drawable.schedule_fitness, R.string.subscriptions_title),
    HomePreviewEntry("health", 0, R.string.home_sample_health, R.drawable.schedule_medical, R.string.dayima_title),
    HomePreviewEntry("completed-notes", 0, R.string.home_sample_completed_notes, R.drawable.schedule_review, R.string.flashnote_title, true),
    HomePreviewEntry("completed-workout", 0, R.string.home_sample_completed_workout, R.drawable.schedule_fitness, R.string.schedule_title, true),
    HomePreviewEntry("notes", -1, R.string.home_sample_notes, R.drawable.schedule_review, R.string.archive_title, true),
    HomePreviewEntry("purchase-record", -1, R.string.home_sample_purchase_record, R.drawable.schedule_groceries, R.string.archive_title, true),
    HomePreviewEntry("medical-record", -2, R.string.home_sample_medical_record, R.drawable.schedule_medical, R.string.archive_title),
    HomePreviewEntry("workout-record", -2, R.string.home_sample_workout_record, R.drawable.schedule_fitness, R.string.flashnote_title, true),
    HomePreviewEntry("old-dinner", -3, R.string.home_sample_dinner, R.drawable.schedule_dinner, R.string.schedule_title, true),
    HomePreviewEntry("old-notes", -3, R.string.home_sample_notes, R.drawable.schedule_review, R.string.archive_title),
    HomePreviewEntry("tomorrow-groceries", 1, R.string.home_sample_tomorrow_groceries, R.drawable.schedule_groceries, R.string.schedule_title),
    HomePreviewEntry("tomorrow-dinner", 1, R.string.home_sample_dinner, R.drawable.schedule_dinner, R.string.schedule_title),
)

internal fun homePreviewDays(showTomorrow: Boolean, showCompleted: Boolean = showTomorrow): Map<Long, List<HomePreviewEntry>> =
    homePreviewEntries.filter { entry ->
        (entry.dayOffset <= 0 || (showTomorrow && entry.dayOffset == 1L)) &&
            (entry.dayOffset == 1L || showCompleted || !entry.completed)
    }.groupBy { it.dayOffset }.toSortedMap(compareByDescending { it })
