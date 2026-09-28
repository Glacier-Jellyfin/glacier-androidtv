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

data class Trailers(
    val localCount: Int,
    /** YouTube and other remote trailer links. */
    val remote: List<String>,
) {
    val any: Boolean get() = localCount > 0 || remote.isNotEmpty()
}

data class Season(
    val id: UUID,
    val number: Int?,
    val name: String,
    val unwatchedCount: Int?,
)

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
)

data class Chapter(val name: String, val startMs: Long, val imageUrl: String?)

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
    val id: UUID,
    val name: String,
    val biography: String?,
    val born: LocalDate?,
    val birthplace: String?,
    val imageUrl: String?,
    val isFavorite: Boolean,
    /** Titles in this server's libraries, newest first. */
    val credits: List<Credit>,
)
