package io.github.glacier_jellyfin.androidtv.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlurredBackdropTest {
    @Test
    fun `a tall picture is portrait and a wide one is not`() {
        assertTrue(isPortrait(1080, 1920))
        assertFalse(isPortrait(1920, 1080))
        assertFalse(isPortrait(1440, 1080))
        assertFalse(isPortrait(1080, 1080))
    }

    @Test
    fun `a quarter turn swaps the sides`() {
        assertTrue(isPortrait(1920, 1080, rotationDegrees = 90))
        assertTrue(isPortrait(1920, 1080, rotationDegrees = 270))
        assertTrue(isPortrait(1920, 1080, rotationDegrees = -90))
        assertFalse(isPortrait(1080, 1920, rotationDegrees = 90))
    }

    @Test
    fun `a half turn or an odd angle leaves the sides`() {
        assertTrue(isPortrait(1080, 1920, rotationDegrees = 180))
        assertFalse(isPortrait(1920, 1080, rotationDegrees = 180))
        assertFalse(isPortrait(1920, 1080, rotationDegrees = 45))
    }

    @Test
    fun `the shape of the pixels counts`() {
        // 720 x 1280 with wide pixels shows as 1080 x 1280, still tall, and as 1440 x 1280 once they are twice as wide.
        assertTrue(isPortrait(720, 1280, pixelWidthHeightRatio = 1.5f))
        assertFalse(isPortrait(720, 1280, pixelWidthHeightRatio = 2f))
    }

    @Test
    fun `an unknown size is not portrait`() {
        assertFalse(isPortrait(0, 1920))
        assertFalse(isPortrait(1080, 0))
        assertFalse(isPortrait(-1, -1))
    }

    @Test
    fun `the soft decode has its own cache key`() {
        assertEquals("backdrop:http://x/y", softCacheKey("http://x/y"))
    }

    @Test
    fun `a box blur keeps a flat picture and smooths an edge`() {
        val flat = IntArray(16) { 0xFF336699.toInt() }
        val out = IntArray(16)
        boxPass(flat, out, Axis(lines = 4, length = 4, step = 1, lineStep = 4), radius = 1)
        assertEquals(flat.toList(), out.toList())
        val edge = intArrayOf(0xFF000000.toInt(), 0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt())
        val smooth = IntArray(4)
        boxPass(edge, smooth, Axis(lines = 1, length = 4, step = 1, lineStep = 4), radius = 1)
        assertEquals(0xFF, smooth[0] ushr 24)
        assertEquals(0x55, smooth[1] and 0xFF)
        assertEquals(0xAA, smooth[2] and 0xFF)
    }
}
