package io.github.glacier_jellyfin.androidtv.trailer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrailerStartTest {

    @Test
    fun `local trailers time out after the plain 20 seconds`() {
        assertEquals(1_000 + 20_000L, TrailerStart.deadline(1_000, 15_000, isYouTube = false))
        assertFalse(TrailerStart.timedOut(1_000, 15_000, 20_999, isYouTube = false))
        assertTrue(TrailerStart.timedOut(1_000, 15_000, 21_000, isYouTube = false))
    }

    @Test
    fun `youtube without a sign of life times out after 20 seconds`() {
        assertFalse(TrailerStart.timedOut(0, 0, 19_999, isYouTube = true))
        assertTrue(TrailerStart.timedOut(0, 0, 20_000, isYouTube = true))
    }

    @Test
    fun `a sign of life extends the youtube deadline`() {
        assertEquals(30_000L, TrailerStart.deadline(0, 10_000, isYouTube = true))
        assertFalse(TrailerStart.timedOut(0, 10_000, 29_999, isYouTube = true))
        assertTrue(TrailerStart.timedOut(0, 10_000, 30_000, isYouTube = true))
    }

    @Test
    fun `the youtube deadline never passes the hard cap`() {
        assertEquals(45_000L, TrailerStart.deadline(0, 40_000, isYouTube = true))
        assertTrue(TrailerStart.timedOut(0, 40_000, 45_000, isYouTube = true))
    }

    @Test
    fun `the playing state means started`() {
        assertTrue(TrailerStart.started(0, 0, 1))
    }

    @Test
    fun `an advancing position means started`() {
        assertTrue(TrailerStart.started(0, 500, 3))
        assertTrue(TrailerStart.started(500, 1_000, -1))
    }

    @Test
    fun `the same or a zero position does not mean started`() {
        assertFalse(TrailerStart.started(0, 0, 3))
        assertFalse(TrailerStart.started(1_000, 1_000, 3))
    }

    @Test
    fun `a position going backwards does not mean started`() {
        assertFalse(TrailerStart.started(2_000, 1_000, 5))
    }
}
