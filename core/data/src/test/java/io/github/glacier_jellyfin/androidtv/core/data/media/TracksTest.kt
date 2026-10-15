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

    private fun sub(title: String?, codec: String, forced: Boolean = false, language: String = "ger", sdh: Boolean = false) =
        Track(index = 0, language = language, codec = codec, channels = null, forced = forced, hearingImpaired = sdh, title = title)

    private fun audio(title: String?, codec: String, channels: Int, language: String = "ger", profile: String? = null) =
        Track(
            index = 0, language = language, codec = codec, channels = channels, title = title, profile = profile,
            commentary = commentaryByName(title), audioDescription = audioDescriptionByName(title),
        )

    private fun facts(track: Track, subtitle: Boolean) = describeTrack(track, subtitle, de).facts

    @Test
    fun `subtitle names lose what the row already says`() {
        assertEquals(listOf("CR/ASS"), facts(sub("German (CR/ASS)(forced)", "ass", forced = true), true))
        assertEquals(listOf("BD/PGS"), facts(sub("German (BD/PGS)", "PGSSUB"), true))
        assertEquals(listOf("DIY", "ASS"), facts(sub("FORCED [DIY]", "ass", forced = true), true))
        assertEquals(listOf("ASS"), facts(sub("Full", "ass"), true))
        assertEquals(listOf("PGS"), facts(sub("German Full", "PGSSUB"), true))
        assertEquals(listOf("PGS"), facts(sub("German-Forced", "PGSSUB", forced = true), true))
        assertEquals(listOf("SRT"), facts(sub("English SDH", "subrip", language = "eng", sdh = true), true))
    }

    @Test
    fun `subtitles name the language and mark forced and SDH`() {
        val forced = describeTrack(sub("German Forced", "PGSSUB", forced = true), true, de)
        assertEquals("Deutsch", forced.name)
        assertEquals(listOf(TrackMark.Forced), forced.marks)
        assertEquals(listOf(TrackMark.HearingImpaired), describeTrack(sub("SDH", "subrip", sdh = true), true, de).marks)
    }

    @Test
    fun `without a language the stream name leads, else the format`() {
        val named = describeTrack(sub("Signs & Songs", "ass", language = "und"), true, de)
        assertEquals("Signs & Songs", named.name)
        assertEquals(listOf("ASS"), named.facts)
        val bare = describeTrack(sub(null, "subrip", language = "und"), true, de)
        assertEquals("SRT", bare.name)
        assertEquals(emptyList<String>(), bare.facts)
    }

    @Test
    fun `subtitles without a name show their format`() {
        assertEquals(listOf("SRT"), facts(sub(null, "subrip", language = "jpn"), true))
        assertEquals(listOf("PGS"), facts(sub("", "PGSSUB"), true))
    }

    @Test
    fun `audio shows the format and only informative names`() {
        assertEquals(listOf("AC3 2.0"), facts(audio(null, "ac3", 2), false))
        assertEquals(listOf("DTS 5.1"), facts(audio("DTS 5.1 @ 768 kbps", "dts", 6), false))
        assertEquals(listOf("FLAC 2.0"), facts(audio("German", "flac", 2), false))
        assertEquals(listOf("TrueHD 2.0"), facts(audio("Stereo", "truehd", 2), false))
        assertEquals(listOf("AAC 2.0", "Director's Cut"), facts(audio("Director's Cut", "aac", 2, "eng"), false))
        assertEquals(listOf("TrueHD Atmos 7.1", "BTM"), facts(audio("BTM TrueHD Atmos", "truehd", 8, "eng", "Dolby TrueHD + Dolby Atmos"), false))
    }

    @Test
    fun `commentary and audio description are marks, not facts`() {
        val commentary = describeTrack(audio("Director's Commentary", "aac", 2, "eng"), false, de)
        assertEquals(listOf("AAC 2.0"), commentary.facts)
        assertEquals(listOf(TrackMark.Commentary), commentary.marks)
        val described = describeTrack(audio("Deutsch AD", "ac3", 2), false, de)
        assertEquals(listOf("AC3 2.0"), described.facts)
        assertEquals(listOf(TrackMark.AudioDescription), described.marks)
        assertTrue(audioDescriptionByName("Hörfilm"))
        assertTrue(audioDescriptionByName("Deutsch Hörfilmfassung"))
        assertFalse(audioDescriptionByName("Advanced"))
        assertTrue(commentaryByName("Audiokommentar"))
    }

    @Test
    fun `audio formats name DTS kinds and Atmos`() {
        assertEquals("DTS-HD MA 5.1", audioFormat("dts", "DTS-HD MA", 6))
        assertEquals("TrueHD Atmos 7.1", audioFormat("truehd", "Dolby TrueHD + Dolby Atmos", 8))
        assertEquals("PCM 2.0", audioFormat("pcm_s16le", null, 2))
        assertEquals("AAC", audioFormat("aac", "LC", null))
        assertNull(audioFormat(null, null, null))
    }

    @Test
    fun `SDH shows in the stream name too`() {
        assertTrue(hearingImpairedByName("English SDH"))
        assertTrue(hearingImpairedByName("Deutsch (Hörgeschädigt)"))
        assertFalse(hearingImpairedByName("CC"))
        assertFalse(hearingImpairedByName("Accent"))
    }

    @Test
    fun `closed captions are their own mark`() {
        assertTrue(closedCaptionsByName("English CC"))
        assertTrue(closedCaptionsByName("Closed Captions"))
        assertFalse(closedCaptionsByName("Accent"))
        val cc = Track(index = 0, language = "eng", codec = "subrip", channels = null, closedCaptions = true, title = "English CC")
        val described = describeTrack(cc, true, de)
        assertEquals(listOf(TrackMark.ClosedCaptions), described.marks)
        assertEquals(listOf("SRT"), described.facts)
    }

    @Test
    fun `language variants come from the tag or the stream name`() {
        assertEquals("BR", regionOf("pt-BR", null))
        assertEquals("BR", regionOf("pob", null))
        assertEquals("CA", regionOf("fre", "BTM CA"))
        assertEquals("FR", regionOf("fre", "BTM FR"))
        assertEquals("419", regionOf("spa", "Spanish (Latin American)"))
        assertNull(regionOf("ger", "BTM CA"))
        assertNull(regionOf("fre", "BTM"))
        assertEquals("BE", regionOf("dut", "Dutch (Flemish)"))
        assertEquals("CH", regionOf("ger", "Schweizerdeutsch"))
        assertNull(regionOf("ger", "German AT"))
        assertEquals("AR", regionOf("spa", "Argentina"))
        assertEquals("be", Languages.flag("dut", "BE"))
        assertEquals("de", Languages.flag("de-LI"))
        assertEquals("Hant", regionOf("chi", "Chinese (Traditional)"))
        assertEquals("Hans", regionOf("zh-Hans", null))
        assertEquals("tw", Languages.flag("chi", "Hant"))
        assertEquals("cn", Languages.flag("chi", "Hans"))
        assertEquals("chinesisch (traditionell)", Languages.name("chi", Locale.GERMAN, "Hant")?.lowercase())
        assertEquals("chinesisch (vereinfacht)", Languages.name("chi", Locale.GERMAN, "Hans")?.lowercase())
    }

    @Test
    fun `a variant names its region and shows its flag`() {
        val canadian = Track(index = 0, language = "fre", codec = "PGSSUB", channels = null, title = "BTM CA", region = "CA")
        val described = describeTrack(canadian, true, de)
        assertEquals("Französisch (Kanada)", described.name)
        assertEquals(listOf("BTM", "PGS"), described.facts)
        assertEquals("ca", Languages.flag("fre", "CA"))
        assertEquals("mx", Languages.flag("spa", "419"))
        assertEquals("br", Languages.flag("pt-BR"))
        assertEquals("Portugiesisch (Brasilien)", Languages.name("pt-BR", Locale.GERMAN))
        assertEquals("deu", Languages.iso3("de-DE"))
        assertEquals("Französisch", Languages.name("fre", Locale.GERMAN, "FR"))
        assertEquals("Spanisch", Languages.name("es-ES", Locale.GERMAN))
        assertEquals("Englisch (Vereinigte Staaten)", Languages.name("eng", Locale.GERMAN, "US"))
    }

    @Test
    fun `a Chinese track named Cantonese is Cantonese`() {
        assertEquals("yue", cantoneseOr("chi", "Cantonese"))
        assertEquals("chi", cantoneseOr("chi", "Mandarin"))
        assertEquals("hk", Languages.flag("yue"))
        assertEquals("Kantonesisch", Languages.name("yue", Locale.GERMAN))
        assertEquals("yue", Languages.iso3("yue"))
    }

    @Test
    fun `an extra audio pick becomes the main track`() {
        val main = Track(index = 1, language = "eng", codec = "dts", channels = 6)
        val commentary = Track(index = 2, language = "eng", codec = "aac", channels = 2, commentary = true)
        val german = Track(index = 3, language = "ger", codec = "ac3", channels = 6)
        assertEquals(1, mainInsteadOfExtra(2, listOf(german, main, commentary)))
        assertEquals(3, mainInsteadOfExtra(2, listOf(german, commentary)))
        assertEquals(2, mainInsteadOfExtra(2, listOf(commentary)))
        assertEquals(1, mainInsteadOfExtra(1, listOf(main, commentary)))
        assertNull(mainInsteadOfExtra(null, listOf(main)))
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
    fun `the subtitle follows the audio it plays under`() {
        val choices = TrackChoices(
            audio = listOf(japanese, german),
            subtitles = subtitles,
            defaultAudio = 1,
            defaultSubtitle = 4,
            serverSubtitle = 5,
        )
        assertEquals(4, choices.subtitleFor(1))
        assertEquals(5, choices.subtitleFor(2))
    }

    @Test
    fun `the full track without SDH comes first`() {
        val sdh = full.copy(index = 3, hearingImpaired = true)
        assertEquals(4, fullInsteadOfForced(5, listOf(sdh, full, forced), japanese, onlyForced = false))
    }
}
