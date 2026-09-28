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

    private val de = listOf(Locale.GERMAN)

    private fun sub(title: String?, codec: String, forced: Boolean = false, language: String = "ger") =
        Track(index = 0, language = language, codec = codec, channels = null, forced = forced, title = title)

    private fun audio(title: String?, codec: String, channels: Int, language: String = "ger") =
        Track(index = 0, language = language, codec = codec, channels = channels, title = title)

    @Test
    fun `subtitle names lose what the label already says`() {
        assertEquals(listOf("CR/ASS"), trackBadges(sub("German (CR/ASS)(forced)", "ass", forced = true), true, de))
        assertEquals(listOf("BD/PGS"), trackBadges(sub("German (BD/PGS)", "PGSSUB"), true, de))
        assertEquals(listOf("DIY", "ASS"), trackBadges(sub("FORCED [DIY]", "ass", forced = true), true, de))
        assertEquals(listOf("Full", "ASS"), trackBadges(sub("Full", "ass"), true, de))
        assertEquals(listOf("PGS"), trackBadges(sub("German-Forced", "PGSSUB", forced = true), true, de))
    }

    @Test
    fun `subtitles without a name show their format`() {
        assertEquals(listOf("SRT"), trackBadges(sub(null, "subrip", language = "jpn"), true, de))
        assertEquals(listOf("PGS"), trackBadges(sub("", "PGSSUB"), true, de))
    }

    @Test
    fun `audio shows the format and only informative names`() {
        assertEquals(listOf("2.0 (AC3)"), trackBadges(audio(null, "ac3", 2), false, de))
        assertEquals(listOf("5.1 (DTS)"), trackBadges(audio("DTS 5.1 @ 768 kbps", "dts", 6), false, de))
        assertEquals(listOf("2.0 (FLAC)"), trackBadges(audio("German", "flac", 2), false, de))
        assertEquals(listOf("2.0 (TRUEHD)").size, trackBadges(audio("Stereo", "truehd", 2), false, de).size)
        assertEquals(listOf("2.0 (AAC)", "Commentary"), trackBadges(audio("Commentary", "aac", 2, "eng"), false, de))
    }
}
