package io.github.glacier_jellyfin.androidtv.core.data.media

import java.time.LocalDate
import java.util.UUID

data class CastMember(
    val id: UUID,
    val name: String,
    /** Character for actors; the job ("Director") is not shown in the design's cast row. */
    val role: String?,
    val imageUrl: String?,
)

data class Season(
    val id: UUID,
    val number: Int?,
    val name: String,
    val unwatchedCount: Int?,
) {
    /** Seen when the server counts nothing left to watch. */
    val played: Boolean get() = unwatchedCount == 0
}

/** A title with everything its detail page shows. */
data class ItemDetails(
    val item: MediaItem,
    val cast: List<CastMember>,
    val tracks: TrackChoices?,
    val trailers: Trailers,
    val seriesId: UUID?,
    val seasonId: UUID?,
    val premiereDate: LocalDate?,
    /** Seasons and episodes of a show. */
    val seasonCount: Int?,
    val episodeCount: Int?,
    val chapters: List<Chapter> = emptyList(),
    val trickplay: Trickplay? = null,
    /** The first of the title's taglines. */
    val tagline: String? = null,
    val directors: List<String> = emptyList(),
    /** Studios of a film, networks of a show. */
    val studios: List<String> = emptyList(),
    /** The title's files when it has several, in the server's order; empty for a single file. */
    val versions: List<MediaVersion> = emptyList(),
    /** All backdrops in server order (the first is [MediaItem.backdropUrl]); empty without any. */
    val backdropUrls: List<String> = emptyList(),
)

/**
 * The library a title is in, to open it from the page's breadcrumb. Without [id], every library of [kind]
 * (music pages that are in no one library, such as an artist's).
 */
data class LibraryRef(
    val id: UUID?,
    val kind: LibraryKind,
    val name: String?,
)

data class EpisodeNeighbours(val previous: MediaItem?, val next: MediaItem?)

/** What the player plays one after another; [upNextPrompt] is false when no "next up" countdown is shown (home videos). */
data class VideoQueue(val items: List<MediaItem>, val upNextPrompt: Boolean = true)

data class Chapter(
    val name: String,
    val startMs: Long,
    val imageUrl: String?,
)

/**
 * Seek preview thumbnails: [count] pictures of [width]×[height], one every
 * [intervalMs], packed [columns]×[rows] per tile image.
 */
data class Trickplay(
    val width: Int,
    val height: Int,
    val columns: Int,
    val rows: Int,
    val count: Int,
    val intervalMs: Long,
    val tileUrls: List<String>,
)

/** A title in a person's filmography, with the part they played. */
data class Credit(val item: MediaItem, val role: String?)

data class PersonDetails(
    /** Null for someone known only to TMDB (through Seerr), not to this server. */
    val id: UUID?,
    val name: String,
    val biography: String?,
    val born: LocalDate?,
    val birthplace: String?,
    val imageUrl: String?,
    val isFavorite: Boolean,
    /** Titles in this server's libraries, newest first. */
    val credits: List<Credit>,
    /** The person's TMDB id, which Seerr knows them by. */
    val tmdbId: Int? = null,
)
