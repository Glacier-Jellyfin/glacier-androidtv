package io.github.glacier_jellyfin.androidtv.player

import org.junit.Assert.assertEquals
import org.junit.Test

class TitleTimeTest {

    @Test
    fun `positions of the first stream are the title's`() {
        val time = TitleTime()
        assertEquals(5_000, time.position(5_000))
        assertEquals(1_000, time.duration(1_000))
    }

    @Test
    fun `after a hand over the player counts from its start`() {
        val time = TitleTime()
        time.startAt(600_000)
        assertEquals(605_000, time.position(5_000))
        assertEquals(7_200_000, time.duration(6_600_000))
    }

    @Test
    fun `the length stays while the player knows none`() {
        val time = TitleTime()
        assertEquals(7_200_000, time.duration(7_200_000))
        assertEquals(7_200_000, time.duration(0))
    }

    @Test
    fun `seeks stay in the item or go to the queued stream`() {
        val time = TitleTime()
        assertEquals(SeekTarget.Here(100_000), time.seek(100_000, null))
        assertEquals(SeekTarget.Here(100_000), time.seek(100_000, 600_000))
        assertEquals(SeekTarget.Next(5_000), time.seek(605_000, 600_000))
    }

    @Test
    fun `a seek before the stream begins opens the title again`() {
        val time = TitleTime()
        time.startAt(600_000)
        assertEquals(SeekTarget.Reopen(100_000), time.seek(100_000, null))
        assertEquals(SeekTarget.Here(0), time.seek(600_000, null))
        assertEquals(SeekTarget.Here(60_000), time.seek(660_000, null))
    }
}
