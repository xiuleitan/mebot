package com.txl.mebot.ui.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.txl.mebot.MebotApplication
import com.txl.mebot.data.home.*
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class HomePageState(val today: LocalDate = LocalDate.now(), val timeline: HomeTimelineData = HomeTimelineData(),
    val loading: Boolean = true, val busy: Boolean = false, val detail: HomeCard? = null, val notice: String? = null,
    val error: String? = null)

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as MebotApplication).container
    private val repository = container.home
    private val mutable = MutableStateFlow(HomePageState())
    val state = mutable.asStateFlow()
    private var from = LocalDate.now().minusDays(29)
    private var collection: Job? = null
    private var ticker: Job? = null
    private var detailRequest = 0L
    private var zone = ZoneId.systemDefault()
    fun visible(value: Boolean) {
        if (!value) { collection?.cancel(); ticker?.cancel(); collection = null; ticker = null; return }
        if (collection == null) refresh()
        ticker?.cancel()
        ticker = viewModelScope.launch {
            while (isActive) {
                delay(30_000)
                if (LocalDate.now() != mutable.value.today || ZoneId.systemDefault() != zone) {
                    container.refreshReminders()
                    from = LocalDate.now().minusDays(29); refresh()
                }
            }
        }
    }
    fun refresh() {
        collection?.cancel()
        zone = ZoneId.systemDefault()
        val today = LocalDate.now()
        mutable.update { it.copy(today = today, loading = true, error = null) }
        collection = viewModelScope.launch {
            try {
                // 每批最多展开一个月；历史继续按需加载，不在启动时读取全部媒体正文。
                repository.prepare(from, today.plusDays(1), today)
                repository.observe(from, today.plusDays(1), today).collect { data ->
                    mutable.update { it.copy(timeline = data, loading = false, error = null) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.update { it.copy(loading = false, error = "首页暂未读取成功，请重试") } }
        }
    }
    fun more() { if (!mutable.value.loading) { from = from.minusDays(30); refresh() } }
    fun selectNote(day: LocalDate, id: String) { viewModelScope.launch {
        try { withContext(Dispatchers.IO) { repository.selectNote(day, id) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutable.update { it.copy(notice = "闪念选择未能保存，请重试") } }
    } }
    fun pinNote(id: String, pinned: Boolean) { viewModelScope.launch {
        try { withContext(Dispatchers.IO) { repository.setNotePinned(id, pinned) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutable.update { it.copy(notice = "闪念固定状态未能保存，请重试") } }
    } }
    fun open(card: HomeCard) {
        if (mutable.value.busy) return
        val request = ++detailRequest
        action {
            val detail = repository.detail(card)
            if (request != detailRequest) return@action
            mutable.update { it.copy(detail = detail, notice = if (detail == null) "这条记录已删除" else null) }
        }
    }
    fun openEvent(id: String) { viewModelScope.launch {
        try {
            val e = container.database.home().event(id) ?: return@launch
            if (e.cancelled && e.completedAt == null) { mutable.update { it.copy(notice = "这条提醒已取消") }; return@launch }
            val source = if (e.kind == com.txl.mebot.data.local.HomeEventKind.SUBSCRIPTION) HomeSource.SUBSCRIPTION else HomeSource.DAYIMA
            open(HomeCard(e.id, source, e.sourceId, e.eventDate, e.title, e.body, e.completedAt != null, e.revision,
                eventId = e.id, eventKind = e.kind))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutable.update { it.copy(notice = "提醒暂未读取成功") } }
    } }
    fun close() { detailRequest++; mutable.update { it.copy(detail = null) } }
    fun dismissNotice() { mutable.update { it.copy(notice = null) } }
    private fun action(block: suspend () -> Unit) {
        if (mutable.value.busy) return
        mutable.update { it.copy(busy = true) }
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { block() } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutable.update { it.copy(notice = error.message?.takeIf { s -> s.length < 100 } ?: "操作未成功，请重试") } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
}
