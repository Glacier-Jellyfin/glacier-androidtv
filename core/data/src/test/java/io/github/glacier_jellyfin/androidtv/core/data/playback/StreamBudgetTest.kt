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
    fun `a weak test is believed when both tests and the estimate say the stream does not fit`() {
        assertTrue(StreamBudget.weakLinkConfirmed(10_800_000, 1_900_000, 2_300_000, 3_000_000))
        // No estimate at all: the two tests decide.
        assertTrue(StreamBudget.weakLinkConfirmed(10_800_000, 1_900_000, 2_300_000, null))
        assertTrue(StreamBudget.weakLinkConfirmed(10_800_000, 1_900_000, 2_300_000, 0))
    }

    @Test
    fun `a single weak test is not believed`() {
        assertFalse(StreamBudget.weakLinkConfirmed(10_800_000, 1_900_000, 15_000_000, 3_000_000))
        assertFalse(StreamBudget.weakLinkConfirmed(10_800_000, 1_900_000, null, 3_000_000))
        assertFalse(StreamBudget.weakLinkConfirmed(10_800_000, 15_000_000, 1_900_000, 3_000_000))
    }

    @Test
    fun `a stream the player's estimate carries is left alone`() {
        // 20 Mbit/s estimate: 17 Mbit/s budget, the 10.8 Mbit/s stream fits.
        assertFalse(StreamBudget.weakLinkConfirmed(10_800_000, 1_900_000, 2_300_000, 20_000_000))
        // 12 Mbit/s estimate: 10.2 Mbit/s budget, the stream does not fit.
        assertTrue(StreamBudget.weakLinkConfirmed(10_800_000, 1_900_000, 2_300_000, 12_000_000))
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
        assertEquals(1_400_000, StreamBudget.afterStall(2_000_000, null))
        assertEquals(StreamBudget.FLOOR, StreamBudget.afterStall(400_000, null))
    }

    @Test
    fun `the floor lets the budget fall to 320 kbit per second`() {
        assertEquals(320_000, StreamBudget.FLOOR)
        assertEquals(425_000, StreamBudget.of(500_000))
        assertEquals(340_000, StreamBudget.of(400_000))
        assertEquals(StreamBudget.FLOOR, StreamBudget.of(300_000))
        assertEquals(StreamBudget.FLOOR, StreamBudget.afterStall(400_000, null))
        assertEquals(490_000, StreamBudget.afterStall(700_000, null))
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

    private fun heights(
        display: Int,
        source: Int,
        codec: String? = "hevc",
        budget: Int? = null,
        fps: Double = 25.0,
    ) = StreamBudget.resolutions(display, source, codec = codec, budget = budget, fps = fps).map { it.height }

    private fun tier(height: Int) = StreamBudget.resolutions(2160, 2160).first { it.height == height }

    @Test
    fun `a 4K file on a 4K display has the big tiers and two small ones`() {
        assertEquals(listOf(2160, 1080, 720, 640, 504, 384), heights(2160, 2160))
    }

    @Test
    fun `a 4K file on a 1080p display has no tier bigger than the display`() {
        assertEquals(listOf(1080, 720, 640, 504, 384), heights(1080, 2160))
    }

    @Test
    fun `a 1080p file on a 4K display has no tier bigger than the file`() {
        assertEquals(listOf(1080, 720, 640, 504, 384), heights(2160, 1080))
    }

    @Test
    fun `a small file has the small tiers up to its own size`() {
        assertEquals(listOf(720, 640, 504, 384), heights(1080, 720))
        // A 480p source: the bitrate allows more than the file has, so one tier of its own size and one below.
        assertEquals(listOf(480, 384), heights(1080, 480))
        assertTrue(heights(1080, 100).isEmpty())
    }

    @Test
    fun `a megabit with HEVC at 25 frames carries about 580 lines`() {
        assertEquals(576, StreamBudget.heightFor(1_000_000, 16.0 / 9, 25.0, "hevc"))
    }

    @Test
    fun `H264 needs more bits, a faster film needs more, a wider one is flatter`() {
        assertEquals(504, StreamBudget.heightFor(1_200_000, 16.0 / 9, 25.0, "h264"))
        assertTrue(
            StreamBudget.heightFor(
                800_000,
                16.0 / 9,
                50.0,
                "hevc",
            ) < StreamBudget.heightFor(800_000, 16.0 / 9, 25.0, "hevc"),
        )
        assertTrue(
            StreamBudget.heightFor(
                800_000,
                2.39,
                25.0,
                "hevc",
            ) < StreamBudget.heightFor(800_000, 16.0 / 9, 25.0, "hevc"),
        )
        assertEquals(0, StreamBudget.heightFor(1_000_000, 16.0 / 9, 25.0, "hevc") % 8)
    }

    @Test
    fun `the width follows the shape of the source and never holds the picture back`() {
        assertEquals(1008, StreamBudget.widthFor(560, 16.0 / 9))
        assertEquals(1344, StreamBudget.widthFor(560, 2.39))
        assertTrue(StreamBudget.widthFor(424, 2.39) >= 424 * 2.39)
        val tiers = StreamBudget.resolutions(1080, 1080)
        assertEquals(listOf(896, 688), tiers.takeLast(2).map { it.width })
    }

    @Test
    fun `the small tiers are asked for less than they need, so the stream with its overhead fits`() {
        for (t in StreamBudget.resolutions(2160, 2160).filter { it.width != null }) {
            assertEquals(t.expected.toLong() * 100 / StreamBudget.SMALL_OVERHEAD_PERCENT, t.bitrate.toLong())
            assertTrue(t.bitrate < t.expected)
            assertTrue(t.bitrate < StreamBudget.expectedBitrate(720))
            assertTrue(t.smallAudio)
        }
        // The big tiers are asked for a multiple of what they need, and carry the audio the server picks.
        assertFalse(tier(720).smallAudio)
        assertTrue(tier(720).bitrate > tier(720).expected)
    }

    @Test
    fun `the tier for a link of 400 kbit per second keeps the reserve and asks for 288 kbit`() {
        val low = StreamBudget.resolutions(1080, 1080, budget = StreamBudget.of(400_000)).last()
        assertEquals(340_000, low.expected)
        assertEquals(288_135, low.bitrate)
        assertEquals(192_135, low.bitrate - StreamBudget.SMALL_AUDIO_BITRATE)
        // The height is sized from the video share of the request, not of the target.
        assertEquals(StreamBudget.heightFor(340_000, 16.0 / 9, 25.0, "hevc"), low.height)
    }

    @Test
    fun `each tier asks less than the bigger one, in the request and in the demand`() {
        val tiers = StreamBudget.resolutions(2160, 2160, budget = 900_000)
        assertEquals(tiers.sortedByDescending { it.bitrate }, tiers)
        assertEquals(tiers.sortedByDescending { it.expected }, tiers)
        assertEquals(tiers.sortedByDescending { it.height }, tiers)
        assertEquals(tiers.map { it.height }.distinct(), tiers.map { it.height })
    }

    @Test
    fun `the link sizes a tier for itself when no fixed one is near`() {
        // A budget of 1.0 Mbit/s would be a picture of 576 lines, too close to the 640 above it: left out.
        assertEquals(heights(2160, 2160), heights(2160, 2160, budget = 1_000_000))
        // A budget of 0.95: a tier of its own between the 1.2 and the 0.8 one.
        assertEquals(listOf(2160, 1080, 720, 640, 560, 384), heights(2160, 2160, budget = 950_000))
        // 0.9 measured, budget 0.765: within 15 % of the 0.8 tier, which serves.
        assertEquals(listOf(2160, 1080, 720, 640, 504, 384), heights(2160, 2160, budget = 765_000))
        // Half a Mbit measured: budget 425 kbit/s gets a tier under the 0.5 one.
        val low = StreamBudget.resolutions(2160, 2160, budget = StreamBudget.of(500_000))
        assertEquals(420_000, low.last().expected)
        assertEquals(StreamBudget.requestFor(420_000), low.last().bitrate)
        assertEquals(336, low.last().height)
        // A link that carries 720p, or an unknown one, adds nothing.
        assertEquals(heights(2160, 2160), heights(2160, 2160, budget = 3_000_000))
        assertEquals(heights(2160, 2160), heights(2160, 2160, budget = null))
    }

    @Test
    fun `the codec the server encodes with sets the picture a bitrate buys`() {
        // H.264 at 1.2 Mbit/s: 552 lines are small enough to be a tier of their own; with HEVC, 704 are not.
        assertEquals(listOf(1080, 720, 504, 400, 296), heights(1080, 1080, codec = "h264"))
    }

    @Test
    fun `a link of one Mbit per second starts at the 0,8 tier, one of half a Mbit at the smallest`() {
        val tiers = StreamBudget.resolutions(1080, 1080)
        fun start(measured: Long) = tiers.first { it.expected <= StreamBudget.of(measured) }.height
        assertEquals(504, start(1_000_000))
        assertEquals(720, start(3_000_000))
        assertEquals(384, start(600_000))
    }

    @Test
    fun `small tiers and small requests carry small audio`() {
        assertTrue(StreamBudget.smallAudioFor(1_500_000))
        assertTrue(StreamBudget.smallAudioFor(2_500_000))
        assertFalse(StreamBudget.smallAudioFor(2_500_001))
        assertEquals(96_000, StreamBudget.audioBitrate(6, small = true))
        assertEquals(96_000, StreamBudget.audioBitrate(2, small = true))
        assertEquals(640_000, StreamBudget.audioBitrate(6))
        assertEquals(1_000_000 + 96_000, StreamBudget.copyDemand(1_000_000, 6, smallAudio = true))
        assertEquals(1_000_000 * 102 / 100 + 96_000 + 64_000, StreamBudget.copyRequest(1_000_000, 6, smallAudio = true))
    }

    @Test
    fun `bitrates read well at any size`() {
        assertEquals("420 kbit/s", StreamBudget.describe(420_000))
        assertEquals("1.0 Mbit/s", StreamBudget.describe(1_000_000))
        assertEquals("2.5 Mbit/s", StreamBudget.describe(2_500_000))
        assertEquals("16.2 Mbit/s", StreamBudget.describe(16_200_000L))
    }

    @Test
    fun `a tier that copies the source picture asks for the picture with reserve plus the audio`() {
        // The measured case: 1080p copy of a 24 Mbit/s remux, 6 channels converted to 640 kbit/s.
        assertEquals(
            29_440_000,
            StreamBudget.tierDemand(tier(1080), 1080, videoCopied = true, videoBitrate = 24_000_000, audioChannels = 6),
        )
        assertEquals(24_000_000 * 12 / 10 + 384_000, StreamBudget.tierDemand(tier(1080), 1080, true, 24_000_000, 2))
    }

    @Test
    fun `a copy of a small source tier counts the small audio`() {
        val small = StreamBudget.resolutions(1080, 480).first()
        assertEquals(
            800_000 * 12 / 10 + 96_000,
            StreamBudget.tierDemand(small, 480, videoCopied = true, videoBitrate = 800_000, audioChannels = 6),
        )
    }

    @Test
    fun `a transcoded tier asks what a transcode of its size takes`() {
        assertEquals(
            StreamBudget.expectedBitrate(1080),
            StreamBudget.tierDemand(
                tier(1080),
                1080,
                videoCopied = false,
                videoBitrate = 24_000_000,
                audioChannels = 6,
            ),
        )
        assertEquals(
            StreamBudget.expectedBitrate(720),
            StreamBudget.tierDemand(tier(720), 1080, videoCopied = true, videoBitrate = 24_000_000, audioChannels = 6),
        )
        assertEquals(
            tier(2160).expected,
            StreamBudget.tierDemand(
                tier(2160),
                2160,
                videoCopied = false,
                videoBitrate = 24_000_000,
                audioChannels = 6,
            ),
        )
    }

    @Test
    fun `a copy without a known video bitrate is counted as a transcode`() {
        assertEquals(StreamBudget.expectedBitrate(1080), StreamBudget.tierDemand(tier(1080), 1080, true, null, 6))
        assertEquals(StreamBudget.expectedBitrate(1080), StreamBudget.tierDemand(tier(1080), 1080, true, 0, 6))
        assertEquals(StreamBudget.expectedBitrate(1080), StreamBudget.tierDemand(tier(1080), null, true, 24_000_000, 6))
    }

    @Test
    fun `the copy decides which tier a link starts with`() {
        val budget = 14_000_000
        val tiers = StreamBudget.resolutions(1080, 1080)
        val copy = StreamBudget.tierDemand(tiers.first(), 1080, true, 24_000_000, 6)
        val demands = tiers.associate { it.height to if (it.height == 1080) copy else it.expected }
        val start = tiers.first { demands.getValue(it.height) <= budget }
        // As a transcode 1080p (8 Mbit/s) would have fitted; as the copy it does not.
        assertTrue(StreamBudget.expectedBitrate(1080) <= budget)
        assertEquals(720, start.height)
    }
}
