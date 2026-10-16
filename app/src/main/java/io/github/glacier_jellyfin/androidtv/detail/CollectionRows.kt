package io.github.glacier_jellyfin.androidtv.detail

import io.github.glacier_jellyfin.androidtv.core.data.media.ItemKind
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaItem

/** The rows of a collection page, one per kind of title, in this order. */
enum class CollectionRow { Movies, Shows, Videos, Seasons, Episodes, MusicVideos, Music, Artists, People, More }

/** Kinds that are watched: a collection's seen count, years, rating and genres come from these alone. */
val WatchableInCollection = setOf(ItemKind.Movie, ItemKind.Series, ItemKind.Season, ItemKind.Episode, ItemKind.MusicVideo)

/** Kinds whose songs play when a collection holds nothing to watch. */
val MusicInCollection = setOf(ItemKind.Album, ItemKind.Song, ItemKind.Playlist)

/**
 * [items] in one row per kind, each in the server's order (chronological).
 * With [moviesWithShows] the movies and shows share one row, but only when there are both.
 */
fun collectionRows(items: List<MediaItem>, moviesWithShows: Boolean): List<Pair<CollectionRow, List<MediaItem>>> {
    val rows = items.groupBy { rowOf(it.kind) }
    val merge = moviesWithShows && CollectionRow.Movies in rows && CollectionRow.Shows in rows
    val merged = if (merge) {
        rows - CollectionRow.Movies - CollectionRow.Shows +
            (CollectionRow.Videos to items.filter { it.kind == ItemKind.Movie || it.kind == ItemKind.Series })
    } else {
        rows
    }
    return merged.toList().sortedBy { it.first.ordinal }
}

private fun rowOf(kind: ItemKind): CollectionRow = when (kind) {
    ItemKind.Movie -> CollectionRow.Movies
    ItemKind.Series -> CollectionRow.Shows
    ItemKind.Season -> CollectionRow.Seasons
    ItemKind.Episode -> CollectionRow.Episodes
    ItemKind.MusicVideo -> CollectionRow.MusicVideos
    ItemKind.Album, ItemKind.Song, ItemKind.Playlist -> CollectionRow.Music
    ItemKind.Artist -> CollectionRow.Artists
    ItemKind.Person -> CollectionRow.People
    ItemKind.Collection, ItemKind.Genre, ItemKind.Other -> CollectionRow.More
}
