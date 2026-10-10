package io.github.glacier_jellyfin.androidtv.library

import io.github.glacier_jellyfin.androidtv.core.log.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.glacier_jellyfin.androidtv.core.data.SessionManager
import io.github.glacier_jellyfin.androidtv.core.data.media.ItemKind
import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryKind
import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryQuery
import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryRepository
import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryScope
import io.github.glacier_jellyfin.androidtv.core.data.settings.LibrarySortChoice
import io.github.glacier_jellyfin.androidtv.core.data.settings.SettingsRepository
import io.github.glacier_jellyfin.androidtv.core.data.media.LibrarySort
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaItem
import io.github.glacier_jellyfin.androidtv.music.MusicController
import io.github.glacier_jellyfin.androidtv.navigation.FavoritesRoute
import io.github.glacier_jellyfin.androidtv.navigation.DetailRoute
import io.github.glacier_jellyfin.androidtv.navigation.HomeRoute
import io.github.glacier_jellyfin.androidtv.navigation.LibraryRoute
import io.github.glacier_jellyfin.androidtv.navigation.LiveTvRoute
import io.github.glacier_jellyfin.androidtv.navigation.MusicRoute
import io.github.glacier_jellyfin.androidtv.navigation.ProfilesRoute
import io.github.glacier_jellyfin.androidtv.navigation.SearchRoute
import io.github.glacier_jellyfin.androidtv.navigation.SettingsRoute
import io.github.glacier_jellyfin.androidtv.ui.NavTarget
import io.github.glacier_jellyfin.androidtv.ui.UiEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class LibraryState(
    val query: LibraryQuery,
    /** Library or genre name; null shows the kind name. */
    val title: String?,
    val items: List<MediaItem> = emptyList(),
    val total: Int? = null,
    val loading: Boolean = true,
    val failed: Boolean = false,
    val letters: Set<Char> = emptySet(),
    val sortMenuOpen: Boolean = false,
    /** Grid index to scroll to and focus after an A–Z jump; consumed by the screen. */
    val jumpTo: Int? = null,
    val userName: String = "",
) {
    val scopes: List<LibraryScope> get() = LibraryQuery.scopes(query.kind, inGenre = query.genreId != null)
    val complete: Boolean get() = total != null && items.size >= total
}

