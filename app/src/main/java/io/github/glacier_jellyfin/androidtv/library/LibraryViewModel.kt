package io.github.glacier_jellyfin.androidtv.library

import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.data.media.DetailRepository
import io.github.glacier_jellyfin.androidtv.core.data.playback.PlaybackRepository
import io.github.glacier_jellyfin.androidtv.core.log.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.glacier_jellyfin.androidtv.core.data.SessionManager
import io.github.glacier_jellyfin.androidtv.core.data.media.FolderContent
import io.github.glacier_jellyfin.androidtv.core.data.media.FolderMedia
import io.github.glacier_jellyfin.androidtv.core.data.media.FolderRepository
import io.github.glacier_jellyfin.androidtv.core.data.media.HomeRepository
import io.github.glacier_jellyfin.androidtv.core.data.media.ItemKind
import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryKind
import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryQuery
import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryRepository
import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryScope
import io.github.glacier_jellyfin.androidtv.core.data.settings.LibraryFilterChoice
import io.github.glacier_jellyfin.androidtv.core.data.settings.LibrarySortChoice
import io.github.glacier_jellyfin.androidtv.core.data.settings.SettingsRepository
import io.github.glacier_jellyfin.androidtv.core.data.media.LibrarySort
import io.github.glacier_jellyfin.androidtv.core.data.media.LoadProgress
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaFolder
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaItem
import io.github.glacier_jellyfin.androidtv.core.data.media.NameOrder
import io.github.glacier_jellyfin.androidtv.core.data.settings.MusicVideoView
import org.jellyfin.sdk.model.api.CollectionType
import io.github.glacier_jellyfin.androidtv.core.data.media.MusicRepository
import io.github.glacier_jellyfin.androidtv.core.jellyfin.runSuspendCatching
import io.github.glacier_jellyfin.androidtv.music.MusicActions
import io.github.glacier_jellyfin.androidtv.music.MusicController
import io.github.glacier_jellyfin.androidtv.music.MusicTarget
import io.github.glacier_jellyfin.androidtv.navigation.LibraryRoute
import io.github.glacier_jellyfin.androidtv.navigation.MusicRoute
import io.github.glacier_jellyfin.androidtv.navigation.navEvent
import io.github.glacier_jellyfin.androidtv.ui.BrowseMemory
import io.github.glacier_jellyfin.androidtv.ui.LibraryMemory
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
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import io.github.glacier_jellyfin.androidtv.navigation.routeFor

data class LibraryState(
    val query: LibraryQuery,
    /** Library or genre name; null shows the kind name. */
    val title: String?,
    /** The loaded part of the list: titles [offset] to [offset] + size. */
    val items: List<MediaItem> = emptyList(),
    /**
     * Where [items] start in the whole list. An A–Z jump far down loads the titles
     * there and leaves those before it for when the user scrolls back up.
     */
    val offset: Int = 0,
    val total: Int? = null,
    val loading: Boolean = true,
    val failed: Boolean = false,
    val letters: Set<Char> = emptySet(),
    val sortMenuOpen: Boolean = false,
    /** Grid index to scroll to and focus after an A–Z jump; consumed by the screen. */
    val jumpTo: Int? = null,
    val userName: String = "",
    /** The server has a music video library: music gets their tab. */
    val musicVideos: Boolean = false,
    /** How the music videos tab lists them; null until known (picked, or decided by their metadata). */
    val musicVideoView: MusicVideoView? = null,
    /** The folders opened in the music videos' folder view, from the top; empty at the top. */
    val folderPath: List<MediaFolder> = emptyList(),
    /** The folder shown in the folder view, its subfolders and videos in the tab's order. */
    val folder: FolderContent? = null,
    val viewMenuOpen: Boolean = false,
) {
    /** The music videos tab shows folders instead of the paged grid. */
    val inFolders: Boolean get() = query.scope == LibraryScope.MusicVideos && musicVideoView == MusicVideoView.Folders

    val scopes: List<LibraryScope> get() = LibraryQuery.scopes(query.kind, inGenre = query.genreId != null, musicVideos = musicVideos)
    val complete: Boolean get() = total != null && offset + items.size >= total
}

