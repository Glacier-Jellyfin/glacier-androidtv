package io.github.glacier_jellyfin.androidtv.core.data.media

import io.github.glacier_jellyfin.androidtv.core.data.settings.SpotlightType
import java.time.LocalDateTime
import java.util.UUID
import kotlin.random.Random
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind

/**
 * The libraries of [kind] among the user's [views], without the [recordingFolders]. Live TV
 * keeps its recordings in ordinary libraries, which must not show up among movies and shows.
 */
internal fun libraryIdsOf(
    views: List<BaseItemDto>,
    kind: LibraryKind,
    recordingFolders: Set<UUID>,
): List<UUID> = views
    .filter { it.collectionType.toLibraryKind() == kind && it.id !in recordingFolders }
    .map { it.id }
    .distinct()

/**
 * The genres of several libraries as one list: a genre that several libraries have is
 * one card, with the titles of all of them counted (see [MediaMapper]).
 */
internal fun mergeGenres(genres: List<BaseItemDto>): List<BaseItemDto> {
    fun Int?.plus(other: Int?): Int? = if (this == null && other == null) null else (this ?: 0) + (other ?: 0)
    val merged = LinkedHashMap<UUID, BaseItemDto>()
    for (genre in genres) {
        val seen = merged[genre.id]
        merged[genre.id] = if (seen == null) {
            genre
        } else {
            seen.copy(
                movieCount = seen.movieCount.plus(genre.movieCount),
                seriesCount = seen.seriesCount.plus(genre.seriesCount),
                childCount = seen.childCount.plus(genre.childCount),
            )
        }
    }
    return merged.values.toList()
}

/**
 * Whether the titles of this query are asked library by library and merged, as Jellyfin Web
 * does for one library. Asked across all libraries, the server would list recordings too.
 * Collections do not live inside the libraries and stay server-wide.
 */
internal val LibraryQuery.mergesLibraries: Boolean
    get() = libraryId == null && (kind == LibraryKind.Movies || kind == LibraryKind.Shows) &&
        (scope == LibraryScope.All || scope == LibraryScope.Genres)

/**
 * The parents to ask for this query: the library chosen, each library of the kind
 * ([libraries], only asked when needed) or null for the whole server. None means nothing to list.
 */
internal inline fun LibraryQuery.parents(libraries: () -> List<UUID>): List<UUID?> =
    if (mergesLibraries) libraries() else listOf(libraryId)

/** A movie or show library, as the lists of titles ask for it. */
internal data class KindLibrary(val id: UUID, val kind: LibraryKind) {
    /** What this library holds at its top: films or series. */
    val itemType: BaseItemKind
        get() = if (kind == LibraryKind.Movies) BaseItemKind.MOVIE else BaseItemKind.SERIES
}

/**
 * The movie and show libraries among the user's [views] that are one of [kinds], without the
 * [recordingFolders], in the order of the views.
 */
internal fun libraryKindsOf(
    views: List<BaseItemDto>,
    kinds: Set<LibraryKind>,
    recordingFolders: Set<UUID>,
): List<KindLibrary> = views
    .mapNotNull { view ->
        val kind = view.collectionType.toLibraryKind()
        if (kind != null && kind in kinds && view.id !in recordingFolders) KindLibrary(view.id, kind) else null
    }
    .distinctBy { it.id }

/** The library kinds a spotlight of this type draws from. */
internal val SpotlightType.libraryKinds: Set<LibraryKind>
    get() = when (this) {
        SpotlightType.All -> setOf(LibraryKind.Movies, LibraryKind.Shows)
        SpotlightType.Movies -> setOf(LibraryKind.Movies)
        SpotlightType.Shows -> setOf(LibraryKind.Shows)
    }

/**
 * Runs [request] for every library at once. No library means no request and an empty list.
 */
internal suspend fun <T> perLibrary(
    libraries: List<KindLibrary>,
    request: suspend (KindLibrary) -> List<T>,
): List<List<T>> = if (libraries.isEmpty()) {
    emptyList()
} else {
    coroutineScope { libraries.map { async { request(it) } }.awaitAll() }
}

/** The [results] of several libraries as one list: sorted by [order], each title once, cut at [limit]. */
internal fun mergeLimited(
    results: List<List<BaseItemDto>>,
    limit: Int,
    order: Comparator<BaseItemDto>,
): List<BaseItemDto> = results.flatten().distinctBy { it.id }.sortedWith(order).take(limit)

/** The [results] of several libraries as [limit] titles in random order, each title once. */
internal fun mergeShuffled(
    results: List<List<BaseItemDto>>,
    limit: Int,
    random: Random,
): List<BaseItemDto> = results.flatten().distinctBy { it.id }.shuffled(random).take(limit)

/** Titles added last first, those without a date at the end. */
internal val byDateCreatedDesc: Comparator<BaseItemDto> =
    compareByDescending(nullsFirst<LocalDateTime>()) { it.dateCreated }

/** Titles released last first, those without a date at the end. */
internal val byPremiereDateDesc: Comparator<BaseItemDto> =
    compareByDescending(nullsFirst<LocalDateTime>()) { it.premiereDate }

/** Titles by sort name, without one by name, ignoring case. */
internal val bySortName: Comparator<BaseItemDto> =
    compareBy(String.CASE_INSENSITIVE_ORDER) { it.sortName ?: it.name.orEmpty() }
