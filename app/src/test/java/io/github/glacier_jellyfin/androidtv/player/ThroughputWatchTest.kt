package io.github.glacier_jellyfin.androidtv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThroughputWatchTest {
    private val stream = 37_000_000L

    private fun fires(
        watch: ThroughputWatch,
        from: Long,
        to: Long,
        rate: Long,
        loading: Boolean = true,
        full: Boolean = false,
    ): List<Long> = (from..to step 2_000).filter { watch.check(it, loading, rate, stream, full) }

    @Test
    fun `a slow link while loading for ten seconds fires`() {
        val watch = ThroughputWatch()
        assertEquals(listOf(10_000L), fires(watch, 0, 12_000, 20_000_000))
    }

    @Test
    fun `a link that carries the stream does not fire`() {
        assertTrue(fires(ThroughputWatch(), 0, 120_000, 36_000_000).isEmpty())
        assertTrue(fires(ThroughputWatch(), 0, 120_000, 40_000_000).isEmpty())
    }

    @Test
    fun `the limit is 95 percent of the stream`() {
        assertFalse(
            ThroughputWatch().apply {
                check(
                    0,
                    true,
                    35_150_000,
                    stream,
                    false,
                )
            }.check(10_000, true, 35_150_000, stream, false),
        )
        assertTrue(
            ThroughputWatch().apply {
                check(
                    0,
                    true,
                    35_149_999,
                    stream,
                    false,
                )
            }.check(10_000, true, 35_149_999, stream, false),
        )
    }

    @Test
    fun `a pause in loading or a full buffer starts the count again`() {
        val watch = ThroughputWatch()
        assertTrue(fires(watch, 0, 8_000, 1_000_000).isEmpty())
        assertTrue(fires(watch, 10_000, 10_000, 1_000_000, loading = false).isEmpty())
        assertTrue(fires(watch, 12_000, 20_000, 1_000_000).isEmpty())
        assertTrue(fires(watch, 22_000, 22_000, 1_000_000, full = true).isEmpty())
        assertTrue(fires(watch, 24_000, 32_000, 1_000_000).isEmpty())
        assertEquals(listOf(34_000L), fires(watch, 34_000, 36_000, 1_000_000))
    }

    @Test
    fun `it fires at most once in thirty seconds`() {
        val watch = ThroughputWatch()
        assertEquals(listOf(10_000L, 40_000L), fires(watch, 0, 60_000, 1_000_000))
    }

    @Test
    fun `an unknown stream rate never fires`() {
        val watch = ThroughputWatch()
        assertFalse((0L..60_000L step 2_000).any { watch.check(it, true, 0, 0, false) })
    }
}
