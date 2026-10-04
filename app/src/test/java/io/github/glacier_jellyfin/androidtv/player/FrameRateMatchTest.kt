package io.github.glacier_jellyfin.androidtv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrameRateMatchTest {

    // The modes a Fire TV Stick 4K Max reports on a 1080p TV.
    private val fireTv = listOf(
        DisplayModeSpec(1, 1920, 1080, 60.000004f),
        DisplayModeSpec(2, 1920, 1080, 59.94f),
        DisplayModeSpec(3, 1280, 720, 59.94f),
        DisplayModeSpec(4, 1920, 1080, 50f),
        DisplayModeSpec(5, 1920, 1080, 29.97f),
        DisplayModeSpec(6, 1920, 1080, 23.976f),
        DisplayModeSpec(7, 1920, 1080, 24f),
        DisplayModeSpec(8, 1920, 1080, 25f),
        DisplayModeSpec(9, 1920, 1080, 30f),
        DisplayModeSpec(10, 1280, 720, 50f),
    )
    private val at5994 = fireTv[1]

    @Test
    fun `films get their exact rate`() {
        assertEquals(6, pickDisplayMode(fireTv, at5994, 23.976025f)?.id)
        assertEquals(7, pickDisplayMode(fireTv, at5994, 24f)?.id)
    }

    @Test
    fun `the fastest even mode wins`() {
        assertEquals(4, pickDisplayMode(fireTv, at5994, 25f)?.id)
        assertEquals(1, pickDisplayMode(fireTv, at5994, 30f)?.id)
    }

    @Test
    fun `a mode that already fits stays`() {
        assertNull(pickDisplayMode(fireTv, at5994, 29.97f))
        assertNull(pickDisplayMode(fireTv, at5994, 59.94f))
    }

    @Test
    fun `other resolutions are never picked`() {
        val modes = listOf(at5994, DisplayModeSpec(20, 3840, 2160, 23.976f))
        assertNull(pickDisplayMode(modes, at5994, 23.976f))
    }

    @Test
    fun `a near rate beats judder when no exact one exists`() {
        val modes = listOf(at5994, DisplayModeSpec(7, 1920, 1080, 24f))
        assertEquals(7, pickDisplayMode(modes, at5994, 23.976f)?.id)
    }

    @Test
    fun `unknown rates change nothing`() {
        assertNull(pickDisplayMode(fireTv, at5994, 0f))
    }
}
