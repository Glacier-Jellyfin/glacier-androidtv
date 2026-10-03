package io.github.glacier_jellyfin.androidtv.home

import io.github.glacier_jellyfin.androidtv.core.log.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.channels.HomeLaunches
import io.github.glacier_jellyfin.androidtv.core.data.ParentalControl
import io.github.glacier_jellyfin.androidtv.core.data.media.AgeFilter
import io.github.glacier_jellyfin.androidtv.core.data.media.DetailRepository
import io.github.glacier_jellyfin.androidtv.core.data.SessionManager
import io.github.glacier_jellyfin.androidtv.core.data.media.HomeContent
import io.github.glacier_jellyfin.androidtv.core.data.media.HomeRepository
import io.github.glacier_jellyfin.androidtv.core.data.playback.PlaybackRepository
import io.github.glacier_jellyfin.androidtv.core.data.settings.HomeSettings
import io.github.glacier_jellyfin.androidtv.core.data.settings.SettingsRepository
import io.github.glacier_jellyfin.androidtv.core.data.settings.SpotlightSource
import io.github.glacier_jellyfin.androidtv.core.data.media.ItemKind
import io.github.glacier_jellyfin.androidtv.core.data.media.Library
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaItem
import io.github.glacier_jellyfin.androidtv.core.data.media.MusicTrack
import io.github.glacier_jellyfin.androidtv.music.MusicController
import io.github.glacier_jellyfin.androidtv.navigation.DetailRoute
import io.github.glacier_jellyfin.androidtv.navigation.LibraryRoute
import io.github.glacier_jellyfin.androidtv.navigation.MusicRoute
import io.github.glacier_jellyfin.androidtv.navigation.PlayerRoute
import io.github.glacier_jellyfin.androidtv.navigation.ProfilesRoute
import io.github.glacier_jellyfin.androidtv.navigation.SearchRoute
import io.github.glacier_jellyfin.androidtv.navigation.SettingsRoute
import io.github.glacier_jellyfin.androidtv.ui.NavTarget
import io.github.glacier_jellyfin.androidtv.ui.PinGate
import io.github.glacier_jellyfin.androidtv.ui.UiEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class HomeState(
    val loading: Boolean = true,
    val failed: Boolean = false,
    val content: HomeContent? = null,
    val spotlight: List<MediaItem> = emptyList(),
    val userName: String = "",
    val settings: HomeSettings = HomeSettings(),
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: HomeRepository,
    private val sessions: SessionManager,
    private val settings: SettingsRepository,
    private val parental: ParentalControl,
    private val ageFilter: AgeFilter,
    playback: PlaybackRepository,
    details: DetailRepository,
    private val music: MusicController,
    launches: HomeLaunches,
) : ViewModel() {

    private val _state = MutableStateFlow(
        HomeState(userName = sessions.session.value?.user?.name.orEmpty(), settings = settings.settings.value.home),
    )
    val state: StateFlow<HomeState> = _state.asStateFlow()

    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    val pin = PinGate(viewModelScope, parental) { _events.send(it) }

    init {
        // A title picked on the Android TV home screen opens on top of Home.
        sessions.session.value?.let { launches.take(it.server.id, it.user.userId) }?.let { launch ->
            viewModelScope.launch { _events.send(UiEvent.Navigate(DetailRoute(launch.itemId))) }
        }
        load()
        // Back from the player: "continue watching" and progress bars follow what was just watched.
        viewModelScope.launch { playback.stopped.collect { refresh() } }
        // Marked watched or unwatched on a detail page.
        viewModelScope.launch { details.playedChanged.collect { refresh() } }
        // Settings › Home changed: only the spotlight follows.
        viewModelScope.launch {
            settings.settings.map { it.home }.distinctUntilChanged().collect { home ->
                if (home == _state.value.settings) return@collect
                _state.update { it.copy(settings = home) }
                _state.value.content?.let { content -> updateSpotlight(content) }
            }
        }
    }

    /** Reloads in the background: no spinner, and what is on screen stays if it fails. */
    private fun refresh() {
        viewModelScope.launch {
            runCatching { repository.load() }
                .onSuccess { content ->
                    _state.update { it.copy(content = content) }
                    // Only "continue watching" follows what was just watched; the other sources stay put.
                    if (SpotlightSource.ContinueWatching in _state.value.settings.spotlightSources) updateSpotlight(content)
                }
                .onFailure { Log.w(TAG, "Refreshing home failed", it) }
        }
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, failed = false) }
            runCatching { repository.load() }
                .onSuccess { content ->
                    val spotlight = spotlightOf(content, _state.value.settings)
                    _state.update { it.copy(loading = false, content = content, spotlight = spotlight) }
                }
                .onFailure { error ->
                    Log.w(TAG, "Loading home failed", error)
                    _state.update { it.copy(loading = false, failed = true) }
                }
        }
    }

    private suspend fun updateSpotlight(content: HomeContent) {
        val spotlight = spotlightOf(content, _state.value.settings)
        _state.update { it.copy(spotlight = spotlight) }
    }

    /** What Settings › Home asks for; if the server cannot be asked, "continue watching" stands in. */
    private suspend fun spotlightOf(content: HomeContent, settings: HomeSettings): List<MediaItem> = try {
        repository.spotlight(settings, content.continueWatching)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Loading the spotlight failed", e)
        if (settings.spotlightSources.isEmpty()) emptyList()
        else content.continueWatching.filter { it.backdropUrl != null }.take(settings.spotlightCount.count)
    }


    fun toggleFavorite(item: MediaItem) {
        val favorite = !item.isFavorite
        updateItem(item.id) { it.copy(isFavorite = favorite) }
        viewModelScope.launch {
            runCatching { repository.setFavorite(item.id, favorite) }
                .onSuccess { _events.send(UiEvent.Toast(if (favorite) R.string.favorite_added else R.string.favorite_removed)) }
                .onFailure {
                    Log.w(TAG, "Changing favorite failed", it)
                    updateItem(item.id) { current -> current.copy(isFavorite = !favorite) }
                }
        }
    }

    private fun updateItem(id: UUID, change: (MediaItem) -> MediaItem) = _state.update { state ->
        val map = { item: MediaItem -> if (item.id == id) change(item) else item }
        state.copy(
            spotlight = state.spotlight.map(map),
            content = state.content?.let { content ->
                content.copy(
                    continueWatching = content.continueWatching.map(map),
                    latest = content.latest.map { (library, items) -> library to items.map(map) },
                )
            },
        )
    }

    /** Starts at once, so the age limit is checked here rather than on a detail page. */
    fun play(item: MediaItem) = pin.openTitle(
        ageFilter,
        id = item.id,
        name = item.title,
        rating = item.officialRating,
        parents = listOfNotNull(item.seriesId),
    ) { navigate(PlayerRoute(item.id.toString())) }

    fun openDetails(item: MediaItem) = navigate(DetailRoute(item.id.toString()))

    /** A favorite song: all of them play, from this one, and the player opens; [title] names the queue. */
    fun playFavorite(track: MusicTrack, title: String) {
        val favorites = _state.value.content?.favoriteSongs ?: return
        music.playTracks(kind = null, title = title, tracks = favorites, startTrackId = track.id.toString())
        navigate(MusicRoute())
    }

    /** Design: episodes open their episode page, movies start playing. */
    fun openContinueWatching(item: MediaItem) =
        if (item.kind == ItemKind.Episode) openDetails(item) else play(item)

    fun openLibrary(library: Library) = navigate(LibraryRoute(library.kind.name, library.id.toString(), title = library.name))

    fun onNav(target: NavTarget) {
        when (target) {
            NavTarget.Home -> Unit
            NavTarget.Search -> navigate(SearchRoute())
            NavTarget.Settings -> navigate(SettingsRoute)
            is NavTarget.Library -> navigate(LibraryRoute(target.kind.name))
            NavTarget.NowPlaying -> navigate(MusicRoute())
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
        const val TAG = "Home"
    }
}
