package io.github.glacier_jellyfin.androidtv.core.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class TracksTest {

    @Test
    fun `bibliographic, terminological and two-letter codes normalise`() {
        assertEquals("deu", Languages.iso3("ger"))
        assertEquals("deu", Languages.iso3("deu"))
        assertEquals("deu", Languages.iso3("de"))
        assertEquals("fra", Languages.iso3("fre"))
        assertEquals("jpn", Languages.iso3("JPN"))
    }

    @Test
    fun `unknown or undetermined languages are null`() {
        assertNull(Languages.iso3("und"))
        assertNull(Languages.iso3("xyz"))
        assertNull(Languages.iso3(""))
        assertNull(Languages.iso3(null))
    }

    @Test
    fun `names follow the display language`() {
        assertEquals("Deutsch", Languages.name("ger", Locale.GERMAN))
        assertEquals("German", Languages.name("ger", Locale.ENGLISH))
        assertEquals("Japanisch", Languages.name("jpn", Locale.GERMAN))
    }

    @Test
    fun `channel layouts`() {
        assertEquals("2.0", channelLayout(2))
        assertEquals("5.1", channelLayout(6))
        assertEquals("7.1", channelLayout(8))
        assertEquals("1.0", channelLayout(1))
        assertNull(channelLayout(null))
    }

    @Test
    fun `codec names`() {
        assertEquals("DTS", codecName("dca"))
        assertEquals("TrueHD", codecName("truehd"))
        assertEquals("EAC3", codecName("eac3"))
        assertEquals("FLAC", codecName("flac"))
        assertEquals("AAC", codecName("aac"))
    }
}
