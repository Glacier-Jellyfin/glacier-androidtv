package io.github.glacier_jellyfin.androidtv.core.data.media

import java.util.UUID

/** Library types Glacier shows in v1; everything else is hidden. */
enum class LibraryKind { Movies, Shows, Music }

data class Library(
    val id: UUID,
    val name: String,
    val kind: LibraryKind,
    val itemCount: Int?,
    val imageUrl: String?,
)

enum class ItemKind { Movie, Series, Episode, Album, Collection, Genre, Artist, Playlist, Other }

/** Resolution and dynamic range, as far as the server reports them. */
data class VideoQuality(val uhd: Boolean, val hdr: HdrFormat?)

enum class HdrFormat { Hdr10, Hdr10Plus, Hlg, DolbyVision }

/** An item as the browsing screens need it; built from the server's BaseItemDto. */
data class MediaItem(
    val id: UUID,
    val kind: ItemKind,
    val title: String,
    /** The server's sort name: lower case, leading articles removed. */
    val sortName: String?,
    val year: Int?,
    val communityRating: Float?,
    val officialRating: String?,
    val runtimeMinutes: Int?,
    val genres: List<String>,
    val overview: String?,
    /** Series name for episodes, album artist for albums. */
    val parentTitle: String?,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val progress: Float?,
    val remainingMinutes: Int?,
    /** Exact resume point; 0 when not started. */
    val resumePositionMs: Long = 0,
    val unwatchedCount: Int?,
    val isFavorite: Boolean,
    val played: Boolean,
    /** Titles in a collection, genre or playlist; albums of an artist. */
    val childCount: Int?,
    val quality: VideoQuality?,
    /** 2:3 poster (or square album cover). */
    val posterUrl: String?,
    /** 16:9 still for "continue watching": thumb, backdrop or episode image. */
    val thumbUrl: String?,
    /** Full-screen backdrop for the spotlight. */
    val backdropUrl: String?,
)

/** Everything the home screen shows, loaded in one go. */
data class HomeContent(
    val libraries: List<Library>,
    val continueWatching: List<MediaItem>,
    val latest: List<Pair<Library, List<MediaItem>>>,
)
