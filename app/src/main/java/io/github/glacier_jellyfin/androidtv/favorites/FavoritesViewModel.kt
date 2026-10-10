package io.github.glacier_jellyfin.androidtv.favorites

import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.SharingStarted
import io.github.glacier_jellyfin.androidtv.core.data.settings.arranged
import io.github.glacier_jellyfin.androidtv.core.data.settings.SettingsRepository
import io.github.glacier_jellyfin.androidtv.core.data.settings.NavigationSettings
import io.github.glacier_jellyfin.androidtv.core.data.settings.FavoriteRow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.glacier_jellyfin.androidtv.core.data.SessionManager
import io.github.glacier_jellyfin.androidtv.core.data.media.DetailRepository
import io.github.glacier_jellyfin.androidtv.core.data.media.Favorites
import io.github.glacier_jellyfin.androidtv.core.data.media.FavoritesRepository
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaItem
import io.github.glacier_jellyfin.androidtv.core.data.media.MusicTrack
import io.github.glacier_jellyfin.androidtv.core.data.playback.PlaybackRepository
import io.github.glacier_jellyfin.androidtv.core.log.Log
import io.github.glacier_jellyfin.androidtv.music.MusicController
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FavoritesState(
    val loading: Boolean = true,
    val failed: Boolean = false,
    val favorites: Favorites? = null,
    val userName: String = "",
)

@HiltViewModel
class FavoritesViewModel @Inject constructor(
    private val repository: FavoritesRepository,
    private val sessions: SessionManager,
    private val music: MusicController,
    details: DetailRepository,
    playback: PlaybackRepository,
    settings: SettingsRepository,
) : ViewModel() {

    /** The rows Settings › Appearance switched on, in its order. */
    val rows: StateFlow<List<FavoriteRow>> = settings.settings
        .map { profile -> shownRows(profile.navigation) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, shownRows(settings.settings.value.navigation))

    private fun shownRows(navigation: NavigationSettings): List<FavoriteRow> =
        navigation.favoriteRows.arranged(FavoriteRow.entries.map { it.name }).filter { it.shown }.map { FavoriteRow.valueOf(it.id) }

    private val _state = MutableStateFlow(FavoritesState(userName = sessions.session.value?.user?.name.orEmpty()))
    val state: StateFlow<FavoritesState> = _state.asStateFlow()

    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        load()
        // A heart set or taken away on a detail page, or progress after watching: reload quietly.
        viewModelScope.launch { details.favoriteChanged.collect { refresh() } }
        viewModelScope.launch { playback.stopped.collect { refresh() } }
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, failed = false) }
            runCatching { repository.load() }
                .onSuccess { favorites -> _state.update { it.copy(loading = false, favorites = favorites) } }
                .onFailure { error ->
                    Log.w(TAG, "Loading favorites failed", error)
                    _state.update { it.copy(loading = false, failed = true) }
                }
        }
    }

    /** In the background: what is on screen stays if it fails. */
    private fun refresh() {
        viewModelScope.launch {
            runCatching { repository.load() }
                .onSuccess { favorites -> _state.update { it.copy(favorites = favorites) } }
                .onFailure { Log.w(TAG, "Refreshing favorites failed", it) }
        }
    }

    fun open(item: MediaItem) = navigate(DetailRoute(item.id.toString()))

    /** All favorite songs play, from this one, and the player opens; [title] names the queue. */
    fun play(track: MusicTrack, title: String) {
        val songs = _state.value.favorites?.songs ?: return
        music.playTracks(kind = null, title = title, tracks = songs, startTrackId = track.id.toString())
        navigate(MusicRoute())
    }

    fun onNav(target: NavTarget) {
        when (target) {
            NavTarget.Favorites -> Unit
            NavTarget.Home -> viewModelScope.launch { _events.send(UiEvent.Navigate(HomeRoute, clearBackStack = true)) }
            NavTarget.Search -> navigate(SearchRoute())
            NavTarget.Settings -> navigate(SettingsRoute)
            is NavTarget.Library -> navigate(LibraryRoute(target.kind.name))
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
        const val TAG = "Favorites"
    }
}
