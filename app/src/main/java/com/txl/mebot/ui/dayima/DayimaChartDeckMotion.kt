package com.txl.mebot.ui.dayima

import kotlin.math.abs
import kotlin.math.sign

internal data class DayimaDeckPose(val x: Float, val depth: Float, val scale: Float, val alpha: Float)

/** 正负方向都交换两张卡；距离不足回位，快速松手按释放方向交换。 */
internal fun dayimaDeckTarget(fraction: Float, velocity: Float, width: Float, velocityThreshold: Float): Float {
    if (width <= 0 || !width.isFinite()) return 0f
    return when {
        abs(velocity) >= velocityThreshold -> velocity.sign
        abs(fraction) >= 0.24f -> fraction.sign
        else -> 0f
    }
}

/** 中途透明时将旧前卡移到后方；末态与交换后的静止姿态相同，不跳回另一侧。 */
internal fun dayimaDeckPose(front: Boolean, fraction: Float, width: Float, peek: Float): DayimaDeckPose {
    val t = abs(fraction).coerceIn(0f, 1f)
    val stackX = peek + width * 0.04f
    if (!front) return DayimaDeckPose(stackX * (1 - t), 1 - t, 0.96f + 0.04f * t, 1f)
    if (t <= 0.5f) return DayimaDeckPose(width * fraction, t * 2,
        1 - 0.08f * t, ((0.5f - t) / 0.2f).coerceIn(0f, 1f))
    val returning = (t - 0.5f) * 2
    return DayimaDeckPose(stackX + width * 0.12f * (1 - returning), 1f, 0.96f, returning)
}
