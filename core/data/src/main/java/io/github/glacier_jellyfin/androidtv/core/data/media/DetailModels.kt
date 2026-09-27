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
)
