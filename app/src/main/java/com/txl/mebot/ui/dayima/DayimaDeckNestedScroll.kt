package com.txl.mebot.ui.dayima

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity

/** 柱图到达横向边缘后将剩余手势交给翻卡；一次手势接手后保持归属，松手只结算一次。 */
internal class DayimaDeckNestedScroll(
    private val onStart: () -> Unit,
    private val onDrag: (Float) -> Unit,
    private val onStop: (Float) -> Unit,
) : NestedScrollConnection {
    private var dragging = false

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
        if (dragging && source == NestedScrollSource.UserInput) consume(available.x) else Offset.Zero

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        if (source != NestedScrollSource.UserInput || available.x == 0f) return Offset.Zero
        if (!dragging) {
            dragging = true
            onStart()
        }
        return consume(available.x)
    }

    override suspend fun onPreFling(available: Velocity): Velocity = finish(available)

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity = finish(available)

    private fun consume(delta: Float): Offset {
        if (delta == 0f) return Offset.Zero
        onDrag(delta)
        return Offset(delta, 0f)
    }

    private fun finish(available: Velocity): Velocity {
        if (!dragging) return Velocity.Zero
        dragging = false
        onStop(available.x)
        return Velocity(available.x, 0f)
    }
}
