package io.github.glacier_jellyfin.androidtv.core.data.playback

import io.github.glacier_jellyfin.androidtv.core.data.media.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

class TrackOrdinalTest {

    private fun source(audio: List<Int>, subtitles: List<Pair<Int, SubtitleDelivery>>) = PlaybackSource(
        itemId = UUID(0, 1),
        mediaSourceId = null,
        playSessionId = null,
        url = "",
        isHls = false,
        method = PlaybackMethod.DirectPlay,
        headers = emptyMap(),
        audioIndex = null,
        subtitleIndex = null,
        audioTracks = audio.map { Track(it, null, null, null) },
        subtitles = subtitles.map { (index, delivery) -> PlaybackSubtitle(Track(index, null, null, null), delivery, null) },
    )

    @Test
    fun `audio streams are counted in file order`() {
        val source = source(audio = listOf(3, 1, 2), subtitles = emptyList())
        assertEquals(0, source.audioOrdinal(1))
        assertEquals(2, source.audioOrdinal(3))
        assertNull(source.audioOrdinal(7))
    }

    @Test
    fun `only subtitles inside the file count for Media3's text tracks`() {
        val source = source(
            audio = listOf(1),
            subtitles = listOf(4 to SubtitleDelivery.Embedded, 5 to SubtitleDelivery.External, 6 to SubtitleDelivery.Embedded),
        )
        assertEquals(1, source.subtitleOrdinal(6))
        assertNull(source.subtitleOrdinal(5))
    }
}
