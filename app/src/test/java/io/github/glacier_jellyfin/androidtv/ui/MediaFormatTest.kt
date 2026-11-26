package io.github.glacier_jellyfin.androidtv.ui

import io.github.glacier_jellyfin.androidtv.core.data.media.ItemKind
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaItem
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaFormatTest {

    private fun episode(seasonNumber: Int?) = MediaItem(
        id = UUID.randomUUID(),
        kind = ItemKind.Episode,
        title = "Episode",
        sortName = null,
        year = null,
        communityRating = null,
        officialRating = null,
        runtimeMinutes = null,
        genres = emptyList(),
        overview = null,
        parentTitle = null,
        seasonNumber = seasonNumber,
        episodeNumber = 1,
        progress = null,
        remainingMinutes = null,
        unwatchedCount = null,
        isFavorite = false,
        played = false,
        childCount = null,
        quality = null,
        posterUrl = null,
        thumbUrl = null,
        backdropUrl = null,
    )

    @Test
    fun `lengths under a minute are shown in seconds`() {
        assertEquals(RuntimeParts.Seconds(1), runtimeParts(1_000))
        assertEquals(RuntimeParts.Seconds(3), runtimeParts(3_400))
        assertEquals(RuntimeParts.Seconds(59), runtimeParts(59_999))
    }

    @Test
    fun `lengths from a minute are shown in minutes and hours`() {
        assertEquals(RuntimeParts.Minutes(1), runtimeParts(60_000))
        assertEquals(RuntimeParts.Minutes(59), runtimeParts(3_599_000))
        assertEquals(RuntimeParts.Hours(1, 1), runtimeParts(61 * 60_000L))
    }

    @Test
    fun `no length gives nothing`() {
        assertNull(runtimeParts(null))
        assertNull(runtimeParts(0))
        assertNull(runtimeParts(999))
    }

    @Test
    fun `a special in a regular season is an inserted special`() {
        assertTrue(isInsertedSpecial(episode(0), 2))
    }

    @Test
    fun `specials in the specials season are not marked`() {
        assertFalse(isInsertedSpecial(episode(0), 0))
    }

    @Test
    fun `regular episodes and unknown seasons are not marked`() {
        assertFalse(isInsertedSpecial(episode(2), 2))
        assertFalse(isInsertedSpecial(episode(0), null))
        assertFalse(isInsertedSpecial(episode(null), 2))
    }
}
