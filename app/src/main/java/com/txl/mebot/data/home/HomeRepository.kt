package com.txl.mebot.data.home

import androidx.room.withTransaction
import com.txl.mebot.data.dayima.DayimaChange
import com.txl.mebot.data.dayima.DayimaRepository
import com.txl.mebot.data.flashnote.FlashnoteRepository
import com.txl.mebot.data.local.*
import com.txl.mebot.data.schedule.ScheduleRepository
import java.time.*
import java.util.UUID
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext

enum class HomeSource(val label: String) { SCHEDULE("日程"), FLASHNOTE("闪念"), DAYIMA("大姨妈"), SUBSCRIPTION("订阅管理") }
data class HomeCard(val id: String, val source: HomeSource, val sourceId: String, val day: LocalDate,
    val title: String, val body: String, val completed: Boolean, val revision: Long,
    val coverRelativePath: String? = null, val builtinKey: String? = null,
    val eventId: String? = null, val sessionId: String? = null, val eventKind: HomeEventKind? = null,
    val schedule: ScheduleDisplayRecord? = null, val resolvedById: String? = null)
data class HomeSlot(val id: String, val cards: List<HomeCard>, val selectedId: String = cards.first().sourceId,
    val pinned: Boolean = false) {
    val selected get() = cards.firstOrNull { it.sourceId == selectedId } ?: cards.first()
}
data class HomeDay(val date: LocalDate, val slots: List<HomeSlot>)
data class HomeTimelineData(val days: List<HomeDay> = emptyList())
private data class Tasks(val schedules: List<ScheduleDisplayRecord>, val leads: List<HomeScheduleLead>, val notes: List<HomeNoteSummary>)
private data class History(val events: List<HomeEventEntity>, val days: List<HomeFlashnoteDayEntity>, val subscriptions: List<HomeSubscriptionRecord>,
    val periods: List<DayimaPeriodEntity>, val pin: HomeFlashnotePinEntity?)
private const val FlashnoteRotationMillis = 60 * 60 * 1000L

