package io.github.glacier_jellyfin.androidtv.ui

import io.github.glacier_jellyfin.androidtv.core.data.media.HdrFormat
import io.github.glacier_jellyfin.androidtv.core.data.media.VideoQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaFormatTest {

    @Test
    fun `german age ratings become plus notation`() {
        assertEquals("12+", ageRatingText("FSK-12"))
        assertEquals("16+", ageRatingText("DE-16"))
        assertEquals("0+", ageRatingText("0"))
        assertEquals("6+", ageRatingText("FSK 6"))
    }

    @Test
    fun `other ratings are kept`() {
        assertEquals("PG-13", ageRatingText("PG-13"))
        assertEquals("TV-MA", ageRatingText("TV-MA"))
        assertNull(ageRatingText(" "))
        assertNull(ageRatingText(null))
    }

    @Test
    fun `quality badge combines resolution and range`() {
        assertEquals("4K HDR", qualityText(VideoQuality(uhd = true, hdr = HdrFormat.Hdr10)))
        assertEquals("4K Dolby Vision", qualityText(VideoQuality(uhd = true, hdr = HdrFormat.DolbyVision)))
        assertEquals("HDR10+", qualityText(VideoQuality(uhd = false, hdr = HdrFormat.Hdr10Plus)))
        assertEquals("4K", qualityText(VideoQuality(uhd = true, hdr = null)))
        assertNull(qualityText(null))
    }
}
