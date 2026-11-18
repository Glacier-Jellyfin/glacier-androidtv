package io.github.glacier_jellyfin.androidtv.core.data.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BandwidthProbeTest {

    @Test
    fun `the first block ends within seconds on a link of 400 kbit per second`() {
        val seconds = BandwidthProbe.FIRST_BYTES * 8 / 400_000.0
        assertTrue(seconds < BandwidthProbe.TIMEOUT_MS / 1000.0 / 1.5)
    }

    @Test
    fun `a first block that took two seconds or more is the whole test`() {
        // 128 KiB take 2 s at 524,288 bit/s.
        assertEquals(false, BandwidthProbe.needsSecond(524_288.0))
        assertEquals(false, BandwidthProbe.needsSecond(400_000.0))
        assertEquals(true, BandwidthProbe.needsSecond(600_000.0))
        assertEquals(true, BandwidthProbe.needsSecond(50_000_000.0))
    }

    @Test
    fun `a slow link gets a second block of about three seconds, never below the first block`() {
        assertEquals(BandwidthProbe.FIRST_BYTES, BandwidthProbe.secondBlock(400_000.0))
        assertEquals(262_144, BandwidthProbe.secondBlock(1_000_000.0))
        assertEquals(524_288, BandwidthProbe.secondBlock(2_000_000.0))
    }

    @Test
    fun `a fast first result gets a big second block, at most the big one`() {
        assertEquals(1_048_576, BandwidthProbe.secondBlock(5_000_000.0))
        assertEquals(2_097_152, BandwidthProbe.secondBlock(10_000_000.0))
        assertEquals(BandwidthProbe.BIG_BYTES, BandwidthProbe.secondBlock(100_000_000.0))
    }

    @Test
    fun `every block is a power of two, which the server sends as asked`() {
        listOf(0.1, 400_000.0, 782_000.0, 3_000_000.0, 50_000_000.0).forEach {
            val block = BandwidthProbe.secondBlock(it)
            assertEquals(block, Integer.highestOneBit(block))
        }
        assertEquals(BandwidthProbe.FIRST_BYTES, Integer.highestOneBit(BandwidthProbe.FIRST_BYTES))
    }

    @Test
    fun `bytes that moved before a timeout give the speed they showed`() {
        // 200 kB in 4 s are 0.4 Mbit/s, not a guess from the block that was asked for.
        assertEquals(400_000.0, BandwidthProbe.bitsPerSecond(200_000, 4_000_000_000), 0.5)
        assertEquals(1_000_000.0, BandwidthProbe.bitsPerSecond(125_000, 1_000_000_000), 0.5)
    }
}
