package io.github.glacier_jellyfin.androidtv.core.player

import io.github.glacier_jellyfin.androidtv.core.player.BitstreamGuard.RouteDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BitstreamGuardTest {

    private var now = 0L
    private val guard = BitstreamGuard()

    /** A bitstream track that played up to [positionUs]. */
    private fun playing(positionUs: Long = 1_000_000) {
        guard.onConfigure(bitstream = true, now)
        guard.onPlay(now)
        guard.onPosition(positionUs - 20_000, pendingData = true, now)
        guard.onPosition(positionUs, pendingData = true, now)
    }

    /** Reports [positionUs] every 10 ms for [ms]; true if the guard asked for a new track. */
    private fun standStill(
        ms: Long,
        positionUs: Long = 1_000_000,
        pendingData: Boolean = true,
    ): Boolean {
        var stalled = false
        repeat((ms / 10).toInt()) {
            now += 10
            stalled = guard.onPosition(positionUs, pendingData, now) || stalled
        }
        return stalled
    }

    @Test
    fun `asks for a new track when a playing track stands still with data waiting`() {
        playing()
        assertFalse(standStill(900))
        assertTrue(standStill(200))
    }

    @Test
    fun `leaves quiet sound alone because its position moves`() {
        // Silence in a bitstream is still frames that play: the position runs on.
        playing()
        var position = 1_000_000L
        repeat(1_000) {
            now += 10
            position += 10_000
            assertFalse(guard.onPosition(position, pendingData = true, now))
        }
    }

    @Test
    fun `leaves a stream without sound data alone`() {
        // Before the sound starts or in a gap the sink holds nothing.
        playing()
        assertFalse(standStill(10_000, pendingData = false))
    }

    @Test
    fun `leaves a paused track and decoded sound alone`() {
        playing()
        guard.onPause()
        assertFalse(standStill(10_000))

        guard.onConfigure(bitstream = false, now)
        guard.onPlay(now)
        assertFalse(standStill(10_000))
    }

    @Test
    fun `gives a new track more time to start`() {
        guard.onConfigure(bitstream = true, now)
        guard.onPlay(now)
        assertFalse(standStill(3_900, positionUs = 0))
        assertTrue(standStill(200, positionUs = 0))
    }

    @Test
    fun `waits before the next track and longer after each that did not play`() {
        playing()
        assertTrue(standStill(1_100))
        guard.onFlush(now)
        assertTrue(guard.holdWrites(now + 299))
        assertFalse(guard.holdWrites(now + 300))

        now += 5_000
        assertTrue(standStill(4_100, positionUs = 0))
        guard.onFlush(now)
        assertTrue(guard.holdWrites(now + 599))
        assertFalse(guard.holdWrites(now + 600))
    }

    @Test
    fun `gives up after three tracks that never played`() {
        playing()
        var recoveries = 0
        repeat(6) {
            if (standStill(6_000, positionUs = 0)) {
                recoveries++
                guard.onFlush(now)
            }
        }
        assertEquals(3, recoveries)
    }

    @Test
    fun `waits for the HDMI link after a dead track and writes once it is back`() {
        playing()
        assertTrue(guard.onDeadTrack(now))
        assertTrue(guard.holdingRoute)
        now += 1_000
        assertEquals(RouteDecision.Wait, guard.routeDecision(routeBack = false, now))
        assertFalse(standStill(3_000))
        assertEquals(RouteDecision.Write, guard.routeDecision(routeBack = true, now))
        guard.onWritten()
        assertFalse(guard.holdingRoute)
    }

    @Test
    fun `fails when the HDMI link stays away`() {
        playing()
        assertTrue(guard.onDeadTrack(now))
        now += 5_000
        assertEquals(RouteDecision.GiveUp, guard.routeDecision(routeBack = false, now))
        assertFalse(guard.holdingRoute)
    }

    @Test
    fun `gives drops of one switch one shared wait`() {
        playing()
        assertTrue(guard.onDeadTrack(now))
        now += 3_000
        guard.onWritten()
        assertTrue(guard.onDeadTrack(now))
        now += 2_000
        assertFalse(guard.onDeadTrack(now))

        // A drop much later gets its own wait.
        now += 20_000
        assertTrue(guard.onDeadTrack(now))
    }

    @Test
    fun `does not hold decoded sound for the link`() {
        guard.onConfigure(bitstream = false, now)
        assertFalse(guard.onDeadTrack(now))
    }
}