@HiltViewModel
class LibraryViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: LibraryRepository,
    private val sessions: SessionManager,
    private val settings: SettingsRepository,
    private val music: MusicController,
    private val songs: MusicRepository,
    details: DetailRepository,
    playback: PlaybackRepository,
    home: HomeRepository,
    private val folders: FolderRepository,
    private val memory: BrowseMemory,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<LibraryRoute>()

    /** Opened from the navigation bar, not for one library or genre from another page. */
    val inNavBar: Boolean get() = route.libraryId == null && route.genreId == null

    private val kind = LibraryKind.valueOf(route.kind)

    /** Where the user left this library last time (the bar starts it anew); only for the bar's own libraries. */
    private val remembered: LibraryMemory? = memory.library(kind)?.takeIf {
        inNavBar && it.scope in LibraryQuery.scopes(kind, inGenre = false, musicVideos = true)
    }

    /** The title that had the focus then, for the grid to go back to. */
    val restoredFocus: String? = remembered?.focusId

    private val _state = MutableStateFlow(
        LibraryState(
            query = LibraryQuery(
                kind = kind,
                libraryId = route.libraryId?.let(UUID::fromString),
                genreId = route.genreId?.let(UUID::fromString),
                groupCollections = settings.settings.value.appearance.groupCollections,
                scope = remembered?.scope ?: LibraryQuery.defaultScope(kind),
            ),
            title = route.title,
            userName = sessions.session.value?.user?.name.orEmpty(),
            // Dropped again below when the tab turns out not to list folders.
            folderPath = remembered?.folderPath.orEmpty(),
        ),
    )
    val state: StateFlow<LibraryState> = _state.asStateFlow()

    /** How far a big list has come while the grid waits for it. */
    val progress: StateFlow<LoadProgress?> = repository.progress

    /** The folder path came from the memory and has not loaded yet: a folder that is gone sends the tab back to the top. */
    private var restoredPath = remembered?.folderPath?.isNotEmpty() == true

    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /** The options of a song of the songs tab. */
    val musicActions = MusicActions(
        scope = viewModelScope,
        controller = music,
        music = songs,
        toast = { _events.send(it) },
    )

    private var loadJob: Job? = null
    private var lettersJob: Job? = null
    private var refreshJob: Job? = null

    init {
        // The kept sort and toggles come from the settings file, which may not be read yet right after start.
        viewModelScope.launch {
            val current = runSuspendCatching { settings.current() }
                .onFailure { Log.w(TAG, "Reading the library sort failed", it) }
                .getOrNull()
            if (current != null) {
                val filters = current.libraryFilters[kind.name]
                _state.update {
                    it.copy(
                        query = it.query.withKeptSort(current.librarySorts).copy(
                            favoritesOnly = filters?.favoritesOnly ?: false,
                            unwatchedOnly = filters?.unwatchedOnly ?: false,
                        ),
                    )
                }
            }
            val view = runSuspendCatching { settings.current().musicVideoView }.getOrNull()
            _state.update { it.copy(musicVideoView = view, folderPath = it.folderPath.takeIf { view == MusicVideoView.Folders }.orEmpty()) }
            reload()
        }
        // Marked watched (here, on a detail page or by watching it): the grid shows it at once,
        // also while it waits behind the detail page.
        viewModelScope.launch { details.playedChanged.collect { refresh() } }
        viewModelScope.launch { playback.stopped.collect { refresh() } }
        viewModelScope.launch { home.kinds.collect { kinds -> _state.update { it.copy(musicVideos = LibraryKind.MusicVideos in kinds) } } }
    }

    /**
     * What is loaded, loaded again in place: the grid keeps its scroll and focus.
     * Unwatched drops what was watched meanwhile.
     */
    private fun refresh() {
        val current = _state.value
        if (current.items.isEmpty() || loadJob?.isActive == true) return
        val query = current.query
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            try {
                val page = repository.page(query, start = current.offset, limit = current.items.size)
                _state.update { if (it.query == query && it.offset == current.offset && loadJob?.isActive != true) it.copy(items = page.items, total = page.total) else it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Refreshing the library failed", e)
            }
        }
    }

    /** Called by the grid when it gets near the end of what is loaded. */
    fun loadMore() {
        val current = _state.value
        if (current.inFolders || current.loading || current.failed || current.complete) return
        loadUpTo(current.offset + current.items.size + PAGE_SIZE - 1)
    }

    /** Back to the toolbar: a list that starts further down (after a jump) starts at the top again. */
    fun toStart() {
        if (_state.value.offset > 0) reload()
    }

    /** Called by the grid of [columns] when it gets near the start of what is loaded, after a jump: the titles before it. */
    fun loadBefore(columns: Int) {
        val current = _state.value
        if (current.offset == 0 || current.loading || current.failed || loadJob?.isActive == true) return
        val query = current.query
        loadJob = viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            try {
                // From the start of a row, so the rows already there stay as they are.
                val start = repository.rowStart(query, (current.offset - PAGE_SIZE).coerceAtLeast(0), columns)
                val page = repository.page(query, start = start, limit = current.offset - start)
                _state.update {
                    if (it.query != query || it.offset != current.offset) it.copy(loading = false)
                    else it.copy(items = page.items + it.items, offset = start, total = page.total, loading = false)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Loading library failed", e)
                _state.update { it.copy(loading = false, failed = true) }
            }
        }
    }

    fun retry() = reload()

    /** Each music tab has its own sort, so a tab change also brings back the sort kept for it. */
    fun setScope(scope: LibraryScope) {
        if (scope == _state.value.query.scope) return
        val kept = settings.settings.value.librarySorts
        update { it.copy(scope = scope).withKeptSort(kept) }
    }

    /** The heart toggle; it stays on across the tabs. */
    fun toggleFavoritesOnly() {
        update { it.copy(favoritesOnly = !it.favoritesOnly) }
        saveFilters()
    }

    /** The "Unwatched" toggle of movies and shows; it stays on across the tabs. */
    fun toggleUnwatchedOnly() {
        update { it.copy(unwatchedOnly = !it.unwatchedOnly) }
        saveFilters()
    }

    /** The toggles are kept for the profile, like the sort, so they outlast the app. */
    private fun saveFilters() {
        val query = _state.value.query
        val choice = LibraryFilterChoice(query.favoritesOnly, query.unwatchedOnly)
        viewModelScope.launch {
            runSuspendCatching { settings.update { it.copy(libraryFilters = it.libraryFilters + (kind.name to choice)) } }
                .onFailure { Log.w(TAG, "Saving the library filters failed", it) }
        }
    }

    fun toggleSortMenu(open: Boolean = !_state.value.sortMenuOpen) = _state.update { it.copy(sortMenuOpen = open) }

    fun toggleViewMenu(open: Boolean = !_state.value.viewMenuOpen) = _state.update { it.copy(viewMenuOpen = open) }

    /** The music videos tab's view, kept for the profile; a new view starts at the top folder. */
    fun setMusicVideoView(view: MusicVideoView) {
        _state.update { it.copy(viewMenuOpen = false) }
        if (view == _state.value.musicVideoView) return
        _state.update { it.copy(musicVideoView = view, folderPath = emptyList(), folder = null) }
        remember(focusId = null)
        reload()
        viewModelScope.launch {
            runSuspendCatching { settings.update { it.copy(musicVideoView = view) } }
                .onFailure { Log.w(TAG, "Saving the music video view failed", it) }
        }
    }

    /** Opens a subfolder in the folder view. */
    fun openFolder(folder: MediaFolder) {
        _state.update { it.copy(folderPath = it.folderPath + folder, folder = null) }
        remember(focusId = null)
        reload()
    }

    /** Up a folder in the folder view; false at the top. */
    fun folderUp(): Boolean {
        if (_state.value.folderPath.isEmpty()) return false
        _state.update { it.copy(folderPath = it.folderPath.dropLast(1), folder = null) }
        remember(focusId = null)
        reload()
        return true
    }

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
            runSuspendCatching { settings.update { it.copy(librarySorts = it.librarySorts + (query.sortKey to choice)) } }
                .onFailure { Log.w(TAG, "Saving the library sort failed", it) }
        }
    }

    /**
     * Scrolls to the first title filed under [letter]. When that is far beyond what is loaded,
     * the list starts anew a few rows of [columns] before it.
     */
    fun jumpToLetter(letter: Char, columns: Int) {
        val current = _state.value
        if (current.total == null) return
        viewModelScope.launch {
            val index = runSuspendCatching { repository.indexOfLetter(current.query, letter) }
                .onFailure { Log.w(TAG, "A-Z jump failed", it) }
                .getOrNull() ?: return@launch
            if (!loadAround(index, columns)) return@launch
            _state.update { it.copy(jumpTo = (index - it.offset).coerceIn(0, (it.items.size - 1).coerceAtLeast(0))) }
        }
    }

    /**
     * Loads the title [id] when it is further down than what is loaded, as an A–Z jump would;
     * true when it is loaded then. Back after "More like this" focuses the title it came from.
     */
    suspend fun reveal(id: String, columns: Int): Boolean {
        val current = _state.value
        if (current.items.any { it.id.toString() == id }) return true
        val uuid = runCatching { UUID.fromString(id) }.getOrNull() ?: return false
        return viewModelScope.async {
            val index = runSuspendCatching { repository.indexOf(current.query, uuid) }.getOrNull() ?: return@async false
            loadAround(index, columns) && _state.value.items.any { it.id == uuid }
        }.await()
    }

    /** Loads up to position [index], or a list that starts a few rows of [columns] before it when that is far beyond what is loaded. */
    private suspend fun loadAround(index: Int, columns: Int): Boolean {
        val state = _state.value
        val end = state.offset + state.items.size
        if (index >= state.offset && index < end + PAGE_SIZE) {
            loadUpTo(index + PAGE_SIZE / 2)?.join()
            return true
        }
        val start = runSuspendCatching { repository.rowStart(state.query, (index - JUMP_ROWS_BEFORE * columns).coerceAtLeast(0), columns) }
            .getOrElse { return false }
        loadJob?.cancel()
        refreshJob?.cancel()
        val query = state.query
        loadJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, failed = false) }
            try {
                val page = repository.page(query, start = start, limit = PAGE_SIZE)
                _state.update { if (it.query == query) it.copy(items = page.items, offset = start, total = page.total, loading = false) else it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Loading library failed", e)
                _state.update { it.copy(loading = false, failed = true) }
            }
        }.also { it.join() }
        return true
    }

    fun jumpHandled() = _state.update { it.copy(jumpTo = null) }

    fun open(item: MediaItem) {
        val query = _state.value.query
        val route = when (item.kind) {
            ItemKind.Genre -> LibraryRoute(query.kind.name, query.libraryId?.toString(), title = item.title, genreId = item.id.toString())
            else -> routeFor(item)
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

    /**
     * A music video of the music videos tab plays in the music player with the videos after it
     * (in the grid, or in the folder shown) as the queue; [title] names the queue.
     */
    fun playVideo(item: MediaItem, title: String) {
        val state = _state.value
        val videos = state.folder?.takeIf { state.inFolders }?.media?.filterIsInstance<FolderMedia.Video>()?.map { it.item } ?: state.items
        val start = videos.indexOfFirst { it.id == item.id }.takeIf { it >= 0 } ?: return
        music.playSongs(title, videos.drop(start).map { it.id }, startTrackId = item.id.toString())
        viewModelScope.launch { _events.send(UiEvent.Navigate(MusicRoute())) }
    }

    /**
     * Play and shuffle of the music videos tab: every video in the tab's order, or in the folder
     * view those of the folder shown and the folders below it. [title] names the queue.
     */
    fun playVideos(title: String, shuffled: Boolean) = playVideos(title, shuffled) { shownVideoIds() }

    /** The videos [load] brings, in its order or [shuffled], in the music player; [title] names the queue. */
    private fun playVideos(title: String, shuffled: Boolean, load: suspend () -> List<UUID>) {
        viewModelScope.launch {
            val ids = runSuspendCatching { load() }.getOrElse {
                Log.w(TAG, "Loading the music videos to play failed", it)
                _events.send(UiEvent.Toast(R.string.music_videos_failed))
                return@launch
            }
            if (ids.isEmpty()) {
                _events.send(UiEvent.Toast(R.string.music_videos_none))
                return@launch
            }
            music.playSongs(title, if (shuffled) ids.shuffled() else ids)
            _events.send(UiEvent.Navigate(MusicRoute()))
        }
    }

    /** The videos Play would play: every one in the tab's order, or in the folder view those of the folder shown and below it. */
    private suspend fun shownVideoIds(): List<UUID> {
        val state = _state.value
        return if (state.inFolders) videoIdsBelow(state.folderPath.lastOrNull()?.id) else repository.ids(state.query)
    }

    /** The music videos in [folderId] and the folders below it (all of them without one), in the tab's order. */
    private suspend fun videoIdsBelow(folderId: UUID?): List<UUID> =
        folders.videosBelow(folderId, CollectionType.MUSICVIDEOS).videosFor(_state.value.query).map { it.item.id }

    /** Options for a music video of the tab (holding OK, or the menu key). */
    fun videoOptions(item: MediaItem) {
        musicActions.open(MusicTarget(title = item.title, subtitle = item.parentTitle, tracks = { songs.tracks(listOf(item.id)) }))
    }

    /** Options for a folder of the folder view, as for an album: its videos and those of the folders below it. */
    fun folderOptions(folder: MediaFolder) {
        musicActions.open(
            MusicTarget(
                title = folder.name,
                tracks = { songs.tracks(videoIdsBelow(folder.id)) },
                play = { playVideos(folder.name, shuffled = false) { videoIdsBelow(folder.id) } },
                shuffle = { playVideos(folder.name, shuffled = true) { videoIdsBelow(folder.id) } },
            ),
        )
    }

    /** The toolbar's options, as for an album: play or shuffle all, the queue, a playlist; [title] names them. */
    fun videosOptions(title: String) {
        musicActions.open(
            MusicTarget(
                title = title,
                tracks = { songs.tracks(shownVideoIds()) },
                play = { playVideos(title, shuffled = false) },
                shuffle = { playVideos(title, shuffled = true) },
            ),
        )
    }

    /** Options for a song of the songs tab (its "…", holding OK, or the menu key). */
    fun songOptions(item: MediaItem) {
        musicActions.open(
            MusicTarget(
                title = item.title,
                subtitle = listOfNotNull(item.parentTitle, item.album).joinToString(" · ").ifEmpty { null },
                tracks = { songs.tracks(listOf(item.id)) },
                mixFrom = item.id,
            ),
        )
    }

    fun onNav(target: NavTarget) {
        val event = sessions.navEvent(target, current = NavTarget.Library(_state.value.query.kind).takeIf { inNavBar }) ?: return
        viewModelScope.launch { _events.send(event) }
    }

    private fun update(change: (LibraryQuery) -> LibraryQuery) {
        _state.update { it.copy(query = change(it.query)) }
        // The title focused before may not be in the new list.
        remember(focusId = null)
        reload()
    }

    /** The grid reports the title that has the focus, for the way back through the navigation bar. */
    fun focused(id: String) = remember(focusId = id)

    private fun remember(focusId: String?) {
        if (!inNavBar) return
        val query = _state.value.query
        memory.remember(kind, LibraryMemory(query.scope, focusId, _state.value.folderPath))
    }

    private fun reload() {
        loadJob?.cancel()
        _state.update { it.copy(items = emptyList(), offset = 0, total = null, loading = false, failed = false, letters = emptySet(), jumpTo = null) }
        val current = _state.value
        if (current.query.scope == LibraryScope.MusicVideos) {
            if (current.musicVideoView == null) return chooseMusicVideoView()
            if (current.inFolders) return loadFolder()
        }
        loadUpTo(PAGE_SIZE - 1)
        lettersJob?.cancel()
        val query = _state.value.query
        if (query.railed) {
            lettersJob = viewModelScope.launch {
                runSuspendCatching { repository.availableLetters(query) }
                    .onSuccess { letters -> _state.update { if (it.query == query) it.copy(letters = letters) else it } }
                    .onFailure { Log.w(TAG, "Loading A-Z letters failed", it) }
            }
        }
    }

    /**
     * The music videos tab before the profile picked a view: the grid when most videos name their
     * artist (tagged libraries), else their folders. Decided anew each time until a view is picked.
     */
    private fun chooseMusicVideoView() {
        val query = _state.value.query
        loadJob = viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val share = runSuspendCatching { repository.artistShare(query) }
                .onFailure { Log.w(TAG, "Counting the music videos' artists failed", it) }
                .getOrDefault(1f)
            val view = if (share >= TAGGED_SHARE) MusicVideoView.All else MusicVideoView.Folders
            _state.update { it.copy(musicVideoView = view, loading = false, folderPath = it.folderPath.takeIf { view == MusicVideoView.Folders }.orEmpty()) }
            reload()
        }
    }

    /** The folder at the end of the folder path, its subfolders and videos in the tab's order. */
    private fun loadFolder() {
        val state = _state.value
        val query = state.query
        val path = state.folderPath
        loadJob = viewModelScope.launch {
            // A new sort keeps the folder on screen until it comes back in the new order.
            _state.update { it.copy(loading = true, failed = false) }
            try {
                val content = folders.load(path.lastOrNull()?.id, CollectionType.MUSICVIDEOS)
                val sorted = content.copy(folders = content.folders.foldersFor(query), media = content.media.filterIsInstance<FolderMedia.Video>().videosFor(query))
                if (restoredPath && content.isEmpty) return@launch backToTop()
                restoredPath = false
                _state.update {
                    if (it.query != query || it.folderPath != path) it
                    else it.copy(folder = sorted, total = sorted.folders.size + sorted.media.size, loading = false)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Loading the music video folder failed", e)
                if (restoredPath) return@launch backToTop()
                _state.update { it.copy(loading = false, failed = true) }
            }
        }
    }

    /** The remembered folder is gone: the folder view starts at its top folder instead. */
    private fun backToTop() {
        restoredPath = false
        _state.update { it.copy(folderPath = emptyList(), folder = null) }
        remember(focusId = null)
        reload()
    }

    /** Loads pages until position [index] is covered (or everything is loaded). */
    private fun loadUpTo(index: Int): Job? {
        val current = _state.value
        if (current.complete || current.inFolders) return null
        if (index < current.offset + current.items.size) return null
        loadJob?.let { if (it.isActive) return it }
        val query = current.query
        return viewModelScope.launch {
            _state.update { it.copy(loading = true, failed = false) }
            try {
                while (true) {
                    val state = _state.value
                    val end = state.offset + state.items.size
                    if ((state.total != null && end >= state.total) || end > index) break
                    val page = repository.page(query, start = end, limit = PAGE_SIZE)
                    if (_state.value.query != query || _state.value.offset != state.offset) return@launch
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
        /** From this share of music videos naming their artist on, their tab starts with all of them in one grid. */
        private const val TAGGED_SHARE = 0.5f
        /** Ten rows of seven posters. */
        const val PAGE_SIZE = 70
        /** Names sort A to Z first; dates, years, ratings and runtimes newest or largest first. */
        private val AscendingFirst = setOf(LibrarySort.Title, LibrarySort.Artist, LibrarySort.Album)
        /** Rows kept above the title an A–Z jump lands on, when the list starts anew there. */
        private const val JUMP_ROWS_BEFORE = 3
        /** Songs queued from the songs tab; their ids go into one request. */
        private const val SONG_QUEUE = 100
        private const val TAG = "Library"
    }
}

/** Subfolders by name, or newest first when the videos go by when they were added; [query]'s direction turns them around. */
private fun List<MediaFolder>.foldersFor(query: LibraryQuery): List<MediaFolder> {
    val byName = compareBy<MediaFolder, String>(NameOrder) { it.name }
    val order = if (query.sort == LibrarySort.DateAdded) compareBy<MediaFolder> { it.date }.then(byName) else byName
    return sortedWith(if (query.descending) order.reversed() else order)
}

/** A folder's videos in [query]'s order; those without what it sorts by come last, by title. */
private fun List<FolderMedia.Video>.videosFor(query: LibraryQuery): List<FolderMedia.Video> {
    val byTitle = compareBy<FolderMedia.Video, String>(NameOrder) { it.item.title }
    fun <K : Comparable<K>> by(key: (FolderMedia.Video) -> K?): List<FolderMedia.Video> {
        val (known, unknown) = partition { key(it) != null }
        val order = compareBy<FolderMedia.Video> { key(it) }.then(byTitle)
        return known.sortedWith(if (query.descending) order.reversed() else order) + unknown.sortedWith(byTitle)
    }
    return when (query.sort) {
        LibrarySort.DateAdded -> by { it.added }
        LibrarySort.Year -> by { it.item.year }
        LibrarySort.Artist -> {
            val (known, unknown) = partition { it.item.parentTitle != null }
            val order = compareBy<FolderMedia.Video, String>(NameOrder) { it.item.parentTitle.orEmpty() }.then(byTitle)
            known.sortedWith(if (query.descending) order.reversed() else order) + unknown.sortedWith(byTitle)
        }
        else -> sortedWith(if (query.descending) byTitle.reversed() else byTitle)
    }
}
