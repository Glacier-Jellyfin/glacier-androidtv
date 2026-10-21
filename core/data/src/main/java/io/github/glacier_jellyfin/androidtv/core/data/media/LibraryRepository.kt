package io.github.glacier_jellyfin.androidtv.core.data.media

import io.github.glacier_jellyfin.androidtv.core.data.Session
import io.github.glacier_jellyfin.androidtv.core.data.SessionManager
import io.github.glacier_jellyfin.androidtv.core.log.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.extensions.artistApi
import org.jellyfin.sdk.api.client.extensions.genreApi
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.model.api.BaseItemDto
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
enum class LibraryScope { All, Collections, Genres, Albums, Artists, Songs, Playlists }

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
    /** The heart toggle: only what the profile marked as favorite, in every tab. */
    val favoritesOnly: Boolean = false,
    /** The "Unwatched" toggle of movies and shows. */
    val unwatchedOnly: Boolean = false,
) {
    /**
     * Movies grouped into collections, and only the unwatched ones. Jellyfin does
     * not group once a played filter is in the query, so the app does it.
     */
    val unwatchedGrouped: Boolean
        get() = unwatchedOnly && groupsCollections

    /** The collections tab of shows lists only the collections that hold a show. */
    val showCollections: Boolean
        get() = scope == LibraryScope.Collections && kind == LibraryKind.Shows

    /** The movies tab shows collections in place of their movies. Filtered, the titles themselves count, except for unwatched. */
    val groupsCollections: Boolean
        get() = scope == LibraryScope.All && kind == LibraryKind.Movies && groupCollections && genreId == null && !favoritesOnly

    /** Genres are listed as they are: the toggles narrow titles, not genres. */
    val filterable: Boolean get() = scope != LibraryScope.Genres

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

    /** Sorted by something else than the name, the list goes in groups with a heading each (see [ListGroup]). */
    val grouped: Boolean get() = !listedByName && sort != LibrarySort.Title

    /** An A–Z rail where the list goes by names: titles, or groups of artists or albums. */
    val railed: Boolean get() = listedByName || sort == LibrarySort.Title || sort == LibrarySort.Artist || sort == LibrarySort.Album

    companion object {
        fun defaultScope(kind: LibraryKind) = if (kind == LibraryKind.Music) LibraryScope.Albums else LibraryScope.All

        fun scopes(kind: LibraryKind, inGenre: Boolean): List<LibraryScope> = when {
            kind == LibraryKind.Music -> listOf(LibraryScope.Albums, LibraryScope.Artists, LibraryScope.Songs, LibraryScope.Playlists)
            inGenre -> listOf(LibraryScope.All)
            kind == LibraryKind.Movies || kind == LibraryKind.Shows -> listOf(LibraryScope.All, LibraryScope.Collections, LibraryScope.Genres)
            else -> listOf(LibraryScope.All, LibraryScope.Genres)
        }
    }
}

data class LibraryPage(val items: List<MediaItem>, val total: Int)

/** Letters of the A–Z rail; '#' stands for everything that does not start with A–Z. */
val AlphabetLetters: List<Char> = listOf('#') + ('A'..'Z')

/** The rail letter [name] is filed under: its first letter without accents, else '#'. Matches [NameOrder]. */
fun railLetter(name: String): Char {
    val first = NameOrder.key(name).firstOrNull() ?: return '#'
    return if (first in 'a'..'z') first.uppercaseChar() else '#'
}

