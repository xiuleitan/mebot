package com.txl.mebot.ui.subscriptions

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.txl.mebot.data.local.SubscriptionCycleUnit
import com.txl.mebot.data.subscriptions.*
import com.txl.mebot.ui.theme.AppTheme
import com.txl.mebot.ui.theme.MebotTheme
import java.io.File
import java.math.BigInteger
import java.time.Duration
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.android.asCoroutineDispatcher
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowChoreographer
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w393dp-h852dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(androidx.compose.ui.InternalComposeUiApi::class)
class SubscriptionSpendingScreenTest {
    @Before fun pause() { ShadowChoreographer.setPaused(true); ShadowChoreographer.setFrameDelay(Duration.ofMillis(16)) }
    @After fun resume() { ShadowChoreographer.setPaused(false) }

    @Test fun cardsHaveTwoDateLinesAndPriceBelowTitleWithLargerFontsInAllThemes() {
        for (theme in AppTheme.entries) for (scale in listOf(1f, 1.3f)) withScreen(theme, scale) { screen ->
            val title = screen.text(TITLE)
            val date = screen.text("下次扣费\n11月3日")
            val price = screen.text("US$100.00 / 月")
            val layout = screen.layout(date)
            assertEquals(2, layout.lineCount)
            assertFalse(layout.hasVisualOverflow)
            assertFalse(screen.layout(price).hasVisualOverflow)
            assertTrue(price.boundsInWindow.top > title.boundsInWindow.bottom)
            assertTrue(date.boundsInWindow.right <= price.boundsInWindow.left)
            val card = screen.nodes().first { it.config.getOrNull(SemanticsActions.OnClick)?.label == "查看${TITLE}详情" }
            assertTrue(card.boundsInWindow.height >= 76 * screen.density)
            screen.click(card); assertEquals(1, screen.details)
            screen.snapshot("subscription-spending-${theme.id}-$scale")
        }
    }

    @Test fun monthTotalOpensPerChargeCnyAndOriginalCurrencyBreakdown() = withScreen { screen ->
        assertNotNull(screen.text("¥706.97"))
        assertNotNull(screen.text("截至10月30日 · 人民币"))
        val total = screen.nodes().first { node -> node.config.getOrNull(SemanticsActions.OnClick) != null &&
            descendants(node).any { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "2026年10月" } == true } }
        screen.click(total); screen.frames(500)
        val dialog = ShadowDialog.getLatestDialog().window!!.decorView
        assertNotNull(screen.text("10月消费明细", dialog))
        assertNotNull(screen.text("美元年卡", dialog)); assertNotNull(screen.text("港元会员", dialog))
        assertNotNull(screen.text("US$100.00", dialog)); assertNotNull(screen.text("HKD 40.00", dialog))
        assertNotNull(screen.text("¥671.38", dialog)); assertNotNull(screen.text("¥34.24", dialog))
        assertNotNull(screen.text("参考汇率 2026-10-30 · 中国银行\n1美元 = 6.7138元 · 1港元 = 0.8559元", dialog))
        screen.snapshot("subscription-spending-detail", dialog)
    }

    private fun withScreen(theme: AppTheme = AppTheme.BasicDark, scale: Float = 1f, block: (Screen) -> Unit) {
        WindowRecomposerPolicy.withFactory({ view -> view.createLifecycleAwareWindowRecomposer(
            Handler(Looper.getMainLooper()).asCoroutineDispatcher() + AndroidUiFrameClock(Choreographer.getInstance())) }) {
            val screen = Screen(theme, scale)
            try { block(screen) } finally { screen.destroy() }
        }
    }

    private class Screen(theme: AppTheme, scale: Float) {
        private val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        private val decor get() = controller.get().window.decorView
        val density get() = controller.get().resources.displayMetrics.density
        var details = 0
        init {
            val today = LocalDate.of(2026, 10, 30)
            val entries = listOf(SubscriptionExpense("a", "美元年卡", "purchase:a", today.minusDays(2), 10000, "USD"),
                SubscriptionExpense("b", "港元会员", "cycle:0", today, 4000, "HKD"),
                SubscriptionExpense("c", "人民币会员", "cycle:0", today, 135, "CNY"))
            val spending = SubscriptionSpending(today, SubscriptionMonthSpending(YearMonth.from(today), entries, BigInteger.valueOf(70697), emptyList()),
                SubscriptionMonthSpending(YearMonth.from(today).minusMonths(1), emptyList(), BigInteger.ZERO, emptyList()))
            val card = SubscriptionValues(TITLE, 10000, "USD", SubscriptionCycleUnit.MONTH, 1,
                anchorDate = today.plusDays(4), nextBillingDate = today.plusDays(4), autoRenew = true).entity("record", 1, 1, 1)
            controller.get().setContent { MebotTheme(theme) {
                CompositionLocalProvider(LocalDensity provides Density(density, scale)) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                            SubscriptionSummary(SubscriptionsState(today = today, loading = false, spendingLoading = false, spending = spending), 3, {})
                            SubscriptionCard(card, null, { details++ })
                        }
                    }
                }
            } }
            frames(700)
        }
        fun frames(ms: Long) { repeat(((ms + 15) / 16).toInt()) {
            Snapshot.sendApplyNotifications(); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16)); measure(decor)
            ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }?.window?.decorView?.let(::measure)
            Thread.sleep(2)
        } }
        fun nodes(view: View = decor) = views(view).filter { it.javaClass.name == "androidx.compose.ui.platform.AndroidComposeView" }.flatMap {
            descendants((it.javaClass.getMethod("getSemanticsOwner").invoke(it) as SemanticsOwner).unmergedRootSemanticsNode)
        }.toList()
        fun text(value: String, view: View = decor) = nodes(view).first { it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == value } == true }
        fun layout(node: SemanticsNode): TextLayoutResult {
            val layouts = mutableListOf<TextLayoutResult>()
            assertTrue(requireNotNull(node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action).invoke(layouts))
            return layouts.single()
        }
        fun click(node: SemanticsNode) { assertTrue(requireNotNull(node.config.getOrNull(SemanticsActions.OnClick)?.action).invoke()) }
        fun snapshot(name: String, view: View = decor) {
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888); view.draw(Canvas(bitmap))
            File("build/reports/subscription-preview/$name.png").apply { parentFile!!.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        fun destroy() {
            ShadowDialog.getLatestDialog()?.dismiss(); frames(100)
            controller.pause().stop().destroy(); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        }
    }
    companion object {
        private const val TITLE = "年度会员 · 长标题内容"
        private fun views(view: View): Sequence<View> = sequence { yield(view); if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(views(view.getChildAt(i))) }
        private fun measure(view: View) { views(view).filter { it.javaClass.name == "androidx.compose.ui.platform.AndroidComposeView" }.forEach { it.javaClass.getMethod("measureAndLayoutForTest").invoke(it) } }
        private fun descendants(node: SemanticsNode): Sequence<SemanticsNode> = sequence { yield(node); for (child in node.children) yieldAll(descendants(child)) }
    }
}
