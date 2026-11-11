package io.github.glacier_jellyfin.androidtv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    private val minute = 60_000L
    private val full = 60_000L

    /** Started at 4K, moved down to 720p at time 0. */
    private fun lowered(): TierController = controller(2).also {
        it.limitTo(1_000_000, 0)
        it.limitTo(1_000_000, 40_000)
    }

    @Test
    fun `a better link moves up when buffer, margin and pause allow`() {
        val c = lowered()
        assertEquals(30_000_000, c.raiseTo(12_000_000, full, 40_000 + 10 * minute))
        assertEquals(1, c.index)
    }

    @Test
    fun `a move up needs a full buffer`() {
        val c = lowered()
        assertNull(c.raiseTo(100_000_000, full - 1, 40_000 + 10 * minute))
        assertEquals(0, c.index)
    }

    @Test
    fun `a move up needs one and a half times what the bigger tier asks`() {
        val c = lowered()
        assertNull(c.raiseTo(11_999_999, full, 40_000 + 10 * minute))
        assertEquals(30_000_000, c.raiseTo(12_000_000, full, 40_000 + 10 * minute))
    }

    @Test
    fun `a move up waits ten minutes after the last move`() {
        val c = lowered()
        assertNull(c.raiseTo(100_000_000, full, 40_000 + 10 * minute - 1))
        assertEquals(30_000_000, c.raiseTo(100_000_000, full, 40_000 + 10 * minute))
        // The move up counts as a move: the next one waits again,.
        assertNull(c.raiseTo(100_000_000, full, 40_000 + 19 * minute))
        assertEquals(60_000_000, c.raiseTo(100_000_000, full, 40_000 + 20 * minute))
    }

    @Test
    fun `a move up never goes above the top tier`() {
        val c = controller(2)
        assertNull(c.raiseTo(500_000_000, full, 100 * minute))
        assertEquals(2, c.index)
        val d = lowered()
        d.raiseTo(500_000_000, full, 40_000 + 10 * minute)
        d.raiseTo(500_000_000, full, 40_000 + 20 * minute)
        assertNull(d.raiseTo(500_000_000, full, 40_000 + 30 * minute))
        assertEquals(2, d.index)
    }

    @Test
    fun `a stream that started low climbs past its start tier up to the top`() {
        val c = controller(0)
        assertEquals(30_000_000, c.raiseTo(500_000_000, full, 0))
        assertEquals(60_000_000, c.raiseTo(500_000_000, full, 10 * minute))
        assertNull(c.raiseTo(500_000_000, full, 20 * minute))
        assertEquals(2, c.index)
    }

    @Test
    fun `a link that is too slow moves down after a move up`() {
        val c = lowered()
        c.raiseTo(100_000_000, full, 40_000 + 10 * minute)
        assertEquals(10_000_000, c.limitTo(1_000_000, 40_000 + 10 * minute + 30_000))
    }

    @Test
    fun `two slow segments in a row raise the alarm`() {
        val s = SlowLoads()
        assertFalse(s.record(11_000, 10_000, 0))
        assertTrue(s.record(10_001, 10_000, 1_000))
    }

    @Test
    fun `a segment that is in time resets the count`() {
        val s = SlowLoads()
        assertFalse(s.record(11_000, 10_000, 0))
        assertFalse(s.record(10_000, 10_000, 1_000))
        assertFalse(s.record(11_000, 10_000, 2_000))
        assertTrue(s.record(11_000, 10_000, 3_000))
    }

    @Test
    fun `the alarm comes at most every thirty seconds`() {
        val s = SlowLoads()
        s.record(11_000, 10_000, 0)
        assertTrue(s.record(11_000, 10_000, 1_000))
        assertFalse(s.record(11_000, 10_000, 2_000))
        assertFalse(s.record(11_000, 10_000, 30_999))
        assertTrue(s.record(11_000, 10_000, 31_000))
    }
}
