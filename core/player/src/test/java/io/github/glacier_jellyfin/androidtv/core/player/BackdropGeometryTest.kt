package io.github.glacier_jellyfin.androidtv.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackdropGeometryTest {
    @Test
    fun outputIsSixteenByNineOfTheInputHeight() {
        assertEquals(3414 to 1920, BackdropGeometry.outputSize(1080, 1920))
        assertEquals(1280 to 720, BackdropGeometry.outputSize(720, 720))
    }

    @Test
    fun wideInputKeepsItsOwnWidth() {
        assertEquals(2560 to 1080, BackdropGeometry.outputSize(2560, 1080))
    }

    @Test
    fun blurTextureIsAboutAEighthAndClamped() {
        assertEquals(240 to 135, BackdropGeometry.blurSize(1920, 1080))
        assertEquals(320 to 180, BackdropGeometry.blurSize(7680, 4320))
        assertEquals(192, BackdropGeometry.blurSize(640, 360).first)
    }

    @Test
    fun coverCropsHeightWithoutStretching() {
        val w = BackdropGeometry.coverWindow(1080, 1920, 3414, 1920)
        assertEquals(1f, w.du, 0.001f)
        assertEquals(0.3164f, w.dv, 0.001f)
        assertEquals((1f - w.dv) / 2f, w.v0, 0.0001f)
        // The window has the frame's aspect: 1080 x (0.3164 * 1920) = 16:9.
        assertEquals(16f / 9f, 1080f / (w.dv * 1920f), 0.01f)
    }

    @Test
    fun sharpVideoKeepsItsAspectAtFullHeight() {
        val e = BackdropGeometry.sharpExtent(1080, 1920, 3414, 1920)
        assertEquals(1f, e.halfHeight, 0f)
        assertEquals(1080f / 3414f, e.halfWidth, 0.001f)
    }

    @Test
    fun kernelIsNormalisedAndWithinTheRadius() {
        val (centre, taps) = BackdropGeometry.blurKernel(4f, 8)
        assertEquals(4, taps.size)
        assertEquals(1f, centre + 2f * taps.sumOf { it.weight.toDouble() }.toFloat(), 0.001f)
        assertTrue(taps.all { it.offset in 1f..8f })
        assertTrue(taps.zipWithNext().all { (a, b) -> a.offset < b.offset && a.weight > b.weight })
    }
}
