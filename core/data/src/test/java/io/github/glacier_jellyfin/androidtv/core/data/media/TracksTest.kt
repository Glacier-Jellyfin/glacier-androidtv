package io.github.glacier_jellyfin.androidtv.core.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun `flags follow the language and region tags`() {
        assertEquals("de", Languages.flag("ger"))
        assertEquals("gb", Languages.flag("eng"))
        assertEquals("jp", Languages.flag("jpn"))
        assertEquals("fr", Languages.flag("fre"))
        assertEquals("br", Languages.flag("pt-BR"))
        assertEquals("pt", Languages.flag("por"))
        assertEquals("es-ct", Languages.flag("cat"))
        assertNull(Languages.flag("und"))
        assertNull(Languages.flag(null))
    }

    @Test
    fun `forced shows in the stream name too`() {
        assertTrue(forcedByName("German Forced"))
        assertTrue(forcedByName("Deutsch (erzwungen)"))
        assertTrue(forcedByName("Signs & Songs"))
        assertFalse(forcedByName("German Full"))
        assertFalse(forcedByName("Unforced Errors"))
        assertFalse(forcedByName(null))
    }

    private val japanese = Track(index = 1, language = "jpn", codec = "truehd", channels = 2)
    private val german = Track(index = 2, language = "ger", codec = "ac3", channels = 2)
    private val full = Track(index = 4, language = "ger", codec = "PGSSUB", channels = null, title = "German Full")
    private val forced = Track(index = 5, language = "ger", codec = "PGSSUB", channels = null, forced = true, title = "German Forced")
    private val subtitles = listOf(Track(index = 0, language = "jpn", codec = "subrip", channels = null), full, forced)

    @Test
    fun `a forced pick under other audio becomes the full track`() {
        assertEquals(4, fullInsteadOfForced(5, subtitles, japanese, onlyForced = false))
    }

    @Test
    fun `a forced pick stays when asked for, under its own audio, or without a full track`() {
        assertEquals(5, fullInsteadOfForced(5, subtitles, japanese, onlyForced = true))
        assertEquals(5, fullInsteadOfForced(5, subtitles, german, onlyForced = false))
        assertEquals(5, fullInsteadOfForced(5, listOf(forced), japanese, onlyForced = false))
    }

    @Test
    fun `full picks and no pick stay as they are`() {
        assertEquals(4, fullInsteadOfForced(4, subtitles, japanese, onlyForced = false))
        assertNull(fullInsteadOfForced(null, subtitles, japanese, onlyForced = false))
    }

    @Test
    fun `the full track without SDH comes first`() {
        val sdh = full.copy(index = 3, hearingImpaired = true)
        assertEquals(4, fullInsteadOfForced(5, listOf(sdh, full, forced), japanese, onlyForced = false))
    }
}
