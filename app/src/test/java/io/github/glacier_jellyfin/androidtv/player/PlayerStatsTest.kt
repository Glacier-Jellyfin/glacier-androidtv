package io.github.glacier_jellyfin.androidtv.player

import androidx.media3.common.MimeTypes
import io.github.glacier_jellyfin.androidtv.core.data.playback.SourceAudio
import io.github.glacier_jellyfin.androidtv.core.data.playback.SourceVideo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerStatsTest {

    @Test
    fun `known codecs get their common names`() {
        assertEquals("HEVC", codecText(MimeTypes.VIDEO_H265, null))
        assertEquals("EAC3", codecText(MimeTypes.AUDIO_E_AC3, null))
        assertEquals("TrueHD", codecText(MimeTypes.AUDIO_TRUEHD, null))
    }

    @Test
    fun `unknown codecs fall back to the mime subtype`() {
        assertEquals("WMV3", codecText("video/x-wmv3", null))
        assertEquals("AVC1", codecText(null, "avc1.640028"))
    }

    @Test
    fun `bitrates read as kbit or Mbit`() {
        assertEquals("640 kbit/s", bitrateText(640_000))
        assertEquals("18.2 Mbit/s", bitrateText(18_200_000))
        assertNull(bitrateText(-1))
    }

    @Test
    fun `frame rates match refresh rates they divide evenly`() {
        assertTrue(frameRateMatches(23.976f, 23.976f))
        assertTrue(frameRateMatches(59.94f, 29.97f))
        assertTrue(frameRateMatches(50f, 25f))
        assertTrue(frameRateMatches(120f, 24f))
        assertFalse(frameRateMatches(60f, 23.976f))
        assertFalse(frameRateMatches(24f, 23.976f))
        assertFalse(frameRateMatches(60f, 25f))
    }

    @Test
    fun `server and player codec names meet in one family`() {
        assertEquals(serverCodecFamily("hevc"), mimeCodecFamily(MimeTypes.VIDEO_DOLBY_VISION))
        assertEquals(serverCodecFamily("dca"), mimeCodecFamily(MimeTypes.AUDIO_DTS_HD))
        assertEquals(serverCodecFamily("eac3"), mimeCodecFamily(MimeTypes.AUDIO_E_AC3_JOC))
        assertEquals(serverCodecFamily("pcm_s24le"), mimeCodecFamily(MimeTypes.AUDIO_RAW))
        assertNotEquals(serverCodecFamily("truehd"), mimeCodecFamily(MimeTypes.AUDIO_AAC))
    }

    @Test
    fun `source audio prefers the DTS profile and marks Atmos`() {
        assertEquals("DTS-HD MA 7.1", sourceAudioText(SourceAudio(1, "dts", "DTS-HD MA", 8, 48_000, null)))
        assertEquals("EAC3 Atmos 5.1", sourceAudioText(SourceAudio(1, "eac3", "Dolby Digital Plus + Dolby Atmos", 6, 48_000, null)))
        assertEquals("AAC 2.0", sourceAudioText(SourceAudio(1, "aac", "LC", 2, 48_000, null)))
    }

    @Test
    fun `dolby vision ranges name profile and fallback`() {
        val video = SourceVideo("hevc", "Main 10", 3840, 2160, 10, 23.976f, "DOVIWithHDR10", 8)
        assertEquals("Dolby Vision P8 · HDR10", rangeText(video))
        assertEquals("HDR10+", rangeText(video.copy(rangeType = "HDR10Plus")))
        assertNull(rangeText(video.copy(rangeType = null)))
    }

    @Test
    fun `software decoders are told from hardware ones`() {
        assertTrue(isSoftwareDecoder("c2.android.aac.decoder"))
        assertTrue(isSoftwareDecoder("ffmpegLib"))
        assertFalse(isSoftwareDecoder("c2.amlogic.hevc.decoder"))
        assertFalse(isSoftwareDecoder("OMX.MTK.VIDEO.DECODER.HEVC"))
    }
}
