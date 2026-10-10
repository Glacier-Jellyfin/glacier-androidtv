package io.github.glacier_jellyfin.androidtv.core.data.media

import io.github.glacier_jellyfin.androidtv.core.data.Session
import io.github.glacier_jellyfin.androidtv.core.data.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.extensions.artistApi
import org.jellyfin.sdk.api.client.extensions.genreApi
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.model.api.BaseItemDtoQueryResult
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemFilter
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** The filter chips of the library screen. */
enum class LibraryScope { All, Collections, Genres, Unwatched, Favorites, Albums, Artists, Songs, Playlists }

enum class LibrarySort { DateAdded, Title, Artist, Album, Year, Rating, Runtime }

/**
 * What the library grid shows: every library of [kind], or one library when
 * [libraryId] is set, optionally narrowed to a genre.
 */
data class LibraryQuery(
    val kind: LibraryKind,
    val libraryId: UUID? = null,
    val genreId: UUID? = null,
    val scope: LibraryScope = defaultScope(kind),
    val sort: LibrarySort = LibrarySort.Title,
    val descending: Boolean = false,
    /** Design setting "Group movies into collections"; on by default. */
    val groupCollections: Boolean = true,
    /** The heart toggle of the music tabs: only what the profile marked as favorite. */
    val favoritesOnly: Boolean = false,
) {
    /** Genres are always listed by name, A to Z, whatever the other scopes sort by. */
    val listedByName: Boolean get() = scope == LibraryScope.Genres

    /** The sorts the sort menu offers for this scope, in menu order. */
    val sorts: List<LibrarySort> get() = when (scope) {
        LibraryScope.Artists, LibraryScope.Playlists -> listOf(LibrarySort.DateAdded, LibrarySort.Title)
        LibraryScope.Albums -> listOf(
            LibrarySort.DateAdded, LibrarySort.Title, LibrarySort.Artist, LibrarySort.Year, LibrarySort.Rating, LibrarySort.Runtime,
        )
        LibraryScope.Songs -> listOf(
            LibrarySort.DateAdded, LibrarySort.Title, LibrarySort.Artist, LibrarySort.Album, LibrarySort.Year, LibrarySort.Runtime,
        )
        else -> listOf(LibrarySort.DateAdded, LibrarySort.Title, LibrarySort.Year, LibrarySort.Rating, LibrarySort.Runtime)
    }

    /**
     * Where the chosen sort is kept: one per kind, and one per tab for music, whose tabs
     * sort by different things. Albums keep the plain kind key they had before tabs had their own.
     */
    val sortKey: String get() = if (kind == LibraryKind.Music && scope != LibraryScope.Albums) "${kind.name}.${scope.name}" else kind.name

    /** The order actually requested: [descending] only applies to the chosen sort. */
    val orderDescending: Boolean get() = descending && !listedByName

    /** Only title sorting has a meaningful A–Z order. */
    val alphabetical: Boolean get() = sort == LibrarySort.Title

    companion object {
        fun defaultScope(kind: LibraryKind) = if (kind == LibraryKind.Music) LibraryScope.Albums else LibraryScope.All

        fun scopes(kind: LibraryKind, inGenre: Boolean): List<LibraryScope> = when {
            kind == LibraryKind.Music -> listOf(LibraryScope.Albums, LibraryScope.Artists, LibraryScope.Songs, LibraryScope.Playlists)
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

/** The rail letter [name] is filed under: its first letter without accents, else '#'. */
fun railLetter(name: String): Char {
    val first = name.trim().firstOrNull()?.uppercaseChar() ?: return '#'
    val plain = java.text.Normalizer.normalize(first.toString(), java.text.Normalizer.Form.NFD).first()
    return if (plain in 'A'..'Z') plain else '#'
}

@Singleton
class LibraryRepository @Inject constructor(
    private val sessions: SessionManager,
    private val ageFilter: AgeFilter,
) {

    suspend fun page(query: LibraryQuery, start: Int, limit: Int): LibraryPage = withContext(Dispatchers.IO) {
        val session = requireSession()
        val mapper = MediaMapper(session.api)
        val result = fetch(session, query, start = start, limit = limit)
        LibraryPage(ageFilter.mark(result.items.map(mapper::item)), result.totalRecordCount)
    }

    /**
     * Letters that have at least one title, for greying out the rest of the
     * A–Z rail. One count query per letter, all at once.
     */
    suspend fun availableLetters(query: LibraryQuery): Set<Char> = withContext(Dispatchers.IO) {
        val session = requireSession()
        if (query.scope == LibraryScope.Songs) return@withContext songNames(session, query).mapTo(mutableSetOf(), ::railLetter)
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
        if (query.scope == LibraryScope.Songs) {
            return@withContext songNames(session, query).indexOfFirst { railLetter(it) == letter }.coerceAtLeast(0)
        }
        suspend fun before(bound: String) = fetch(session, query, start = 0, limit = 0, nameLessThan = bound).totalRecordCount
        if (!query.orderDescending) {
            if (letter == '#') 0 else before(letter.toString())
        } else {
            val next = AlphabetLetters.getOrNull(AlphabetLetters.indexOf(letter) + 1)
            if (next == null) 0 else total - before(next.toString())
        }
    }

    /** The last [songNames] list and the query it belongs to. */
    private var songNamesCache: Pair<LibraryQuery, List<String>>? = null

    /**
     * Every song name of [query], in its order. The server's letter filters work on the sort
     * name, which for songs starts with disc and track ("0001 - 0003 - Title"), so the
     * songs' A–Z rail goes by the plain names instead. Fetched without images or user data,
     * page by page at once, and kept for the next jump.
     */
    private suspend fun songNames(session: Session, query: LibraryQuery): List<String> {
        songNamesCache?.let { (cached, names) -> if (cached == query) return names }
        val userId = UUID.fromString(session.user.userId)
        suspend fun names(start: Int) = session.api.libraryApi.getItems(
            userId = userId,
            parentId = query.libraryId,
            recursive = true,
            includeItemTypes = listOf(BaseItemKind.AUDIO),
            isFavorite = true.takeIf { query.favoritesOnly },
            sortBy = sortBy(query),
            sortOrder = listOf(if (query.orderDescending) SortOrder.DESCENDING else SortOrder.ASCENDING),
            enableImages = false,
            enableUserData = false,
            startIndex = start,
            limit = NAMES_PAGE,
            enableTotalRecordCount = true,
        ).content
        val first = names(0)
        val total = first.totalRecordCount.coerceAtMost(MAX_SONG_NAMES)
        val rest = coroutineScope {
            (NAMES_PAGE until total step NAMES_PAGE).map { start -> async { names(start).items } }.awaitAll()
        }
        val all = (first.items + rest.flatten()).map { it.name.orEmpty() }
        songNamesCache = query to all
        return all
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
        val sortBy = sortBy(query)
        val favorite = true.takeIf { query.scope == LibraryScope.Favorites || query.favoritesOnly }
        // Music and playlists have no age ratings worth filtering on.
        val ages = if (query.kind == LibraryKind.Music || query.scope == LibraryScope.Playlists) null else ageFilter.limits()
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
                isFavorite = favorite,
                sortBy = sortBy,
                sortOrder = order,
                enableUserData = true,
            ).content
            else -> session.api.libraryApi.getItems(
                userId = userId,
                parentId = if (query.scope == LibraryScope.Playlists) null else query.libraryId,
                recursive = true,
                includeItemTypes = listOf(itemTypeFor(query)),
                genreIds = listOfNotNull(query.genreId),
                maxOfficialRating = ages?.maxOfficialRating,
                hasOfficialRating = ages?.hasOfficialRating,
                filters = listOfNotNull(ItemFilter.IS_UNPLAYED.takeIf { query.scope == LibraryScope.Unwatched }),
                isFavorite = favorite,
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
        LibraryScope.Songs -> BaseItemKind.AUDIO
        else -> query.kind.itemType
    }

    private val LibraryKind.itemType: BaseItemKind
        get() = when (this) {
            LibraryKind.Movies -> BaseItemKind.MOVIE
            LibraryKind.Shows -> BaseItemKind.SERIES
            LibraryKind.Music -> BaseItemKind.MUSIC_ALBUM
            LibraryKind.MusicVideos -> BaseItemKind.MUSIC_VIDEO
        }

    /** A secondary sort by name keeps the order stable between pages. Songs of one album stay in track order. */
    private fun sortBy(query: LibraryQuery): List<ItemSortBy> {
        val inAlbum = listOf(ItemSortBy.ALBUM, ItemSortBy.PARENT_INDEX_NUMBER, ItemSortBy.INDEX_NUMBER, ItemSortBy.SORT_NAME)
        return when (if (query.listedByName) LibrarySort.Title else query.sort) {
            LibrarySort.DateAdded -> listOf(ItemSortBy.DATE_CREATED, ItemSortBy.SORT_NAME)
            LibrarySort.Title -> if (query.scope == LibraryScope.Songs) listOf(ItemSortBy.NAME) else listOf(ItemSortBy.SORT_NAME)
            LibrarySort.Artist ->
                if (query.scope == LibraryScope.Songs) listOf(ItemSortBy.ARTIST) + inAlbum
                else listOf(ItemSortBy.ALBUM_ARTIST, ItemSortBy.SORT_NAME)
            LibrarySort.Album -> inAlbum
            LibrarySort.Year -> listOf(ItemSortBy.PRODUCTION_YEAR, ItemSortBy.PREMIERE_DATE, ItemSortBy.SORT_NAME)
            LibrarySort.Rating -> listOf(ItemSortBy.COMMUNITY_RATING, ItemSortBy.SORT_NAME)
            LibrarySort.Runtime -> listOf(ItemSortBy.RUNTIME, ItemSortBy.SORT_NAME)
        }
    }

    private companion object {
        const val NAMES_PAGE = 2000
        /** Beyond this many songs the rail covers only the first ones. */
        const val MAX_SONG_NAMES = 40_000
    }

    private fun requireSession(): Session = checkNotNull(sessions.session.value) { "No profile is signed in" }
}