/** 首页只组织本地事实；各 Bot 继续拥有自己的任务状态和执行校验。 */
class HomeRepository(private val database: MebotDatabase, private val schedules: ScheduleRepository,
    private val notes: FlashnoteRepository, private val dayima: DayimaRepository,
    private val clock: Clock = Clock.systemUTC(), private val zone: () -> ZoneId = ZoneId::systemDefault) {
    private val dao = database.home()
    suspend fun prepare(from: LocalDate, through: LocalDate, today: LocalDate) = withContext(Dispatchers.IO) {
        var cursor = from
        while (cursor <= through) {
            val end = minOf(cursor.plusDays(89), through)
            schedules.materializeRange(cursor, end)
            cursor = end.plusDays(1)
        }
        schedules.materializeReminderRange(today, today.plusDays(35))
        dao.insertFlashnoteDay(HomeFlashnoteDayEntity(today, Random.nextLong(), null, clock.millis()))
        Unit
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(from: LocalDate, through: LocalDate, today: LocalDate): Flow<HomeTimelineData> {
        val fromMillis = from.atStartOfDay(zone()).toInstant().toEpochMilli()
        val until = through.plusDays(1).atStartOfDay(zone()).toInstant().toEpochMilli()
        val tasks = combine(dao.observeSchedules(from, through, fromMillis, until), dao.observeScheduleLeads(fromMillis, until),
            dao.observeNotes(fromMillis, until)) { schedules, leads, notes -> Tasks(schedules, leads, notes) }
        val history = combine(dao.observeEvents(from, through), dao.observeFlashnoteDays(from, through), dao.observeSubscriptions(),
            dayima.observePeriods(), dao.observeFlashnotePin()) { events, days, subscriptions, periods, pin -> History(events, days, subscriptions, periods, pin) }
        return combine(tasks, history) { tasksValue, historyValue -> tasksValue to historyValue }
            .transformLatest { (t, h) ->
                database.withTransaction {
                    val store = HomeEventStore(database, clock, zone)
                    for (schedule in t.schedules) store.rememberSchedule(schedule.occurrence)
                }
                val seed = h.days.firstOrNull { it.day == today }?.seed ?: 0
                val pending = orderNotes(t.notes.filter { it.status == FlashnoteStatus.PENDING }, seed)
                val selection = updateFlashnoteSelection(today, pending)
                emit(build(t, h, from, through, today, pending, selection?.selectedNoteId))
                if (selection != null && pending.size > 1 && h.pin?.noteId != selection.selectedNoteId) {
                    // 写入下一张后由 Room 重新发出状态，重排计时也跟随手动切换及候选变化。
                    do {
                        val remaining = selection.updatedAt + FlashnoteRotationMillis - clock.millis()
                        delay(remaining.coerceAtLeast(1_000L))
                    } while (updateFlashnoteSelection(today, pending) == selection)
                }
            }.distinctUntilChanged().flowOn(Dispatchers.IO)
    }
    private fun orderNotes(notes: List<HomeNoteSummary>, seed: Long) = notes.sortedWith(compareBy<HomeNoteSummary> {
        Random(seed xor it.id.hashCode().toLong()).nextLong()
    }.thenBy { it.id })

    private suspend fun updateFlashnoteSelection(day: LocalDate, pending: List<HomeNoteSummary>): HomeFlashnoteDayEntity? {
        if (pending.isEmpty()) return null
        return database.withTransaction {
            val stored = dao.flashnoteDay(day) ?: return@withTransaction null
            val index = pending.indexOfFirst { it.id == stored.selectedNoteId }
            val pin = dao.flashnotePin()
            val pinnedIndex = pending.indexOfFirst { it.id == pin?.noteId }
            val now = clock.millis()
            val next = when {
                pinnedIndex >= 0 -> pending[pinnedIndex]
                index < 0 -> pending.first()
                pending.size > 1 && now - stored.updatedAt >= FlashnoteRotationMillis -> pending[(index + 1) % pending.size]
                else -> return@withTransaction stored
            }
            if (next.id == stored.selectedNoteId) return@withTransaction stored
            if (notes.note(next.id)?.status != FlashnoteStatus.PENDING) return@withTransaction stored
            dao.selectFlashnote(day, next.id, now)
            stored.copy(selectedNoteId = next.id, updatedAt = now)
        }
    }

    private fun build(t: Tasks, h: History, from: LocalDate, through: LocalDate, today: LocalDate,
        pending: List<HomeNoteSummary>, selectedNoteId: String?): HomeTimelineData {
        val cards = linkedMapOf<LocalDate, MutableList<HomeSlot>>()
        fun add(card: HomeCard) { if (card.day in from..through) cards.getOrPut(card.day) { mutableListOf() }.add(HomeSlot(card.id, listOf(card))) }
        val leads = t.leads.groupBy { it.occurrenceId }
        val rememberedScheduleDays = h.events.filter { it.kind == HomeEventKind.SCHEDULE && !it.cancelled }
            .groupBy { it.sourceId }.mapValues { (_, events) -> events.map { it.eventDate }.toSet() }
        for (row in t.schedules) {
            val o = row.occurrence
            // 旧首页记录不能决定改期后的分组。只有仍符合当前日期、时间及提醒配置的计划，
            // 才能提供提前提醒日期；完成导致计划取消时，仅保留实际出现过的有效提醒历史。
            val reminderDays = leads[o.id].orEmpty().mapNotNull { lead ->
                if (lead.occurrenceRevision != o.revision || o.time == null) return@mapNotNull null
                val start = o.date.atTime(o.time).atZone(ZoneId.of(lead.zoneId)).toInstant().toEpochMilli()
                if (lead.triggerAt != start - lead.minutesBefore.toLong() * 60_000L) return@mapNotNull null
                val day = Instant.ofEpochMilli(lead.triggerAt).atZone(zone()).toLocalDate()
                day.takeIf { lead.reminderRevision == o.revision && lead.status != ReminderStatus.CANCELLED ||
                    day in rememberedScheduleDays[o.id].orEmpty() }
            }
            val days = (listOf(o.date) + reminderDays).distinct()
            for (day in days) add(HomeCard("schedule:${o.id}:$day", HomeSource.SCHEDULE, o.id, day, o.title,
                (if (day != o.date) "${o.date} ${o.time ?: ""} 的日程提醒\n" else o.time?.let { "$it\n" }.orEmpty()) + o.detail,
                o.completedAt != null, o.revision, row.coverRelativePath, row.coverBuiltinKey,
                sessionId = row.sessionId, schedule = row))
        }
        fun noteCard(n: HomeNoteSummary, day: LocalDate) = HomeCard("flashnote:${n.id}:$day", HomeSource.FLASHNOTE, n.id, day,
            n.title, n.excerpt, n.status == FlashnoteStatus.COMPLETED, n.revision, n.coverRelativePath)
        if (pending.isNotEmpty()) cards.getOrPut(today) { mutableListOf() }.add(HomeSlot("flashnote:$today",
            pending.map { noteCard(it, today) }, selectedNoteId ?: pending.first().id,
            pinned = h.pin?.noteId == selectedNoteId))
        // 每日选择只控制今天的未完成卡片；历史只展示原闪念的实际完成日期。
        for (n in t.notes.filter { it.status == FlashnoteStatus.COMPLETED }) {
            val day = n.completedAt?.let { Instant.ofEpochMilli(it).atZone(zone()).toLocalDate() } ?: continue
            add(noteCard(n, day))
        }
        val subscriptions = h.subscriptions.associateBy { it.record.id }
        for (event in h.events.filter { it.kind !in setOf(HomeEventKind.FLASHNOTE, HomeEventKind.SCHEDULE) && (!it.cancelled || it.completedAt != null) }) {
            val sub = if (event.kind == HomeEventKind.SUBSCRIPTION) subscriptions[event.sourceId] ?: continue else null
            if (sub != null && event.completedAt == null && (!sub.record.autoRenew || sub.record.status != SubscriptionStatus.ACTIVE)) continue
            if (sub == null && h.periods.none { it.id == event.sourceId }) continue
            add(HomeCard(event.id, if (sub != null) HomeSource.SUBSCRIPTION else HomeSource.DAYIMA, event.sourceId,
                event.eventDate, event.title, event.body, event.completedAt != null, event.revision,
                sub?.coverRelativePath, sub?.builtinKey, eventId = event.id, eventKind = event.kind))
        }
        cards.getOrPut(today) { mutableListOf() }
        return HomeTimelineData(cards.entries.sortedByDescending { it.key }.map { HomeDay(it.key, it.value) })
    }
    suspend fun selectNote(day: LocalDate, id: String) = database.withTransaction {
        val current = dao.flashnoteDay(day) ?: return@withTransaction
        if (notes.note(id)?.status != FlashnoteStatus.PENDING) return@withTransaction
        if (current.selectedNoteId != id) {
            dao.flashnotePin()?.let { dao.clearFlashnotePin(it.noteId) }
            dao.selectFlashnote(day, id, clock.millis())
        }
    }
    suspend fun setNotePinned(id: String, pinned: Boolean) = database.withTransaction {
        check(notes.note(id)?.status == FlashnoteStatus.PENDING) { "闪念已完成或删除" }
        val now = clock.millis()
        val today = LocalDate.now(clock.withZone(zone()))
        dao.insertFlashnoteDay(HomeFlashnoteDayEntity(today, Random.nextLong(), null, now))
        if (pinned) {
            dao.saveFlashnotePin(HomeFlashnotePinEntity(noteId = id, updatedAt = now))
            dao.selectFlashnote(today, id, now)
        } else if (dao.flashnotePin()?.noteId == id) {
            dao.clearFlashnotePin(id)
            // 取消固定从当前时刻重新计满一小时。
            dao.selectFlashnote(today, id, now)
        }
    }
    suspend fun detail(card: HomeCard): HomeCard? = withContext(Dispatchers.IO) {
        when (card.source) {
            HomeSource.FLASHNOTE -> notes.note(card.sourceId)?.let { card.copy(title = it.title, body = it.content,
                completed = it.status == FlashnoteStatus.COMPLETED, revision = it.revision) }
            HomeSource.SCHEDULE -> dao.schedule(card.sourceId)?.let { card.copy(title = it.occurrence.title,
                body = (it.occurrence.time?.let { time -> "$time\n" }.orEmpty()) + it.occurrence.detail,
                completed = it.occurrence.completedAt != null, revision = it.occurrence.revision,
                coverRelativePath = it.coverRelativePath, builtinKey = it.coverBuiltinKey, schedule = it) }
            else -> if (card.source == HomeSource.SUBSCRIPTION && card.eventId == null) {
                database.subscriptions().subscription(card.sourceId)?.takeIf { it.status != SubscriptionStatus.DELETED }?.let {
                    card.copy(title = it.title, body = it.notes, revision = it.revision)
                }
            } else dao.event(requireNotNull(card.eventId))?.takeIf { event ->
                (!event.cancelled || event.completedAt != null) && if (event.kind == HomeEventKind.SUBSCRIPTION)
                    database.subscriptions().subscription(event.sourceId)?.status?.let { it != SubscriptionStatus.DELETED } == true
                else database.dayimaDao().period(event.sourceId) != null
            }?.let { card.copy(title = it.title, body = it.body, completed = it.completedAt != null,
                revision = it.revision, resolvedById = it.resolvedById) }
        }
    }
    /** 原 Bot 和首页共用提醒处理入口，指定历史提醒时不替换成另一周期。 */
    suspend fun reminder(source: HomeSource, sourceId: String, eventId: String? = null): HomeCard? = withContext(Dispatchers.IO) {
        val kinds = when (source) {
            HomeSource.SUBSCRIPTION -> listOf(HomeEventKind.SUBSCRIPTION)
            HomeSource.DAYIMA -> listOf(HomeEventKind.DAYIMA_PREPARE, HomeEventKind.DAYIMA_MISSING)
            else -> return@withContext null
        }
        val candidates = if (eventId != null) listOfNotNull(dao.event(eventId))
            else kinds.flatMap { dao.sourceEvents(it, sourceId) } + dao.resolvedEvents(sourceId)
        val event = candidates.filter { it.kind in kinds && (it.sourceId == sourceId || it.resolvedById == sourceId) &&
            (eventId != null || it.eventDate <= LocalDate.now(clock.withZone(zone()))) && (!it.cancelled || it.completedAt != null) }
            .sortedWith(compareBy<HomeEventEntity> { it.completedAt != null }.thenByDescending { it.eventDate }.thenBy { it.id }).firstOrNull()
            ?: return@withContext null
        val card = HomeCard(event.id, source, event.sourceId, event.eventDate, event.title, event.body,
            event.completedAt != null, event.revision, eventId = event.id, eventKind = event.kind, resolvedById = event.resolvedById)
        detail(card)?.takeIf { current -> current.completed || source != HomeSource.SUBSCRIPTION ||
            database.subscriptions().subscription(sourceId)?.let { it.autoRenew && it.status == SubscriptionStatus.ACTIVE } == true }
    }
    suspend fun setCompleted(card: HomeCard, completed: Boolean) = withContext(Dispatchers.IO) {
        when (card.source) {
            HomeSource.SCHEDULE -> schedules.setCompleted(requireNotNull(card.sessionId), card.sourceId, completed, UUID.randomUUID().toString(), card.revision)
            HomeSource.FLASHNOTE -> if (completed) notes.complete(card.sourceId, card.revision) else notes.restore(card.sourceId, card.revision)
            else -> database.withTransaction {
                val event = dao.event(requireNotNull(card.eventId)) ?: error("提醒已不存在")
                check(event.revision == card.revision) { "提醒已更新，请重新打开" }
                require(event.kind != HomeEventKind.DAYIMA_MISSING) { "请通过补记真实开始日期处理" }
                check(!event.cancelled || event.completedAt != null) { "提醒已取消" }
                if (event.kind == HomeEventKind.SUBSCRIPTION && completed) {
                    val sub = database.subscriptions().subscription(event.sourceId)
                    check(sub?.autoRenew == true && sub.status == SubscriptionStatus.ACTIVE) { "订阅已取消提醒" }
                }
                dao.updateEvent(event.copy(completedAt = if (completed) event.completedAt ?: clock.millis() else null,
                    updatedAt = clock.millis(), revision = event.revision + 1))
            }
        }
        Unit
    }
    suspend fun recordPeriod(card: HomeCard, date: LocalDate) = database.withTransaction {
        val event = dao.event(requireNotNull(card.eventId)) ?: error("提醒已不存在")
        check(event.revision == card.revision && event.completedAt == null && !event.cancelled) { "提醒已更新，请重新打开" }
        require(event.kind == HomeEventKind.DAYIMA_MISSING)
        val origin = database.dayimaDao().period(event.sourceId) ?: error("原记录已删除")
        require(date > origin.startDate) { "本次开始日期需要晚于上次记录" }
        dayima.execute(DayimaChange.RecordPeriod(date))
        Unit
    }
}
