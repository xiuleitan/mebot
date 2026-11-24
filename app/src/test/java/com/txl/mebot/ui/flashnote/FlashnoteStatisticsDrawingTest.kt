package com.txl.mebot.ui.flashnote

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FlashnoteStatisticsDrawingTest {
    @Test fun collapsedButtonIsAnEvenCircleWithoutStrayLinesAcrossSizesAndDensities() {
        for ((widthDp, headerDp, scale) in listOf(
            Triple(328f, 48f, 1f), Triple(329f, 48f, 1f),
            Triple(328f, 48f, 2.625f), Triple(328f, 80f, 2f),
        )) {
            val width = (widthDp * scale).roundToInt()
            val header = headerDp * scale
            val bitmap = Bitmap.createBitmap(width, ceil(header).toInt(), Bitmap.Config.ARGB_8888)
            try {
                render(bitmap, Density(scale), header, 0f)
                val cx = width / 2f
                val cy = header / 2f
                val radius = 24f * scale
                for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                    val dx = x + 0.5f - cx
                    val dy = y + 0.5f - cy
                    if (dx * dx + dy * dy > (radius + 1f) * (radius + 1f)) {
                        assertEquals("Stray pixel at ($x, $y), density $scale", 0, bitmap.getPixel(x, y))
                    }
                }
                assertEquals(Container.toArgb(), bitmap.getPixel(cx.toInt(), cy.toInt()))
                assertSymmetricSilhouette(bitmap)
                // 四个方向的边缘都应有可见描边，而不是被边框缓存裁掉一侧。
                for ((x, y) in listOf(
                    cx.toInt() to (cy - radius + scale / 2).toInt(),
                    cx.toInt() to (cy + radius - scale / 2).toInt(),
                    (cx - radius + scale / 2).toInt() to cy.toInt(),
                    (cx + radius - scale / 2).toInt() to cy.toInt(),
                )) {
                    val pixel = bitmap.getPixel(x, y)
                    // 半像素圆心会将 1 dp 描边分摊到相邻像素，仍应明显深于底色。
                    assertTrue("Missing border at ($x, $y): ${pixel.toUInt().toString(16)}",
                        android.graphics.Color.alpha(pixel) > 127 && android.graphics.Color.red(pixel) < 170)
                }
            } finally { bitmap.recycle() }
        }
    }

    @Test fun animatedContourRemainsSymmetricAndClosingLeavesNoOldChartPixels() {
        val bitmap = Bitmap.createBitmap(328, 360, Bitmap.Config.ARGB_8888)
        try {
            val scope = CanvasDrawScope()
            render(bitmap, Density(1f), 48f, 0f, scope = scope)
            val original = bitmap.pixels()
            for (p in listOf(0.001f, 0.05f, 0.4f, 0.8f, 1f, 0.7f, 0.1f, 0f, 1f, 0f)) {
                render(bitmap, Density(1f), 48f, p, scope = scope)
                assertSymmetricSilhouette(bitmap)
                if (p == 0f) assertArrayEquals("Closing must restore the original circle", original, bitmap.pixels())
            }
        } finally { bitmap.recycle() }
    }

    @Test fun collapsedBorderKeepsItsThemeColorInBothDirections() {
        val bitmap = Bitmap.createBitmap(328, 48, Bitmap.Config.ARGB_8888)
        try {
            for (direction in listOf(LayoutDirection.Ltr, LayoutDirection.Rtl)) {
                render(bitmap, Density(1f), 48f, 0f, direction = direction, container = Color(0xFF303B49), border = Color(0xFFF2F2F2))
                assertEquals(Color(0xFF303B49).toArgb(), bitmap.getPixel(164, 24))
                assertTrue(android.graphics.Color.red(bitmap.getPixel(164, 0)) > 200)
                assertSymmetricSilhouette(bitmap)
            }
        } finally { bitmap.recycle() }
    }

    private fun render(
        bitmap: Bitmap, density: Density, header: Float, progress: Float,
        scope: CanvasDrawScope = CanvasDrawScope(), direction: LayoutDirection = LayoutDirection.Ltr,
        container: Color = Container, border: Color = Border,
    ) {
        bitmap.eraseColor(android.graphics.Color.TRANSPARENT)
        val height = header + 300f * progress
        val size = Size(bitmap.width.toFloat(), height)
        val path = (ConnectedStatisticsShape(header, progress).createOutline(size, direction, density) as Outline.Generic).path
        scope.draw(density, direction, Canvas(bitmap.asImageBitmap()), size) {
            drawStatisticsContainer(path, container, border, density.density)
        }
    }

    private fun assertSymmetricSilhouette(bitmap: Bitmap) {
        for (y in 0 until bitmap.height) {
            val left = (0 until bitmap.width).firstOrNull { android.graphics.Color.alpha(bitmap.getPixel(it, y)) > 127 } ?: continue
            val right = (bitmap.width - 1 downTo 0).first { android.graphics.Color.alpha(bitmap.getPixel(it, y)) > 127 }
            // Skia 的左右抗锯齿覆盖率允许一像素取整差，不能出现错位的另一圈边框。
            assertTrue("Asymmetric contour at row $y: $left..$right", abs(left - (bitmap.width - 1 - right)) <= 1)
        }
    }

    private fun Bitmap.pixels() = IntArray(width * height).also { getPixels(it, 0, width, 0, 0, width, height) }

    private companion object {
        val Container = Color(0xFFE3EAF3)
        val Border = Color(0xFF1A1A1A)
    }
}
