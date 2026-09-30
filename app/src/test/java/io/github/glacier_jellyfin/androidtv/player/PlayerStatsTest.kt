package io.github.glacier_jellyfin.androidtv.player

import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
