package io.github.glacier_jellyfin.androidtv.core.data.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamBudgetTest {

    @Test
    fun `a stream keeps headroom on the measured link`() {
        assertEquals(16_150_000, StreamBudget.of(19_000_000))
    }

    @Test
    fun `a 20 Mbit file does not fit a 19 Mbit link`() {
        assertTrue(StreamBudget.exceeds(20_000_000, StreamBudget.of(19_000_000)))
    }

    @Test
    fun `a fast link leaves the file alone`() {
        assertFalse(StreamBudget.exceeds(60_000_000, StreamBudget.of(500_000_000)))
    }

    @Test
    fun `the budget stays within floor and the highest step`() {
        assertEquals(StreamBudget.FLOOR, StreamBudget.of(100_000))
        assertEquals(StreamBudget.CEILING, StreamBudget.of(10_000_000_000))
    }

    @Test
    fun `unknown values never exceed`() {
        assertFalse(StreamBudget.exceeds(null, 10_000_000))
        assertFalse(StreamBudget.exceeds(20_000_000, null))
    }

    @Test
    fun `a stall lowers the budget below the stream that stalled`() {
        assertEquals(14_000_000, StreamBudget.afterStall(20_000_000, null))
        assertEquals(10_000_000, StreamBudget.afterStall(20_000_000, 10_000_000))
        assertEquals(StreamBudget.FLOOR, StreamBudget.afterStall(2_000_000, null))
    }

    @Test
    fun `a 4K file on a 4K display has all three tiers`() {
        assertEquals(listOf(2160, 1080, 720), StreamBudget.resolutions(2160, 2160).map { it.height })
    }

    @Test
    fun `a 4K file on a 1080p display has no tier bigger than the display`() {
        assertEquals(listOf(1080, 720), StreamBudget.resolutions(1080, 2160).map { it.height })
    }

    @Test
    fun `a 1080p file on a 4K display has no tier bigger than the file`() {
        assertEquals(listOf(1080, 720), StreamBudget.resolutions(2160, 1080).map { it.height })
    }

    @Test
    fun `a small file has one tier or none`() {
        assertEquals(listOf(720), StreamBudget.resolutions(1080, 720).map { it.height })
        assertTrue(StreamBudget.resolutions(1080, 480).isEmpty())
    }

    @Test
    fun `a bigger picture asks more of the link`() {
        assertTrue(StreamBudget.expectedBitrate(720) < StreamBudget.expectedBitrate(1080))
        assertTrue(StreamBudget.expectedBitrate(1080) < StreamBudget.expectedBitrate(2160))
    }

    @Test
    fun `a reduced stream returns only with room to spare`() {
        assertFalse(StreamBudget.returnsTo(20_000_000, 21_000_000))
        assertTrue(StreamBudget.returnsTo(20_000_000, 23_000_000))
    }
}
