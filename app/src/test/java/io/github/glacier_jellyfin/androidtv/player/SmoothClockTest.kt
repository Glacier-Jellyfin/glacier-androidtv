package io.github.glacier_jellyfin.androidtv.player

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmoothClockTest {
    /** Frames of 16 ms; a sample every 500 ms reporting [rate] times the wall clock plus [jitter]. */
    private fun run(
        rate: Double,
        jitter: (Int) -> Long,
        seconds: Int = 20,
    ): List<Long> {
        val clock = SmoothClock()
        val out = mutableListOf<Long>()
        var now = 0L
        var tick = 0
        while (now < seconds * 1000L) {
            if (now % 496 < 16) {
                clock.onSample((10_000 + now * rate).toLong() + jitter(tick++), now)
            }
            out += clock.onFrame(now, true)
            now += 16
        }
        return out
    }

    @Test
    fun noBackwardsStepsWithJitter() {
        val out = run(1.0, { if (it % 2 == 0) 100L else -100L })
        val steps = out.zipWithNext { a, b -> b - a }
        assertTrue("went back: ${steps.min()}", steps.all { it >= -2 })
        assertTrue("jumped: ${steps.max()}", steps.all { it < 80 })
    }

    @Test
    fun followsHalfSpeed() {
        val out = run(0.5, { 0L })
        val tail = out.takeLast(200).zipWithNext { a, b -> b - a }
        assertTrue("avg step ${tail.average()}", abs(tail.average() - 8.0) < 1.5)
    }

    @Test
    fun seekSnapsAtOnce() {
        val clock = SmoothClock()
        clock.onSample(1_000, 0)
        clock.onFrame(0, true)
        clock.onFrame(16, true)
        clock.onSample(60_000, 500)
        assertEquals(60_000L, clock.onFrame(516, true) / 100 * 100)
    }

    @Test
    fun pausedSettlesOnSample() {
        val clock = SmoothClock()
        clock.onSample(1_000, 0)
        clock.onFrame(0, true)
        assertEquals(1_000L, clock.onFrame(16, false))
    }
}
