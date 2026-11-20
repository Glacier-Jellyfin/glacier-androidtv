package io.github.glacier_jellyfin.androidtv.ui

import io.github.glacier_jellyfin.androidtv.core.data.media.ItemKind
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaItem
import java.util.UUID
import org.junit.Assert.assertFalse
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
