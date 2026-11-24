package com.txl.mebot.ui.common

import org.junit.Test
import org.junit.Assert.*

class HapticGateTest {
    @Test fun overlappingFeedbackIsCoalescedWithoutBlockingLaterActionsOrAResetClock() {
        var now = 100L
        val gate = HapticGate { now }
        assertTrue(gate.allow()); assertFalse(gate.allow())
        now = 149; assertFalse(gate.allow())
        now = 150; assertTrue(gate.allow())
        now = 300; assertTrue(gate.allow())
        now = 0; assertTrue(gate.allow())
    }
}
