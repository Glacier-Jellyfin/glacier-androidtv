package io.github.glacier_jellyfin.androidtv.detail

import io.github.glacier_jellyfin.androidtv.core.data.media.ItemKind
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

class CollectionRowsTest {

    private fun item(kind: ItemKind, title: String) = MediaItem(
        id = UUID.randomUUID(),
        kind = kind,
        title = title,
        sortName = null,
        year = null,
        communityRating = null,
        officialRating = null,
        runtimeMinutes = null,
        genres = emptyList(),
        overview = null,
        parentTitle = null,
        seasonNumber = null,
        episodeNumber = null,
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

    private val mixed = listOf(
        item(ItemKind.Person, "Actor"),
        item(ItemKind.Movie, "First"),
        item(ItemKind.Series, "Show"),
        item(ItemKind.Song, "Song"),
        item(ItemKind.Season, "Season 1"),
        item(ItemKind.Movie, "Second"),
        item(ItemKind.Album, "Album"),
    )

    private fun titles(rows: List<Pair<CollectionRow, List<MediaItem>>>) = rows.map { (row, items) -> row to items.map { it.title } }

    @Test
    fun `one row per kind, videos first, each in the given order`() {
        assertEquals(
            listOf(
                CollectionRow.Movies to listOf("First", "Second"),
                CollectionRow.Shows to listOf("Show"),
                CollectionRow.Seasons to listOf("Season 1"),
                CollectionRow.Music to listOf("Song", "Album"),
                CollectionRow.People to listOf("Actor"),
            ),
            titles(collectionRows(mixed, moviesWithShows = false)),
        )
    }

    @Test
    fun `movies and shows share a row when asked`() {
        assertEquals(
            listOf(
                CollectionRow.Videos to listOf("First", "Show", "Second"),
                CollectionRow.Seasons to listOf("Season 1"),
                CollectionRow.Music to listOf("Song", "Album"),
                CollectionRow.People to listOf("Actor"),
            ),
            titles(collectionRows(mixed, moviesWithShows = true)),
        )
    }

    @Test
    fun `movies alone stay movies when sharing is on`() {
        val movies = listOf(item(ItemKind.Movie, "First"), item(ItemKind.Movie, "Second"))
        assertEquals(listOf(CollectionRow.Movies to listOf("First", "Second")), titles(collectionRows(movies, moviesWithShows = true)))
    }
}
