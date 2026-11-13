package io.github.glacier_jellyfin.androidtv.core.data.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamBudgetTest {

    @Test
    fun `the picture fits on direct play or stream`() {
        assertTrue(StreamBudget.pictureFits(true, false, null))
        assertTrue(StreamBudget.pictureFits(true, true, "VideoCodecNotSupported"))
    }

    @Test
    fun `an audio only transcode leaves the picture`() {
        assertTrue(StreamBudget.pictureFits(false, true, "AudioCodecNotSupported"))
        assertTrue(StreamBudget.pictureFits(false, true, "ContainerNotSupported,AudioCodecNotSupported"))
    }

    @Test
    fun `a video reason, an unknown one or none does not fit`() {
        assertFalse(StreamBudget.pictureFits(false, true, "AudioCodecNotSupported,VideoCodecNotSupported"))
        assertFalse(StreamBudget.pictureFits(false, true, "SubtitleCodecNotSupported"))
        assertFalse(StreamBudget.pictureFits(false, true, "UnknownVideoStreamInfo"))
        assertFalse(StreamBudget.pictureFits(false, true, "SomethingNew"))
        assertFalse(StreamBudget.pictureFits(false, true, null))
        assertFalse(StreamBudget.pictureFits(false, false, null))
    }

    @Test
    fun `a stream keeps headroom on the measured link`() {
        assertEquals(16_150_000, StreamBudget.of(19_000_000))
    }

    @Test
    fun `a 20 Mbit file does not fit a 19 Mbit link`() {
        assertTrue(StreamBudget.exceeds(20_000_000, StreamBudget.of(19_000_000)))
    }

    @Test
    fun `a fast link leaves the file alone`() {
        assertFalse(StreamBudget.exceeds(60_000_000, StreamBudget.of(500_000_000)))
    }

    @Test
    fun `the budget stays within floor and the highest step`() {
        assertEquals(StreamBudget.FLOOR, StreamBudget.of(100_000))
        assertEquals(StreamBudget.CEILING, StreamBudget.of(10_000_000_000))
    }

    @Test
    fun `unknown values never exceed`() {
        assertFalse(StreamBudget.exceeds(null, 10_000_000))
        assertFalse(StreamBudget.exceeds(20_000_000, null))
    }

    @Test
    fun `a stall lowers the budget below the stream that stalled`() {
        assertEquals(14_000_000, StreamBudget.afterStall(20_000_000, null))
        assertEquals(10_000_000, StreamBudget.afterStall(20_000_000, 10_000_000))
        assertEquals(StreamBudget.FLOOR, StreamBudget.afterStall(2_000_000, null))
    }

    @Test
    fun `a 4K file on a 4K display has all three tiers`() {
        assertEquals(listOf(2160, 1080, 720), StreamBudget.resolutions(2160, 2160).map { it.height })
    }

    @Test
    fun `a 4K file on a 1080p display has no tier bigger than the display`() {
        assertEquals(listOf(1080, 720), StreamBudget.resolutions(1080, 2160).map { it.height })
    }

    @Test
    fun `a 1080p file on a 4K display has no tier bigger than the file`() {
        assertEquals(listOf(1080, 720), StreamBudget.resolutions(2160, 1080).map { it.height })
    }

    @Test
    fun `a small file has one tier or none`() {
        assertEquals(listOf(720), StreamBudget.resolutions(1080, 720).map { it.height })
        assertTrue(StreamBudget.resolutions(1080, 480).isEmpty())
    }

    @Test
    fun `a bigger picture asks more of the link`() {
        assertTrue(StreamBudget.expectedBitrate(720) < StreamBudget.expectedBitrate(1080))
        assertTrue(StreamBudget.expectedBitrate(1080) < StreamBudget.expectedBitrate(2160))
    }

    @Test
    fun `audio takes 640 kbit per second from six channels`() {
        assertEquals(640_000, StreamBudget.audioBitrate(6))
        assertEquals(640_000, StreamBudget.audioBitrate(8))
        assertEquals(384_000, StreamBudget.audioBitrate(2))
    }

    @Test
    fun `a video copy asks the picture plus the new audio`() {
        assertEquals(40_640_000, StreamBudget.copyDemand(40_000_000, 6))
        assertEquals(40_384_000, StreamBudget.copyDemand(40_000_000, 2))
    }

    @Test
    fun `a video copy takes the shortest segment the budget carries with room for peaks`() {
        // 10 s segments: 7 of 10 s move data, demand plus 20 %.
        assertEquals(10, StreamBudget.copySegmentSeconds(10_000_000, 17_200_000))
        assertEquals(10, StreamBudget.copySegmentSeconds(10_000_000, 100_000_000))
        // Needs 17 s: 14 of 17 s must carry 12 Mbit/s.
        assertEquals(17, StreamBudget.copySegmentSeconds(10_000_000, 14_600_000))
        // The longest segment, exactly on the border (10.8 Mbit/s * 30 / 27 = 12 Mbit/s).
        assertEquals(30, StreamBudget.copySegmentSeconds(9_000_000, 12_000_000))
        assertNull(StreamBudget.copySegmentSeconds(9_000_000, 11_999_999))
        // Never: the measured case, 21.6 Mbit/s with a budget of 26.1.
        assertNull(StreamBudget.copySegmentSeconds(21_600_000, 26_100_000))
    }

    @Test
    fun `the requested bitrate leaves the picture room above its own bitrate`() {
        val request = StreamBudget.copyRequest(40_000_000, 6)
        assertEquals(41_504_000, request)
        // The server subtracts its own audio guess; what is left must reach the picture's bitrate.
        assertTrue(request - 640_000 >= 40_000_000)
    }

    @Test
    fun `a reduced stream returns only with room to spare`() {
        assertFalse(StreamBudget.returnsTo(20_000_000, 21_000_000))
        assertTrue(StreamBudget.returnsTo(20_000_000, 23_000_000))
    }

    @Test
    fun `a tier that copies the source picture asks for the picture with reserve plus the audio`() {
        // The measured case: 1080p copy of a 24 Mbit/s remux, 6 channels converted to 640 kbit/s.
        assertEquals(
            29_440_000,
            StreamBudget.tierDemand(1080, 1080, videoCopied = true, videoBitrate = 24_000_000, audioChannels = 6),
        )
        assertEquals(24_000_000 * 12 / 10 + 384_000, StreamBudget.tierDemand(1080, 1080, true, 24_000_000, 2))
    }

    @Test
    fun `a transcoded tier asks what a transcode of its size takes`() {
        assertEquals(
            StreamBudget.expectedBitrate(1080),
            StreamBudget.tierDemand(1080, 1080, videoCopied = false, videoBitrate = 24_000_000, audioChannels = 6),
        )
        assertEquals(
            StreamBudget.expectedBitrate(720),
            StreamBudget.tierDemand(720, 1080, videoCopied = true, videoBitrate = 24_000_000, audioChannels = 6),
        )
    }

    @Test
    fun `a copy without a known video bitrate is counted as a transcode`() {
        assertEquals(StreamBudget.expectedBitrate(1080), StreamBudget.tierDemand(1080, 1080, true, null, 6))
        assertEquals(StreamBudget.expectedBitrate(1080), StreamBudget.tierDemand(1080, 1080, true, 0, 6))
        assertEquals(StreamBudget.expectedBitrate(1080), StreamBudget.tierDemand(1080, null, true, 24_000_000, 6))
    }

    @Test
    fun `the copy decides which tier a link starts with`() {
        val budget = 14_000_000
        val copy = StreamBudget.tierDemand(1080, 1080, true, 24_000_000, 6)
        val demands = mapOf(1080 to copy, 720 to StreamBudget.expectedBitrate(720))
        val start = StreamBudget.resolutions(1080, 1080).first { demands.getValue(it.height) <= budget }
        // As a transcode 1080p (8 Mbit/s) would have fitted; as the copy it does not.
        assertTrue(StreamBudget.expectedBitrate(1080) <= budget)
        assertEquals(720, start.height)
    }
}
