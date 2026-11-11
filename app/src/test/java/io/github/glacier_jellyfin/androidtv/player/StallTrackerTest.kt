package io.github.glacier_jellyfin.androidtv.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StallTrackerTest {

    @Test
    fun `one stall is a hiccup`() {
        assertFalse(StallTracker().record(10_000))
    }

    @Test
    fun `two stalls close together call for action`() {
        val tracker = StallTracker()
        assertFalse(tracker.record(10_000))
        assertTrue(tracker.record(40_000))
    }

    @Test
    fun `stalls far apart do not add up`() {
        val tracker = StallTracker()
        assertFalse(tracker.record(10_000))
        assertFalse(tracker.record(200_000))
    }

    @Test
    fun `clearing starts the count again`() {
        val tracker = StallTracker()
        tracker.record(10_000)
        tracker.clear()
        assertFalse(tracker.record(20_000))
    }
}
