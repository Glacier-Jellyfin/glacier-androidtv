package io.github.glacier_jellyfin.androidtv.search

import io.github.glacier_jellyfin.androidtv.core.log.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.glacier_jellyfin.androidtv.core.data.ParentalControl
import io.github.glacier_jellyfin.androidtv.core.data.SessionManager
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaItem
import io.github.glacier_jellyfin.androidtv.core.data.media.SearchRepository
import io.github.glacier_jellyfin.androidtv.core.data.media.SeerrItem
import io.github.glacier_jellyfin.androidtv.core.data.media.SeerrRepository
import io.github.glacier_jellyfin.androidtv.core.data.media.SeerrStatus
import io.github.glacier_jellyfin.androidtv.navigation.FavoritesRoute
import io.github.glacier_jellyfin.androidtv.navigation.DetailRoute
import io.github.glacier_jellyfin.androidtv.navigation.HomeRoute
import io.github.glacier_jellyfin.androidtv.navigation.LibraryRoute
import io.github.glacier_jellyfin.androidtv.navigation.LiveTvRoute
import io.github.glacier_jellyfin.androidtv.navigation.MusicRoute
import io.github.glacier_jellyfin.androidtv.navigation.ProfilesRoute
import io.github.glacier_jellyfin.androidtv.navigation.SearchRoute
import io.github.glacier_jellyfin.androidtv.navigation.SeerrRoute
import io.github.glacier_jellyfin.androidtv.navigation.SettingsRoute
import io.github.glacier_jellyfin.androidtv.ui.NavTarget
import io.github.glacier_jellyfin.androidtv.ui.UiEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SearchState(
    val query: String = "",
    /** Library hits for [resultsFor]; suggestions while the query is empty. */
    val results: List<MediaItem> = emptyList(),
    /** Titles to request through Seerr, shown after the library hits. */
    val seerr: List<SeerrItem> = emptyList(),
    /** Seerr is still being asked for [resultsFor]. */
    val seerrLoading: Boolean = false,
    /** The query [results] belong to; null for suggestions. */
    val resultsFor: String? = null,
    val loading: Boolean = false,
    val failed: Boolean = false,
    val userName: String = "",
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: SearchRepository,
    private val seerr: SeerrRepository,
    private val parental: ParentalControl,
    private val sessions: SessionManager,
) : ViewModel() {

    private val _state = MutableStateFlow(
        SearchState(
            query = savedStateHandle.toRoute<SearchRoute>().query.orEmpty(),
            userName = sessions.session.value?.user?.name.orEmpty(),
        ),
    )
    val state: StateFlow<SearchState> = _state.asStateFlow()

    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var searchJob: Job? = null

    init {
        search(_state.value.query, debounce = false)
    }

    fun setQuery(query: String) {
        if (query == _state.value.query) return
        _state.update { it.copy(query = query) }
        search(query, debounce = true)
    }

    fun retry() = search(_state.value.query, debounce = false)

    private fun search(query: String, debounce: Boolean) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            // Wait for a typing pause so every key press does not hit the server.
            if (debounce) delay(DEBOUNCE_MS)
            _state.update { it.copy(loading = true, failed = false) }
            val term = query.trim()
            try {
                if (term.isEmpty()) {
                    val results = repository.suggestions()
                    _state.update { it.copy(loading = false, results = results, resultsFor = null, seerr = emptyList(), seerrLoading = false) }
                } else {
                    searchBoth(term)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Search failed", e)
                _state.update { it.copy(loading = false, failed = true, seerrLoading = false) }
            }
        }
    }

    /** Library hits show at once; Seerr's follow when they arrive and never fail the search. */
    private suspend fun searchBoth(term: String) = coroutineScope {
        // Requests are kept away from profiles with an age limit.
        val found = async { if (parental.lock.value.protection.restricts) emptyList() else softly { seerr.search(term) } }
        val results = repository.search(term)
        _state.update { it.copy(loading = false, results = results, resultsFor = term, seerr = emptyList(), seerrLoading = true) }
        val hits = found.await()
        // Titles Seerr knows in the library but the title search missed (another language, say).
        val known = results.mapTo(HashSet()) { it.id }
        val extra = softly { repository.byIds(hits.mapNotNull { it.jellyfinId }.filter { it !in known }.distinct()) }
        val requestable = hits.filter { it.jellyfinId == null && it.status != SeerrStatus.Available && it.status != SeerrStatus.Blocklisted }
        _state.update { it.copy(results = results + extra, seerr = requestable, seerrLoading = false) }
    }

    private suspend fun <T> softly(block: suspend () -> List<T>): List<T> = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Seerr search failed", e)
        emptyList()
    }

    fun open(item: MediaItem) = navigate(DetailRoute(item.id.toString()))

    fun open(item: SeerrItem) = navigate(SeerrRoute(item.type.name, item.tmdbId))

    fun onNav(target: NavTarget) {
        when (target) {
            NavTarget.Search -> Unit
            NavTarget.Home -> viewModelScope.launch { _events.send(UiEvent.Navigate(HomeRoute, clearBackStack = true)) }
            NavTarget.Settings -> navigate(SettingsRoute)
            is NavTarget.Library -> navigate(LibraryRoute(target.kind.name))
            NavTarget.Favorites -> navigate(FavoritesRoute)
            NavTarget.NowPlaying -> navigate(MusicRoute())
            NavTarget.LiveTv -> navigate(LiveTvRoute)
            NavTarget.Profile -> {
                val serverId = sessions.session.value?.server?.id ?: return
                sessions.leave()
                viewModelScope.launch { _events.send(UiEvent.Navigate(ProfilesRoute(serverId), clearBackStack = true)) }
            }
        }
    }

    private fun navigate(route: Any) {
        viewModelScope.launch { _events.send(UiEvent.Navigate(route)) }
    }

    private companion object {
        const val TAG = "Search"
        const val DEBOUNCE_MS = 350L
    }
}
