package io.github.glacier_jellyfin.androidtv.player

import io.github.glacier_jellyfin.androidtv.core.data.playback.PlaybackMethod
import io.github.glacier_jellyfin.androidtv.core.player.HandOverProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamSwitchTest {

    @Test
    fun `the file as it is is called the file`() {
        assertEquals(StreamLabel.File, streamLabel(PlaybackMethod.DirectPlay, null))
        assertEquals(StreamLabel.File, streamLabel(PlaybackMethod.DirectStream, null))
    }

    @Test
    fun `a transcode is called by its picture`() {
        assertEquals(StreamLabel.Picture(720), streamLabel(PlaybackMethod.Transcode, 720))
        // A copied picture comes as a direct stream sometimes and keeps the source's height.
        assertEquals(StreamLabel.Picture(1080), streamLabel(PlaybackMethod.DirectStream, 1080))
        assertEquals(StreamLabel.Converted, streamLabel(PlaybackMethod.Transcode, null))
    }

    @Test
    fun `pictures read as lines, from 2160 as 4K`() {
        assertEquals("720p", pictureText(720))
        assertEquals("1080p", pictureText(1080))
        assertEquals("4K", pictureText(2160))
        assertEquals("4K", pictureText(4320))
    }

    @Test
    fun `every trigger has its cause, the way back is the recovered link`() {
        assertEquals(SwitchCause.LinkSlow, switchReason(HandoverTrigger.Link).cause)
        assertEquals(SwitchCause.Draining, switchReason(HandoverTrigger.Draining).cause)
        assertEquals(SwitchCause.Stalls, switchReason(HandoverTrigger.Stalls).cause)
        assertEquals(SwitchCause.Throughput, switchReason(HandoverTrigger.Throughput).cause)
        assertEquals(SwitchCause.LinkRecovered, switchReason(null).cause)
    }

    @Test
    fun `a slow link shows its numbers in whole Mbit per second`() {
        assertEquals(15 to 37, switchReason(HandoverTrigger.Link, 15_200_000, 36_800_000).mbit)
        assertEquals(2 to 8, switchReason(HandoverTrigger.Throughput, 2_400_000, 7_600_000).mbit)
    }

    @Test
    fun `numbers are only shown when both are known and the cause is about the link`() {
        assertNull(switchReason(HandoverTrigger.Link, 15_000_000).mbit)
        assertNull(switchReason(HandoverTrigger.Link).mbit)
        assertNull(switchReason(HandoverTrigger.Draining, 15_000_000, 37_000_000).mbit)
        assertNull(switchReason(null, 80_000_000, 37_000_000).mbit)
    }

    @Test
    fun `the bar holds the playing buffer up to the point and the next stream after it`() {
        // 20 s to the point with 40 s buffered, 14 s of the next stream, on a scale of 50 s.
        val split = bufferSplit(bufferedMs = 40_000, pointInMs = 20_000, nextLoadedMs = 14_000, maxMs = 50_000)
        assertEquals(0.4f, split.playing, 0.001f)
        assertEquals(0.4f, split.point, 0.001f)
        assertEquals(0.68f, split.nextEnd, 0.001f)
    }

    @Test
    fun `the playing part ends where the buffer does when it has not reached the point`() {
        val split = bufferSplit(bufferedMs = 10_000, pointInMs = 20_000, nextLoadedMs = 0, maxMs = 50_000)
        assertEquals(0.2f, split.playing, 0.001f)
        assertEquals(0.4f, split.point, 0.001f)
        assertEquals(0.4f, split.nextEnd, 0.001f)
    }

    @Test
    fun `the bar never runs over its scale`() {
        val split = bufferSplit(bufferedMs = 90_000, pointInMs = 45_000, nextLoadedMs = 30_000, maxMs = 50_000)
        assertEquals(0.9f, split.playing, 0.001f)
        assertEquals(1f, split.nextEnd, 0.001f)
        assertEquals(BufferSplit(0f, 0f, 0f), bufferSplit(1_000, 1_000, 1_000, 0))
    }

    @Test
    fun `the progress of the player is counted from the position`() {
        val stats = HandOverProgress(
            pointInItemMs = 80_000,
            postponedMs = 5_000,
            nextLoadedMs = 14_000,
        ).toStats(positionMs = 62_000, bufferedPositionMs = 100_000)
        assertEquals(18_000L, stats.pointInMs)
        assertEquals(5_000L, stats.postponedMs)
        assertEquals(14_000L, stats.nextLoadedMs)
        assertEquals(38_000L, stats.bufferedMs)
    }

    @Test
    fun `a point that is passed counts as now`() {
        val stats = HandOverProgress(
            pointInItemMs = 80_000,
            postponedMs = 0,
            nextLoadedMs = 0,
        ).toStats(positionMs = 81_000, bufferedPositionMs = 80_500)
        assertEquals(0L, stats.pointInMs)
        assertEquals(0L, stats.bufferedMs)
    }
}
