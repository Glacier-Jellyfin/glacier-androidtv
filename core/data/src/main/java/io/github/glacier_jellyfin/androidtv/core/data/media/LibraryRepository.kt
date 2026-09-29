package io.github.glacier_jellyfin.androidtv.core.data.media

import io.github.glacier_jellyfin.androidtv.core.data.Session
import io.github.glacier_jellyfin.androidtv.core.data.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.extensions.artistApi
import org.jellyfin.sdk.api.client.extensions.genreApi
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.model.api.BaseItemDtoQueryResult
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemFilter
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.SortOrder
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** The filter chips of the library screen. */
enum class LibraryScope { All, Collections, Genres, Unwatched, Favorites, Albums, Artists, Playlists }

enum class LibrarySort { DateAdded, Title, Year, Rating, Runtime }

/**
 * What the library grid shows: every library of [kind], or one library when
 * [libraryId] is set, optionally narrowed to a genre.
 */
data class LibraryQuery(
    val kind: LibraryKind,
    val libraryId: UUID? = null,
    val genreId: UUID? = null,
    val scope: LibraryScope = defaultScope(kind),
    val sort: LibrarySort = LibrarySort.DateAdded,
    val descending: Boolean = true,
    /** Design setting "Group movies into collections"; on by default. */
    val groupCollections: Boolean = true,
) {
    /** Genres and artists are always listed by name, A to Z, whatever the other scopes sort by. */
    val listedByName: Boolean get() = scope == LibraryScope.Genres || scope == LibraryScope.Artists

    /** The order actually requested: [descending] only applies to the chosen sort. */
    val orderDescending: Boolean get() = descending && !listedByName

    /** Only title sorting has a meaningful A–Z order. */
    val alphabetical: Boolean get() = sort == LibrarySort.Title

    companion object {
        fun defaultScope(kind: LibraryKind) = if (kind == LibraryKind.Music) LibraryScope.Albums else LibraryScope.All

        fun scopes(kind: LibraryKind, inGenre: Boolean): List<LibraryScope> = when {
            kind == LibraryKind.Music -> listOf(LibraryScope.Albums, LibraryScope.Artists, LibraryScope.Playlists)
            inGenre -> listOf(LibraryScope.All, LibraryScope.Unwatched, LibraryScope.Favorites)
            kind == LibraryKind.Movies -> listOf(
                LibraryScope.All, LibraryScope.Collections, LibraryScope.Genres, LibraryScope.Unwatched, LibraryScope.Favorites,
            )
            else -> listOf(LibraryScope.All, LibraryScope.Genres, LibraryScope.Unwatched, LibraryScope.Favorites)
        }
    }
}

data class LibraryPage(val items: List<MediaItem>, val total: Int)

/** Letters of the A–Z rail; '#' stands for everything that does not start with A–Z. */
val AlphabetLetters: List<Char> = listOf('#') + ('A'..'Z')

