package io.github.glacier_jellyfin.androidtv.core.player

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadRateTest {
    private var now = 0L
    private val rate = DownloadRate { now }

    @Test
    fun `bytes over the window give the bit rate`() {
        // 2.5 MB/s for 8 s = 20 Mbit/s.
        repeat(80) {
            rate.record(250_000)
            now += 100
        }
        assertEquals(20_000_000L, rate.bitsPerSecond(8_000))
    }

    @Test
    fun `nothing loaded gives zero`() {
        rate.record(1_000_000)
        now += 20_000
        assertEquals(0L, rate.bitsPerSecond(8_000))
    }

    @Test
    fun `old bytes fall out of the window`() {
        repeat(50) {
            rate.record(1_000_000)
            now += 100
        }
        now += 10_000
        repeat(80) {
            rate.record(100_000)
            now += 100
        }
        assertEquals(8_000_000L, rate.bitsPerSecond(8_000))
    }

    @Test
    fun `reads in one bucket add up`() {
        repeat(10) { rate.record(10_000) }
        now += 1_000
        assertEquals(800_000L, rate.bitsPerSecond(1_000))
    }
}
