package com.txl.mebot.data

import android.app.Application
import com.txl.mebot.data.local.*
import com.txl.mebot.data.subscriptions.SubscriptionDates
import com.txl.mebot.data.subscriptions.SubscriptionValues
import com.txl.mebot.ui.subscriptions.SubscriptionEditor
import com.txl.mebot.ui.subscriptions.billingDateText
import com.txl.mebot.ui.subscriptions.dateSummary
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SubscriptionEditorTest {
    private val jan31 = LocalDate.of(2026, 1, 31)
    private fun record() = SubscriptionValues("云盘", 2500, "CNY", SubscriptionCycleUnit.MONTH, 1,
        anchorDate = jan31, nextBillingDate = LocalDate.of(2026, 2, 28), autoRenew = true).entity("subscription", 1, 2, 3)

    @Test fun invalidUnsentFieldsAndRequestIdentityRoundTripWithoutLosingRawInput() {
        val input = SubscriptionEditor("conversation", title = " 尚未确认 ", amount = "25.0x", currency = "???", next = "不完整日期", notes = "多行\n备注")
        assertEquals(input, SubscriptionEditor.decode(input.json()))
        assertTrue(runCatching { input.values() }.isFailure)
        assertEquals("25.0x", input.amount)
    }
    @Test fun editingNotesAfterFebruaryClampPreservesThe31AnchorEvenWithWhitespaceInTheDate() {
        val editor = SubscriptionEditor.from(record(), "conversation", LocalDate.of(2026, 2, 1)).copy(notes = "补充", next = " 2026-02-28 ")
        val decoded = SubscriptionEditor.decode(editor.json())
        assertEquals(jan31, decoded.values().anchorDate)
        assertEquals(LocalDate.of(2026, 3, 31), SubscriptionDates.after(decoded.values().anchorDate!!,
            decoded.unit, 1, false, LocalDate.of(2026, 2, 28)))
    }
    @Test fun explicitNewBillingDateResetsTheAnchorWhilePreservingOtherFields() {
        val editor = SubscriptionEditor.from(record(), "conversation", LocalDate.of(2026, 2, 1)).copy(next = "2026-03-05")
        assertEquals(LocalDate.of(2026, 3, 5), editor.values().anchorDate)
        assertEquals(2500L, editor.values().amountMinor)
        assertEquals(3L, editor.expectedRevision)
    }
    @Test fun restoringAnArchivedSubscriptionSuggestsItsNextCycleWithoutLosingOriginalDay31() {
        val archived = record().copy(status = SubscriptionStatus.ARCHIVED, autoRenew = false, nextBillingDate = jan31)
        val editor = SubscriptionEditor.from(archived, "conversation", LocalDate.of(2026, 2, 1)).copy(autoRenew = true, validUntil = "")
        assertEquals("2026-02-28", editor.next)
        assertEquals(jan31, editor.values().anchorDate)
        assertEquals(SubscriptionStatus.ACTIVE, editor.values().status)
    }
    @Test fun nonRecurringInputDoesNotAccidentallyCreateAutomaticBillingFromAnOldHiddenDate() {
        val editor = SubscriptionEditor("conversation", title = "永久许可", amount = "100", currency = "JPY",
            unit = SubscriptionCycleUnit.NONE, next = "错误但未使用的旧日期", autoRenew = false)
        val values = editor.values()
        assertFalse(values.autoRenew)
        assertNull(values.nextBillingDate)
        assertNull(values.anchorDate)
        assertEquals(0, values.cycleInterval)
    }
    @Test fun closedRenewalAndItsOwnExpiryRemainSeparateFromFutureBillingDate() {
        val record = record().copy(status = SubscriptionStatus.CANCEL_AT_PERIOD_END, autoRenew = false,
            anchorDate = LocalDate.of(2026, 11, 1), nextBillingDate = LocalDate.of(2026, 11, 1), validUntil = LocalDate.of(2026, 10, 31))
        val editor = SubscriptionEditor.from(record, "conversation", LocalDate.of(2026, 10, 26))
        assertEquals(SubscriptionStatus.CANCEL_AT_PERIOD_END, editor.values().status)
        assertEquals(LocalDate.of(2026, 10, 31), editor.values().validUntil)
        assertEquals(record.nextBillingDate, editor.copy(notes = "只改备注").values().nextBillingDate)
        assertEquals(SubscriptionStatus.ACTIVE, editor.copy(autoRenew = true).values().status)
    }
    @Test fun cancellingMonthlyOrYearlyRenewalPreservesValidityAndShowsCancelledInsteadOfAnotherCharge() {
        for (unit in listOf(SubscriptionCycleUnit.MONTH, SubscriptionCycleUnit.YEAR)) {
            val next = LocalDate.of(if (unit == SubscriptionCycleUnit.YEAR) 2027 else 2026, 11, 7)
            val original = record().copy(cycleUnit = unit, anchorDate = next, nextBillingDate = next, validUntil = next.minusDays(1))
            val editor = SubscriptionEditor.from(original, "chat", LocalDate.of(2026, 10, 28)).copy(autoRenew = false)
            val saved = editor.values().entity("saved", 1, 2, 4)
            assertEquals(SubscriptionStatus.CANCEL_AT_PERIOD_END, saved.status)
            assertEquals(next.minusDays(1), saved.validUntil)
            assertEquals(next, saved.nextBillingDate)
            assertEquals("已取消续费", saved.billingDateText(LocalDate.of(2026, 10, 28)))
            assertEquals("已取消续费", saved.dateSummary(LocalDate.of(2026, 10, 28)))
        }
    }

}