@Singleton
class LibraryRepository @Inject constructor(
    private val sessions: SessionManager,
    private val ageFilter: AgeFilter,
    private val store: NameIndexStore,
) {

    /** Titles [start] to [start] + [limit] of [query], each with its group when the list is grouped. */
    suspend fun page(query: LibraryQuery, start: Int, limit: Int): LibraryPage = withContext(Dispatchers.IO) {
        val session = requireSession()
        val mapper = MediaMapper(session.api)
        // The first page is a fresh look: something may have changed meanwhile.
        val listing = listing(session, query, fresh = start == 0)
        val list = listing.list
        val end = minOf(start + limit, list.size)
        if (start >= end) return@withContext LibraryPage(emptyList(), list.size)
        val slice = list.entries.subList(start, end)
        val groups = list.groups?.let { all -> slice.indices.associate { slice[it].id to all[start + it] } }
        val dtos = listing.whole?.let { whole -> slice.mapNotNull { whole[it.id] } } ?: itemsByIds(session, slice.map { it.id })
        val items = dtos.map(mapper::item).map { item -> groups?.get(item.id)?.let { item.copy(group = it) } ?: item }
        LibraryPage(ageFilter.mark(items), list.size)
    }

    /** Letters that have at least one title, for greying out the rest of the A–Z rail. */
    suspend fun availableLetters(query: LibraryQuery): Set<Char> = withContext(Dispatchers.IO) {
        val list = listing(requireSession(), query).list
        (0 until list.size).mapNotNullTo(mutableSetOf()) { list.letterAt(it) }
    }

    /** Position of the first title filed under [letter] in the current order. */
    suspend fun indexOfLetter(query: LibraryQuery, letter: Char): Int = withContext(Dispatchers.IO) {
        val list = listing(requireSession(), query).list
        (0 until list.size).firstOrNull { list.letterAt(it) == letter } ?: 0
    }

    /** Where the row that holds [index] starts in a grid of [columns] (see [ArrangedList.rowStart]). */
    suspend fun rowStart(query: LibraryQuery, index: Int, columns: Int): Int = withContext(Dispatchers.IO) {
        listing(requireSession(), query).list.rowStart(index, columns)
    }

    /**
     * Fetched whole, with everything a card needs ([wholeList]). Artists and genres come
     * from their own endpoints, which count albums and titles. Fetched by id they would not.
     */
    private val LibraryQuery.listedWhole: Boolean
        get() = unwatchedGrouped || scope == LibraryScope.Artists || scope == LibraryScope.Genres

    /** What a title is sorted and filed under (see [NameOrder.title]). A song's sort name starts with its disc and track ("0001 - 0003 - Title"). */
    private fun nameOf(dto: BaseItemDto, query: LibraryQuery): String =
        if (query.scope == LibraryScope.Songs) dto.name.orEmpty() else NameOrder.title(dto.name.orEmpty(), dto.sortName)

    private fun entryOf(dto: BaseItemDto, query: LibraryQuery) = IndexEntry(
        id = dto.id,
        name = nameOf(dto, query),
        addedMonth = dto.dateCreated?.let { it.year * 12 + it.monthValue - 1 },
        year = dto.productionYear,
        rating = dto.communityRating,
        runtimeMs = dto.runTimeTicks?.div(10_000),
        artist = if (query.scope == LibraryScope.Songs) dto.artists?.firstOrNull() ?: dto.albumArtist else dto.albumArtist,
        albumId = dto.albumId?.toString(),
        album = dto.album,
        disc = dto.parentIndexNumber,
        track = dto.indexNumber,
    )

    /** A list in the grid's order and, when fetched whole, its titles. */
    private class Listing(val query: LibraryQuery, val source: List<IndexEntry>, val list: ArrangedList, val whole: Map<UUID, BaseItemDto>?)

    private var listingCache: Listing? = null
    private val listingLock = Mutex()

    /**
     * [query]'s titles in the grid's order. The app puts them in order itself (see
     * [ListArrangement]), so it needs every title first: most come as a light index ([index]),
     * and each page is then fetched by its ids. One index serves every sort, so a new sort
     * only puts the titles in a new order.
     */
    private suspend fun listing(session: Session, query: LibraryQuery, fresh: Boolean = false): Listing {
        val whole = if (query.listedWhole) wholeList(session, query, fresh) else null
        val entries = whole?.entries ?: index(session, query, fresh)
        return listingLock.withLock {
            listingCache?.takeIf { it.query == query && it.source === entries }
                ?: Listing(query, entries, ListArrangement.arrange(entries, query), whole?.dtos).also { listingCache = it }
        }
    }

    private class Cached<T>(val key: Any, val at: Long, val value: T)

    /** The index the grid pages through. */
    private var indexCache: Cached<List<IndexEntry>>? = null
    private val indexLock = Mutex()
    private val refreshing = mutableSetOf<String>()
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Every title of [query] with what the sorts need. Kept for the next pages and the A–Z
     * rail, and on the device ([NameIndexStore]): a big library takes the server half a
     * minute to list. A fresh look (the first page) takes the newest copy and, when that is
     * old, has a new one fetched in the background for next time. Narrowed to favorites or
     * unwatched it is not saved: a change elsewhere can take a title out, so each fresh look
     * fetches it again unless that was a moment ago.
     */
    private suspend fun index(session: Session, query: LibraryQuery, fresh: Boolean = false): List<IndexEntry> = indexLock.withLock {
        val key = indexKey(session, query)
        val now = System.currentTimeMillis()
        val cached = indexCache?.takeIf { it.key == key }
        when {
            cached != null && !fresh -> cached.value
            query.favoritesOnly || query.unwatchedOnly ->
                if (cached != null && now - cached.at < WHOLE_FRESH_MS) cached.value else fetchIndex(session, query).also { indexCache = Cached(key, now, it) }
            else -> {
                val savedAt = store.savedAt(key)
                val newest = if (savedAt != null && (cached == null || savedAt > cached.at)) store.read(key) else null
                when {
                    newest != null -> {
                        indexCache = Cached(key, newest.at, newest.entries)
                        if (now - newest.at > INDEX_REFRESH_MS) refreshInBackground(session, query, key)
                        newest.entries
                    }
                    cached != null -> {
                        if (now - cached.at > INDEX_REFRESH_MS) refreshInBackground(session, query, key)
                        cached.value
                    }
                    else -> fetchIndex(session, query).also {
                        indexCache = Cached(key, now, it)
                        store.write(key, SavedIndex(now, it))
                    }
                }
            }
        }
    }

    /** One index serves every sort and both directions. The version changes with what an entry holds. */
    private fun indexKey(session: Session, query: LibraryQuery): String =
        "$INDEX_VERSION|${session.server.id}|${session.user.userId}|${query.copy(sort = LibrarySort.Title, descending = false)}"

    /** [query]'s titles from the server. */
    private suspend fun fetchIndex(session: Session, query: LibraryQuery): List<IndexEntry> =
        fetchAll(session, query, light = true)
            .distinctBy { it.id }
            .map { entryOf(it, query) }

    /** Saves a new copy of an index for the next fresh look; the one in use stays as it is. */
    private fun refreshInBackground(session: Session, query: LibraryQuery, key: String) {
        synchronized(refreshing) { if (!refreshing.add(key)) return }
        background.launch {
            try {
                val entries = fetchIndex(session, query)
                store.write(key, SavedIndex(System.currentTimeMillis(), entries))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Refreshing a name index failed", e)
            } finally {
                synchronized(refreshing) { refreshing.remove(key) }
            }
        }
    }

    /** A list fetched whole: its titles as index entries and as they came. */
    private class Whole(val entries: List<IndexEntry>, val dtos: Map<UUID, BaseItemDto>)

    private var wholeCache: Cached<Whole>? = null
    private val wholeLock = Mutex()

    /**
     * The whole list of [query], kept for the next pages and the A–Z rail. [fresh] fetches
     * it again unless it was fetched a moment ago (the first page and the rail load together).
     * The movies tab grouped into collections, without what was watched, is one: the server
     * does not group once a played filter is in the query, so the app leaves out the movies
     * watched and the collections whose movies were all watched (the server marks those played).
     */
    private suspend fun wholeList(session: Session, query: LibraryQuery, fresh: Boolean = false): Whole = wholeLock.withLock {
        val key = indexKey(session, query)
        wholeCache?.let { cached ->
            val recent = System.currentTimeMillis() - cached.at < WHOLE_FRESH_MS
            if (cached.key == key && (!fresh || recent)) return@withLock cached.value
        }
        var items = fetchAll(session, query, grouped = query.unwatchedGrouped).distinctBy { it.id }
        if (query.unwatchedGrouped) items = items.filter { it.userData?.played != true }
        val whole = Whole(items.map { entryOf(it, query) }, items.associateBy { it.id })
        wholeCache = Cached(key, System.currentTimeMillis(), whole)
        whole
    }

    /** Every title of [query], page by page, a few pages at once. */
    private suspend fun fetchAll(session: Session, query: LibraryQuery, light: Boolean = false, grouped: Boolean = false): List<BaseItemDto> {
        val first = fetch(session, query, start = 0, limit = WHOLE_PAGE, light = light, grouped = grouped)
        val total = first.totalRecordCount.coerceAtMost(MAX_WHOLE)
        val permits = Semaphore(WHOLE_PARALLEL)
        val rest = coroutineScope {
            (WHOLE_PAGE until total step WHOLE_PAGE).map { start ->
                async { permits.withPermit { fetch(session, query, start = start, limit = WHOLE_PAGE, light = light, grouped = grouped).items } }
            }.awaitAll()
        }
        return first.items + rest.flatten()
    }

    /** The titles with [ids], in that order. A few at once, so the address stays short. */
    private suspend fun itemsByIds(session: Session, ids: List<UUID>): List<BaseItemDto> {
        if (ids.isEmpty()) return emptyList()
        val userId = UUID.fromString(session.user.userId)
        val found = coroutineScope {
            ids.chunked(IDS_PER_REQUEST).map { chunk ->
                async {
                    session.api.libraryApi.getItems(
                        userId = userId,
                        ids = chunk,
                        fields = listOf(ItemFields.CHILD_COUNT, ItemFields.GENRES, ItemFields.SORT_NAME),
                        enableUserData = true,
                    ).content.items
                }
            }.awaitAll().flatten()
        }.associateBy { it.id }
        return ids.mapNotNull { found[it] }
    }

    /** The last [showCollectionIds], the user they belong to, and when they were found. */
    private var showCollectionsCache: Triple<String, Long, List<UUID>>? = null
    private val showCollectionsLock = Mutex()

    /**
     * Collections that hold at least one show. The server groups only movies into
     * collections and cannot filter collections by what they hold, so each collection
     * is asked for its shows, a few at a time. Collections change rarely: the answer is
     * kept for a while.
     */
    private suspend fun showCollectionIds(session: Session): List<UUID> = showCollectionsLock.withLock {
        val userId = UUID.fromString(session.user.userId)
        showCollectionsCache?.let { (user, at, ids) ->
            if (user == session.user.userId && System.currentTimeMillis() - at < SHOW_COLLECTIONS_FRESH_MS) return@withLock ids
        }
        val collections = session.api.libraryApi.getItems(
            userId = userId,
            recursive = true,
            includeItemTypes = listOf(BaseItemKind.BOX_SET),
            enableImages = false,
            enableUserData = false,
        ).content.items.map { it.id }
        val limit = Semaphore(SHOW_COLLECTIONS_PARALLEL)
        val ids = coroutineScope {
            collections.map { id ->
                async {
                    limit.withPermit {
                        val shows = session.api.libraryApi.getItems(
                            userId = userId,
                            parentId = id,
                            includeItemTypes = listOf(BaseItemKind.SERIES),
                            limit = 0,
                            enableTotalRecordCount = true,
                        ).content.totalRecordCount
                        id.takeIf { shows > 0 }
                    }
                }
            }.awaitAll().filterNotNull()
        }
        showCollectionsCache = Triple(session.user.userId, System.currentTimeMillis(), ids)
        ids
    }

    private suspend fun fetch(
        session: Session,
        query: LibraryQuery,
        start: Int,
        limit: Int,
        /** Only what [index] needs: no images, user data or extra fields. */
        light: Boolean = false,
        /** For [wholeList]: grouped into collections, without the played filter the server would not group with. */
        grouped: Boolean = false,
    ): BaseItemDtoQueryResult {
        val userId = UUID.fromString(session.user.userId)
        // The app puts the titles in order itself (ListArrangement). This only keeps the pages apart.
        val sortBy = listOf(ItemSortBy.SORT_NAME, ItemSortBy.DATE_CREATED)
        val favorite = true.takeIf { query.favoritesOnly }
        // Music and playlists have no age ratings worth filtering on.
        val ages = if (query.kind == LibraryKind.Music || query.scope == LibraryScope.Playlists) null else ageFilter.limits()
        val order = listOf(SortOrder.ASCENDING)
        val only = if (query.showCollections) showCollectionIds(session) else emptyList()
        // Asked without ids, the server would list every collection.
        if (query.showCollections && only.isEmpty()) return BaseItemDtoQueryResult(items = emptyList(), totalRecordCount = 0, startIndex = 0)
        return when (query.scope) {
            LibraryScope.Genres -> session.api.genreApi.getGenres(
                userId = userId,
                parentId = query.libraryId,
                includeItemTypes = listOf(query.kind.itemType),
                fields = listOf(ItemFields.ITEM_COUNTS, ItemFields.SORT_NAME),
                startIndex = start,
                limit = limit,
                sortBy = listOf(ItemSortBy.SORT_NAME),
                sortOrder = order,
            ).content
            LibraryScope.Artists -> session.api.artistApi.getAlbumArtists(
                userId = userId,
                parentId = query.libraryId,
                fields = listOf(ItemFields.CHILD_COUNT, ItemFields.SORT_NAME, ItemFields.DATE_CREATED),
                startIndex = start,
                limit = limit,
                isFavorite = favorite,
                sortBy = sortBy,
                sortOrder = order,
                enableUserData = true,
            ).content
            else -> session.api.libraryApi.getItems(
                userId = userId,
                parentId = if (query.scope == LibraryScope.Playlists) null else query.libraryId,
                ids = only,
                recursive = true,
                includeItemTypes = listOf(itemTypeFor(query)),
                genreIds = listOfNotNull(query.genreId),
                maxOfficialRating = ages?.maxOfficialRating,
                hasOfficialRating = ages?.hasOfficialRating,
                filters = listOfNotNull(ItemFilter.IS_UNPLAYED.takeIf { query.unwatchedOnly && !grouped }),
                isFavorite = favorite,
                // Filtered, the titles themselves count: a collection would hide which of its movies match.
                collapseBoxSetItems = (grouped || (query.groupsCollections && !query.unwatchedOnly)).takeIf { it },
                fields = if (light) listOf(ItemFields.SORT_NAME, ItemFields.DATE_CREATED) else listOf(ItemFields.CHILD_COUNT, ItemFields.GENRES, ItemFields.SORT_NAME, ItemFields.DATE_CREATED),
                enableImages = !light,
                enableUserData = !light,
                startIndex = start,
                limit = limit,
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

    private companion object {
        /** Titles per request when a whole list is fetched. */
        const val WHOLE_PAGE = 2000
        /** Requests at once when a whole list is fetched. */
        const val WHOLE_PARALLEL = 8
        /** The grid's first page and the A–Z rail ask for the whole list together: one fetch serves both. */
        const val WHOLE_FRESH_MS = 3_000L
        /** An [index] older than this is fetched again in the background. The pages fetched with it are always fresh. */
        const val INDEX_REFRESH_MS = 30 * 60_000L
        const val TAG = "Library"
        /** Ids per request when a page is fetched by its ids. */
        const val IDS_PER_REQUEST = 40
        /** How long the collections holding shows are trusted before they are looked up again. */
        const val SHOW_COLLECTIONS_FRESH_MS = 5 * 60_000L
        /** Collections asked for their shows at once. */
        const val SHOW_COLLECTIONS_PARALLEL = 8
        /** Beyond this many titles only the first ones are listed. */
        const val MAX_WHOLE = 200_000
        /** Part of the index key: saved indexes of an older form are not read. */
        const val INDEX_VERSION = 2
    }

    private fun requireSession(): Session = checkNotNull(sessions.session.value) { "No profile is signed in" }
}