@HiltViewModel
class LibraryViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: LibraryRepository,
    private val sessions: SessionManager,
    private val settings: SettingsRepository,
    private val music: MusicController,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<LibraryRoute>()

    private val _state = MutableStateFlow(
        LibraryState(
            query = LibraryQuery(
                kind = LibraryKind.valueOf(route.kind),
                libraryId = route.libraryId?.let(UUID::fromString),
                genreId = route.genreId?.let(UUID::fromString),
                groupCollections = settings.settings.value.appearance.groupCollections,
            ),
            title = route.title,
            userName = sessions.session.value?.user?.name.orEmpty(),
        ),
    )
    val state: StateFlow<LibraryState> = _state.asStateFlow()

    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var loadJob: Job? = null
    private var lettersJob: Job? = null

    init {
        // The kept sort comes from the settings file, which may not be read yet right after start.
        viewModelScope.launch {
            val kept = runCatching { settings.current().librarySorts }
                .onFailure { Log.w(TAG, "Reading the library sort failed", it) }
                .getOrNull()
            if (kept != null) _state.update { it.copy(query = it.query.withKeptSort(kept)) }
            reload()
        }
    }

    /** Called by the grid when it gets near the end of what is loaded. */
    fun loadMore() {
        val current = _state.value
        if (current.loading || current.failed || current.complete) return
        loadUpTo(current.items.size + PAGE_SIZE - 1)
    }

    fun retry() = reload()

    /** Each music tab has its own sort, so a tab change also brings back the sort kept for it. */
    fun setScope(scope: LibraryScope) {
        if (scope == _state.value.query.scope) return
        val kept = settings.settings.value.librarySorts
        update { it.copy(scope = scope).withKeptSort(kept) }
    }

    /** The heart toggle of the music tabs; it stays on across the tabs. */
    fun toggleFavoritesOnly() = update { it.copy(favoritesOnly = !it.favoritesOnly) }

    fun toggleSortMenu(open: Boolean = !_state.value.sortMenuOpen) = _state.update { it.copy(sortMenuOpen = open) }

    /** The design's sort menu: picking the active sort again flips the direction. */
    fun setSort(sort: LibrarySort) {
        _state.update { it.copy(sortMenuOpen = false) }
        update { query ->
            if (query.sort == sort) query.copy(descending = !query.descending)
            else query.copy(sort = sort, descending = sort !in AscendingFirst)
        }
        val query = _state.value.query
        val choice = LibrarySortChoice(query.sort, query.descending)
        viewModelScope.launch {
            runCatching { settings.update { it.copy(librarySorts = it.librarySorts + (query.sortKey to choice)) } }
                .onFailure { Log.w(TAG, "Saving the library sort failed", it) }
        }
    }

    fun jumpToLetter(letter: Char) {
        val current = _state.value
        val total = current.total ?: return
        viewModelScope.launch {
            val index = runCatching { repository.indexOfLetter(current.query, letter, total) }
                .onFailure { Log.w(TAG, "A-Z jump failed", it) }
                .getOrNull() ?: return@launch
            loadUpTo(index + PAGE_SIZE / 2)?.join()
            _state.update { it.copy(jumpTo = index.coerceIn(0, (it.items.size - 1).coerceAtLeast(0))) }
        }
    }

    fun jumpHandled() = _state.update { it.copy(jumpTo = null) }

    fun open(item: MediaItem) {
        val query = _state.value.query
        val route = when (item.kind) {
            ItemKind.Genre -> LibraryRoute(query.kind.name, query.libraryId?.toString(), title = item.title, genreId = item.id.toString())
            else -> DetailRoute(item.id.toString())
        }
        viewModelScope.launch { _events.send(UiEvent.Navigate(route)) }
    }

    /**
     * A song of the songs tab plays with the loaded songs after it as the queue, and the
     * player opens; [title] names the queue.
     */
    fun play(item: MediaItem, title: String) {
        val items = _state.value.items
        val start = items.indexOf(item).takeIf { it >= 0 } ?: return
        val ids = items.drop(start).take(SONG_QUEUE).map { it.id }
        music.playSongs(title, ids, startTrackId = item.id.toString())
        viewModelScope.launch { _events.send(UiEvent.Navigate(MusicRoute())) }
    }

    fun onNav(target: NavTarget) {
        val route: Any = when (target) {
            NavTarget.Home -> {
                viewModelScope.launch { _events.send(UiEvent.Navigate(HomeRoute, clearBackStack = true)) }
                return
            }
            NavTarget.Search -> SearchRoute()
            NavTarget.Settings -> SettingsRoute
            NavTarget.Favorites -> FavoritesRoute
            NavTarget.NowPlaying -> MusicRoute()
            NavTarget.LiveTv -> LiveTvRoute
            is NavTarget.Library -> if (target.kind == _state.value.query.kind && route.libraryId == null && route.genreId == null) return else LibraryRoute(target.kind.name)
            NavTarget.Profile -> {
                val serverId = sessions.session.value?.server?.id ?: return
                sessions.leave()
                viewModelScope.launch { _events.send(UiEvent.Navigate(ProfilesRoute(serverId), clearBackStack = true)) }
                return
            }
        }
        viewModelScope.launch { _events.send(UiEvent.Navigate(route)) }
    }

    private fun update(change: (LibraryQuery) -> LibraryQuery) {
        _state.update { it.copy(query = change(it.query)) }
        reload()
    }

    private fun reload() {
        loadJob?.cancel()
        _state.update { it.copy(items = emptyList(), total = null, loading = false, failed = false, letters = emptySet(), jumpTo = null) }
        loadUpTo(PAGE_SIZE - 1)
        lettersJob?.cancel()
        val query = _state.value.query
        if (query.alphabetical) {
            lettersJob = viewModelScope.launch {
                runCatching { repository.availableLetters(query) }
                    .onSuccess { letters -> _state.update { if (it.query == query) it.copy(letters = letters) else it } }
                    .onFailure { Log.w(TAG, "Loading A-Z letters failed", it) }
            }
        }
    }

    /** Loads pages until [index] is covered (or everything is loaded). */
    private fun loadUpTo(index: Int): Job? {
        val current = _state.value
        if (current.total != null && current.items.size >= current.total) return null
        if (index < current.items.size) return null
        loadJob?.let { if (it.isActive) return it }
        val query = current.query
        return viewModelScope.launch {
            _state.update { it.copy(loading = true, failed = false) }
            try {
                while (true) {
                    val loaded = _state.value.items
                    val total = _state.value.total
                    if ((total != null && loaded.size >= total) || loaded.size > index) break
                    val page = repository.page(query, start = loaded.size, limit = PAGE_SIZE)
                    if (_state.value.query != query) return@launch
                    _state.update { it.copy(items = it.items + page.items, total = page.total) }
                    if (page.items.isEmpty()) break
                }
                _state.update { it.copy(loading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Loading library failed", e)
                _state.update { it.copy(loading = false, failed = true) }
            }
        }.also { loadJob = it }
    }

    /** The sort kept for this query's kind and tab, if the tab offers it; else by title, A to Z. */
    private fun LibraryQuery.withKeptSort(kept: Map<String, LibrarySortChoice>): LibraryQuery {
        val choice = kept[sortKey]?.takeIf { it.sort in sorts } ?: LibrarySortChoice()
        return copy(sort = choice.sort, descending = choice.descending)
    }

    companion object {
        /** Ten rows of seven posters. */
        const val PAGE_SIZE = 70
        /** Names sort A to Z first; dates, years, ratings and runtimes newest or largest first. */
        private val AscendingFirst = setOf(LibrarySort.Title, LibrarySort.Artist, LibrarySort.Album)
        /** Songs queued from the songs tab; their ids go into one request. */
        private const val SONG_QUEUE = 100
        private const val TAG = "Library"
    }
}
