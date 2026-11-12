package io.github.glacier_jellyfin.androidtv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HandoverPlannerTest {

    @Test
    fun `a full buffer gives the longest lead`() {
        val plan = HandoverPlanner.lower(HandoverTrigger.Link, aheadMs = 120_000, positionMs = 600_000, durationMs = 7_200_000)
        assertEquals(HandoverPlan.At(690_000, 90_000), plan)
    }

    @Test
    fun `lead is the buffer minus the reserve`() {
        val plan = HandoverPlanner.lower(HandoverTrigger.Draining, aheadMs = 70_000, positionMs = 100_000, durationMs = 7_200_000)
        assertEquals(HandoverPlan.At(165_000, 65_000), plan)
    }

    @Test
    fun `the shortest buffer that works is the minimum lead plus the reserve`() {
        assertEquals(HandoverPlan.At(108_000, 8_000), HandoverPlanner.lower(HandoverTrigger.Link, 13_000, 100_000, 7_200_000))
        assertEquals(HandoverPlan.Reopen, HandoverPlanner.lower(HandoverTrigger.Link, 12_999, 100_000, 7_200_000))
    }

    @Test
    fun `a short buffer opens the title again whatever the trigger`() {
        for (trigger in HandoverTrigger.entries) {
            assertEquals(HandoverPlan.Reopen, HandoverPlanner.lower(trigger, 10_000, 0, 7_200_000))
            assertEquals(HandoverPlan.Reopen, HandoverPlanner.lower(trigger, 2_000, 0, 7_200_000))
            assertEquals(HandoverPlan.Reopen, HandoverPlanner.lower(trigger, -1_000, 0, 7_200_000))
        }
    }

    @Test
    fun `the throughput trigger hands over like the others`() {
        assertEquals(HandoverPlan.At(36_000, 36_000), HandoverPlanner.lower(HandoverTrigger.Throughput, 41_000, 0, 7_200_000))
    }

    @Test
    fun `a stall with enough buffer left hands over`() {
        assertTrue(HandoverPlanner.lower(HandoverTrigger.Stalls, 100_000, 0, 7_200_000) is HandoverPlan.At)
    }

    @Test
    fun `near the end of the title nothing is handed over`() {
        assertEquals(HandoverPlan.Wait, HandoverPlanner.lower(HandoverTrigger.Link, 120_000, 7_150_000, 7_200_000))
        assertEquals(HandoverPlan.Wait, HandoverPlanner.lower(HandoverTrigger.Draining, 10_000, 7_190_000, 7_200_000))
        assertEquals(HandoverPlan.Reopen, HandoverPlanner.lower(HandoverTrigger.Stalls, 120_000, 7_150_000, 7_200_000))
        // An unknown length does not hold it back.
        assertTrue(HandoverPlanner.lower(HandoverTrigger.Link, 120_000, 0, 0) is HandoverPlan.At)
    }

    @Test
    fun `the way back needs thirty seconds of buffer`() {
        assertEquals(HandoverPlan.Wait, HandoverPlanner.back(29_999, 0, 7_200_000))
        assertEquals(HandoverPlan.At(25_000, 25_000), HandoverPlanner.back(30_000, 0, 7_200_000))
    }

    @Test
    fun `the way back stays within the buffer and the maximum lead`() {
        assertEquals(HandoverPlan.At(55_000, 45_000), HandoverPlanner.back(50_000, 10_000, 7_200_000))
        assertEquals(HandoverPlan.At(100_000, 90_000), HandoverPlanner.back(300_000, 10_000, 7_200_000))
    }

    @Test
    fun `the way back waits near the end`() {
        assertEquals(HandoverPlan.Wait, HandoverPlanner.back(60_000, 7_180_000, 7_200_000))
    }

    private fun backToFile(
        fileFits: Boolean = true,
        fileBitrate: Int? = 37_000_000,
        budget: Int = 120_000_000,
        aheadMs: Long = 60_000,
        handoverActive: Boolean = false,
        returns: Int = 0,
    ) = HandoverPlanner.backToFile(fileFits, fileBitrate, budget, aheadMs, handoverActive, returns, maxReturns = 2)

    @Test
    fun `a ladder goes back to the file when the link carries it`() {
        assertTrue(backToFile())
    }

    @Test
    fun `a title that needs a transcode anyway never goes back`() {
        assertFalse(backToFile(fileFits = false))
        assertFalse(backToFile(fileBitrate = null))
    }

    @Test
    fun `a link without room to spare keeps the ladder`() {
        // The file needs 37 Mbit/s; the return asks for 15 % more.
        assertFalse(backToFile(budget = 42_000_000))
        assertTrue(backToFile(budget = 43_000_000))
    }

    @Test
    fun `a ladder waits for the buffer, a running hand over and the returns left`() {
        assertFalse(backToFile(aheadMs = 29_999))
        assertTrue(backToFile(aheadMs = 30_000))
        assertFalse(backToFile(handoverActive = true))
        assertTrue(backToFile(returns = 1))
        assertFalse(backToFile(returns = 2))
    }

    @Test
    fun `an opened stream must leave enough of the lead`() {
        assertTrue(HandoverPlanner.stillWorks(lowering = true, atMs = 150_000, positionMs = 147_000))
        assertFalse(HandoverPlanner.stillWorks(lowering = true, atMs = 150_000, positionMs = 148_000))
        assertTrue(HandoverPlanner.stillWorks(lowering = false, atMs = 150_000, positionMs = 142_000))
        assertFalse(HandoverPlanner.stillWorks(lowering = false, atMs = 150_000, positionMs = 143_000))
    }

    @Test
    fun `a buffer that fell by 15 s in 30 s while loading is draining`() {
        val trend = BufferTrend()
        var fired = false
        for (i in 0..60) fired = trend.record(i * 500L, 100_000 - i * 500L, loading = true) || fired
        assertTrue(fired)
    }

    @Test
    fun `a buffer that holds up is not draining`() {
        val trend = BufferTrend()
        for (i in 0..400) assertFalse(trend.record(i * 500L, 60_000 - (i % 10) * 500L, loading = true))
    }

    @Test
    fun `a slow fall is not draining`() {
        val trend = BufferTrend()
        // 10 s in 30 s.
        for (i in 0..60) assertFalse(trend.record(i * 500L, 100_000 - i * 166L, loading = true))
    }

    @Test
    fun `no loading starts the count again`() {
        val trend = BufferTrend()
        for (i in 0..40) trend.record(i * 500L, 100_000 - i * 500L, loading = true)
        assertFalse(trend.record(20_500, 70_000, loading = false))
        assertFalse(trend.record(21_000, 69_500, loading = true))
        assertFalse(trend.record(40_000, 60_000, loading = true))
    }

    @Test
    fun `it fires once and then counts anew`() {
        val trend = BufferTrend()
        var fires = 0
        for (i in 0..100) if (trend.record(i * 500L, 120_000 - i * 500L, loading = true)) fires++
        // Falls 1 s per second: fires at 30 s and again 30 s after that, not at every sample.
        assertEquals(1, fires)
    }

    @Test
    fun `the sync point may be moved to by at most 30 s`() {
        assertEquals(30_000, HandoverPlanner.syncShiftLimit(bufferedBeyondMs = 100_000, loadAheadMs = 10_000))
    }

    @Test
    fun `the sync point must leave the reserve and the load ahead of the playing stream`() {
        assertEquals(10_000, HandoverPlanner.syncShiftLimit(bufferedBeyondMs = 25_000, loadAheadMs = 10_000))
        assertEquals(0, HandoverPlanner.syncShiftLimit(bufferedBeyondMs = 15_000, loadAheadMs = 10_000))
        assertTrue(HandoverPlanner.syncShiftLimit(bufferedBeyondMs = 14_999, loadAheadMs = 10_000) < 0)
    }
}