@Singleton
class LibraryRepository @Inject constructor(
    private val sessions: SessionManager,
) {

    suspend fun page(query: LibraryQuery, start: Int, limit: Int): LibraryPage = withContext(Dispatchers.IO) {
        val session = requireSession()
        val mapper = MediaMapper(session.api)
        val result = fetch(session, query, start = start, limit = limit)
        LibraryPage(result.items.map(mapper::item), result.totalRecordCount)
    }

    /**
     * Letters that have at least one title, for greying out the rest of the
     * A–Z rail. One count query per letter, all at once.
     */
    suspend fun availableLetters(query: LibraryQuery): Set<Char> = withContext(Dispatchers.IO) {
        val session = requireSession()
        AlphabetLetters.map { letter ->
            async {
                val count = if (letter == '#') {
                    fetch(session, query, start = 0, limit = 0, nameLessThan = "A").totalRecordCount
                } else {
                    fetch(session, query, start = 0, limit = 0, nameStartsWith = letter.toString()).totalRecordCount
                }
                letter.takeIf { count > 0 }
            }
        }.awaitAll().filterNotNull().toSet()
    }

    /**
     * Position of the first title filed under [letter] in the current order.
     * Everything sorting before the letter comes first when ascending; when
     * descending, everything from the next letter on comes first.
     */
    suspend fun indexOfLetter(query: LibraryQuery, letter: Char, total: Int): Int = withContext(Dispatchers.IO) {
        val session = requireSession()
        suspend fun before(bound: String) = fetch(session, query, start = 0, limit = 0, nameLessThan = bound).totalRecordCount
        if (!query.orderDescending) {
            if (letter == '#') 0 else before(letter.toString())
        } else {
            val next = AlphabetLetters.getOrNull(AlphabetLetters.indexOf(letter) + 1)
            if (next == null) 0 else total - before(next.toString())
        }
    }

    private suspend fun fetch(
        session: Session,
        query: LibraryQuery,
        start: Int,
        limit: Int,
        nameStartsWith: String? = null,
        nameLessThan: String? = null,
    ): BaseItemDtoQueryResult {
        val userId = UUID.fromString(session.user.userId)
        val sortBy = query.sort.sortBy
        val order = listOf(if (query.orderDescending) SortOrder.DESCENDING else SortOrder.ASCENDING)
        return when (query.scope) {
            LibraryScope.Genres -> session.api.genreApi.getGenres(
                userId = userId,
                parentId = query.libraryId,
                includeItemTypes = listOf(query.kind.itemType),
                fields = listOf(ItemFields.ITEM_COUNTS, ItemFields.SORT_NAME),
                startIndex = start,
                limit = limit,
                nameStartsWith = nameStartsWith,
                nameLessThan = nameLessThan,
                sortBy = listOf(ItemSortBy.SORT_NAME),
                sortOrder = order,
            ).content
            LibraryScope.Artists -> session.api.artistApi.getAlbumArtists(
                userId = userId,
                parentId = query.libraryId,
                fields = listOf(ItemFields.CHILD_COUNT, ItemFields.SORT_NAME),
                startIndex = start,
                limit = limit,
                nameStartsWith = nameStartsWith,
                nameLessThan = nameLessThan,
                sortBy = listOf(ItemSortBy.SORT_NAME),
                sortOrder = order,
                enableUserData = true,
            ).content
            else -> session.api.libraryApi.getItems(
                userId = userId,
                parentId = if (query.scope == LibraryScope.Playlists) null else query.libraryId,
                recursive = true,
                includeItemTypes = listOf(itemTypeFor(query)),
                mediaTypes = if (query.scope == LibraryScope.Playlists) listOf(MediaType.AUDIO) else emptyList(),
                genreIds = listOfNotNull(query.genreId),
                filters = listOfNotNull(ItemFilter.IS_UNPLAYED.takeIf { query.scope == LibraryScope.Unwatched }),
                isFavorite = true.takeIf { query.scope == LibraryScope.Favorites },
                collapseBoxSetItems = (query.scope == LibraryScope.All && query.kind == LibraryKind.Movies &&
                    query.groupCollections && query.genreId == null).takeIf { it },
                fields = listOf(ItemFields.CHILD_COUNT, ItemFields.GENRES, ItemFields.SORT_NAME),
                enableUserData = true,
                startIndex = start,
                limit = limit,
                nameStartsWith = nameStartsWith,
                nameLessThan = nameLessThan,
                sortBy = sortBy,
                sortOrder = order,
                enableTotalRecordCount = true,
            ).content
        }
    }

    private fun itemTypeFor(query: LibraryQuery): BaseItemKind = when (query.scope) {
        LibraryScope.Collections -> BaseItemKind.BOX_SET
        LibraryScope.Playlists -> BaseItemKind.PLAYLIST
        else -> query.kind.itemType
    }

    private val LibraryKind.itemType: BaseItemKind
        get() = when (this) {
            LibraryKind.Movies -> BaseItemKind.MOVIE
            LibraryKind.Shows -> BaseItemKind.SERIES
            LibraryKind.Music -> BaseItemKind.MUSIC_ALBUM
        }

    /** A secondary sort by name keeps the order stable between pages. */
    private val LibrarySort.sortBy: List<ItemSortBy>
        get() = when (this) {
            LibrarySort.DateAdded -> listOf(ItemSortBy.DATE_CREATED, ItemSortBy.SORT_NAME)
            LibrarySort.Title -> listOf(ItemSortBy.SORT_NAME)
            LibrarySort.Year -> listOf(ItemSortBy.PRODUCTION_YEAR, ItemSortBy.PREMIERE_DATE, ItemSortBy.SORT_NAME)
            LibrarySort.Rating -> listOf(ItemSortBy.COMMUNITY_RATING, ItemSortBy.SORT_NAME)
            LibrarySort.Runtime -> listOf(ItemSortBy.RUNTIME, ItemSortBy.SORT_NAME)
        }

    private fun requireSession(): Session = checkNotNull(sessions.session.value) { "No profile is signed in" }
}
