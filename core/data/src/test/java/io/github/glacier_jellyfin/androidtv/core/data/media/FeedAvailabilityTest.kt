package io.github.glacier_jellyfin.androidtv.core.data.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedAvailabilityTest {

    private var now = 1_000_000L
    private val availability = FeedAvailability { now }

    @Test
    fun `an unknown profile is asked`() = assertTrue(availability.shouldAsk("a/1"))

    @Test
    fun `a no is not asked again within five minutes`() {
        availability.record("a/1", available = false)
        now += FeedAvailability.RECHECK_MS - 1
        assertFalse(availability.shouldAsk("a/1"))
    }

    @Test
    fun `a no is asked again after five minutes`() {
        availability.record("a/1", available = false)
        now += FeedAvailability.RECHECK_MS
        assertTrue(availability.shouldAsk("a/1"))
    }

    @Test
    fun `a yes is asked again every time`() {
        availability.record("a/1", available = true)
        assertTrue(availability.shouldAsk("a/1"))
    }

    @Test
    fun `a yes clears an earlier no`() {
        availability.record("a/1", available = false)
        availability.record("a/1", available = true)
        assertTrue(availability.shouldAsk("a/1"))
    }

    @Test
    fun `another profile is asked`() {
        availability.record("a/1", available = false)
        assertTrue(availability.shouldAsk("a/2"))
        assertTrue(availability.shouldAsk("b/1"))
    }
}
