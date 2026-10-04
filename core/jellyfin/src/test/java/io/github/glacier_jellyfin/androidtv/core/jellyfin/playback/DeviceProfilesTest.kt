package io.github.glacier_jellyfin.androidtv.core.jellyfin.playback

import org.jellyfin.sdk.model.api.CodecType
import org.jellyfin.sdk.model.api.DlnaProfileType
import org.jellyfin.sdk.model.api.ProfileConditionValue
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod
import org.jellyfin.sdk.model.api.VideoRangeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceProfilesTest {

    private val fullHdTv = DeviceCapabilities(
        h264 = VideoCodecSupport(1920, 1080, maxLevel = 51, tenBit = false),
        hevc = VideoCodecSupport(3840, 2160, maxLevel = 153, tenBit = true),
        av1 = null,
        vp9 = null,
        otherVideoCodecs = setOf("mpeg2video"),
        audioCodecs = setOf("aac", "mp3", "ac3", "eac3"),
        maxAudioChannels = 6,
        hdr = setOf(HdrType.Hdr10),
    )

    @Test
    fun `direct play offers exactly the decodable codecs`() {
        val video = DeviceProfiles.build(fullHdTv, MAX).directPlayProfiles.single { it.type == DlnaProfileType.VIDEO }
        assertEquals(setOf("h264", "hevc", "mpeg2video"), video.videoCodec!!.split(",").toSet())
        assertEquals(setOf("aac", "ac3", "eac3", "mp3"), video.audioCodec!!.split(",").toSet())
    }

    @Test
    fun `transcoding prefers hevc and keeps the device's surround codecs`() {
        val video = DeviceProfiles.build(fullHdTv, MAX).transcodingProfiles.single { it.type == DlnaProfileType.VIDEO }
        assertEquals("hevc,h264", video.videoCodec)
        assertEquals("aac,eac3,ac3,mp3", video.audioCodec)
        assertEquals("6", video.maxAudioChannels)
    }

    @Test
    fun `video is transcoded to MPEG-TS segments with codecs TS carries`() {
        val tv = fullHdTv.copy(audioCodecs = setOf("aac", "ac3", "eac3", "flac", "opus"))
        val video = DeviceProfiles.build(tv, MAX).transcodingProfiles.single { it.type == DlnaProfileType.VIDEO }
        assertEquals("ts", video.container)
        assertEquals("aac,eac3,ac3", video.audioCodec)
    }

    @Test
    fun `dolby vision devices get fMP4 segments, which keep the dolby vision tag`() {
        val tv = fullHdTv.copy(audioCodecs = setOf("aac", "eac3", "flac"), hdr = setOf(HdrType.Hdr10, HdrType.DolbyVision))
        val video = DeviceProfiles.build(tv, MAX).transcodingProfiles.single { it.type == DlnaProfileType.VIDEO }
        assertEquals("mp4", video.container)
        assertEquals("aac,eac3,flac", video.audioCodec)
        val retry = DeviceProfiles.build(tv, MAX, segments = HlsSegments.Ts).transcodingProfiles.single { it.type == DlnaProfileType.VIDEO }
        assertEquals("ts", retry.container)
    }

    @Test
    fun `a bitstream the receiver takes comes before AAC`() {
        assertEquals(listOf("eac3", "ac3", "aac", "mp3"), DeviceProfiles.hlsAudioOrder(setOf("ac3", "eac3", "dts")))
        assertEquals(listOf("ac3", "aac", "eac3", "mp3"), DeviceProfiles.hlsAudioOrder(setOf("ac3")))
        assertEquals(listOf("aac", "eac3", "ac3", "mp3"), DeviceProfiles.hlsAudioOrder(emptySet()))
    }

    @Test
    fun `resolution and level limits follow the decoder`() {
        val h264 = DeviceProfiles.build(fullHdTv, MAX).codecProfiles.single { it.codec == "h264" }
        val byProperty = h264.conditions.associate { it.property to it.value }
        assertEquals("1920", byProperty[ProfileConditionValue.WIDTH])
        assertEquals("51", byProperty[ProfileConditionValue.VIDEO_LEVEL])
        // No High 10 decoder: 10-bit H.264 has to be transcoded.
        assertEquals("8", byProperty[ProfileConditionValue.VIDEO_BIT_DEPTH])
    }

    @Test
    fun `hdr the display cannot show is left to the server`() {
        val hevcRange = DeviceProfiles.build(fullHdTv, MAX).codecProfiles
            .single { it.codec == "hevc" && it.conditions.any { c -> c.property == ProfileConditionValue.VIDEO_RANGE_TYPE } }
        val ranges = hevcRange.conditions.single().value!!.split("|")
        assertTrue(VideoRangeType.HDR10.serialName in ranges)
        assertTrue(VideoRangeType.DOVI_WITH_HDR10.serialName in ranges)
        assertFalse(VideoRangeType.DOVI.serialName in ranges)
        assertFalse(VideoRangeType.HLG.serialName in ranges)
    }

    @Test
    fun `picture subtitles are never delivered as separate files`() {
        val subtitles = DeviceProfiles.build(fullHdTv, MAX).subtitleProfiles
        assertTrue(subtitles.none { it.format == "pgssub" && it.method == SubtitleDeliveryMethod.EXTERNAL })
        assertTrue(subtitles.any { it.format == "srt" && it.method == SubtitleDeliveryMethod.EXTERNAL })
        assertTrue(subtitles.any { it.format == "pgssub" && it.method == SubtitleDeliveryMethod.EMBED })
    }

    @Test
    fun `burn-in modes only offer burning for the chosen formats`() {
        fun methods(mode: SubtitleBurnIn, format: String) =
            DeviceProfiles.build(fullHdTv, MAX, mode).subtitleProfiles.filter { it.format == format }.map { it.method }.toSet()

        assertEquals(setOf(SubtitleDeliveryMethod.ENCODE), methods(SubtitleBurnIn.PictureFormats, "pgssub"))
        assertEquals(setOf(SubtitleDeliveryMethod.EMBED, SubtitleDeliveryMethod.EXTERNAL), methods(SubtitleBurnIn.PictureFormats, "ass"))
        assertEquals(setOf(SubtitleDeliveryMethod.ENCODE), methods(SubtitleBurnIn.ComplexFormats, "ass"))
        assertEquals(setOf(SubtitleDeliveryMethod.EMBED, SubtitleDeliveryMethod.EXTERNAL), methods(SubtitleBurnIn.ComplexFormats, "srt"))
        assertEquals(setOf(SubtitleDeliveryMethod.ENCODE), methods(SubtitleBurnIn.Always, "srt"))
    }

    @Test
    fun `audio channels are capped for video`() {
        val channels = DeviceProfiles.build(fullHdTv, MAX).codecProfiles.single { it.type == CodecType.VIDEO_AUDIO }
        assertEquals("6", channels.conditions.single().value)
    }

    private companion object {
        const val MAX = 120_000_000
    }
}
