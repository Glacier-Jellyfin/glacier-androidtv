package io.github.glacier_jellyfin.androidtv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TierControllerTest {

    /** 720p, 1080p and 4K as the player lists them, and what each asks of the link. */
    private val tiers = listOf(10_000_000, 30_000_000, 60_000_000)
    private val expected = listOf(2_500_000, 8_000_000, 25_000_000)

    private fun controller(start: Int) = TierController(tiers, expected, start)

    @Test
    fun `starts at the biggest tier the budget carries`() {
        assertEquals(2, TierController.startFor(expected, 30_000_000))
        assertEquals(1, TierController.startFor(expected, 10_000_000))
        assertEquals(0, TierController.startFor(expected, 1_000_000))
    }

    @Test
    fun `a link that carries the tier leaves it alone`() {
        val c = controller(1)
        assertNull(c.limitTo(10_000_000, 0))
        assertNull(c.limitTo(500_000_000, 100_000))
        assertEquals(1, c.index)
    }

    @Test
    fun `a link that no longer carries the tier moves it down a step`() {
        val c = controller(2)
        assertEquals(30_000_000, c.limitTo(10_000_000, 0))
        assertEquals(1, c.index)
    }

    @Test
    fun `a move waits for the transcode it started`() {
        val c = controller(2)
        assertEquals(30_000_000, c.limitTo(1_000_000, 0))
        assertNull(c.limitTo(1_000_000, 10_000))
        assertEquals(10_000_000, c.limitTo(1_000_000, 40_000))
        assertNull(c.limitTo(1_000_000, 100_000))
    }

    @Test
    fun `a better link does not move up`() {
        val c = controller(0)
        assertNull(c.limitTo(500_000_000, 100_000))
        assertEquals(0, c.index)
    }
}
