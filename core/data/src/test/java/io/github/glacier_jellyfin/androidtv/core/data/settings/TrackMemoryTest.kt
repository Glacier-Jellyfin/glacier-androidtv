package io.github.glacier_jellyfin.androidtv.core.data.settings

import io.github.glacier_jellyfin.androidtv.core.data.media.Track
import io.github.glacier_jellyfin.androidtv.core.data.media.TrackChoices
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackMemoryTest {

    private val german = Track(index = 1, language = "ger", codec = "dts", channels = 6)
    private val japanese = Track(index = 2, language = "jpn", codec = "flac", channels = 2)
    private val germanForced = Track(index = 3, language = "ger", codec = "ass", channels = null, forced = true, title = "Signs")
    private val germanFull = Track(index = 4, language = "deu", codec = "ass", channels = null, title = "Full")
    private val englishSdh = Track(index = 5, language = "eng", codec = "srt", channels = null, hearingImpaired = true)

    private val choices = TrackChoices(
        audio = listOf(german, japanese),
        subtitles = listOf(germanForced, germanFull, englishSdh),
        defaultAudio = 1,
        defaultSubtitle = null,
    )

    @Test
    fun `language decides, whichever code form the file uses`() {
        assertEquals(germanFull, closestTrack(TrackKey("ger"), choices.subtitles))
        assertEquals(japanese, closestTrack(TrackKey("jpn"), choices.audio))
        assertNull(closestTrack(TrackKey("fra"), choices.audio))
    }

    @Test
    fun `forced subtitles follow forced ones`() {
        assertEquals(germanForced, closestTrack(TrackKey("deu", forced = true), choices.subtitles))
    }

    @Test
    fun `the name breaks a tie`() {
        val second = germanFull.copy(index = 6, title = "Commentary")
        assertEquals(second, closestTrack(TrackKey("ger", title = "commentary"), listOf(germanFull, second)))
    }

    @Test
    fun `remembered tracks respect the switches`() {
        val last = LastTracks(audio = japanese.toKey(), subtitle = null, subtitlesOff = true)
        assertEquals(RememberedTracks(2, RememberedTracks.SUBTITLES_OFF), rememberedTracks(last, ServerPreferences(), choices))
        assertEquals(
            RememberedTracks(null, null),
            rememberedTracks(last, ServerPreferences(rememberAudio = false, rememberSubtitles = false), choices),
        )
    }

    @Test
    fun `nothing remembered leaves the choice to the server`() {
        assertEquals(RememberedTracks(null, null), rememberedTracks(LastTracks(), ServerPreferences(), choices))
    }
}
