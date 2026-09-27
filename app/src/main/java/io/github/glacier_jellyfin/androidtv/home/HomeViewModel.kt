package io.github.glacier_jellyfin.androidtv.home

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.data.SessionManager
import io.github.glacier_jellyfin.androidtv.core.data.media.HomeContent
import io.github.glacier_jellyfin.androidtv.core.data.media.HomeRepository
import io.github.glacier_jellyfin.androidtv.core.data.media.ItemKind
import io.github.glacier_jellyfin.androidtv.core.data.media.Library
import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryKind
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaItem
import io.github.glacier_jellyfin.androidtv.navigation.DetailRoute
import io.github.glacier_jellyfin.androidtv.navigation.LibraryRoute
import io.github.glacier_jellyfin.androidtv.navigation.PlayerRoute
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
import java.util.UUID
import javax.inject.Inject

/**
 * Spotlight options from the design's settings (Settings › Home). Fixed to the
 * design's defaults until the settings screen exists.
 */
data class SpotlightSettings(
    val count: Int = 5,
    val rotateSeconds: Int = 9,
)

data class HomeState(
    val loading: Boolean = true,
    val failed: Boolean = false,
    val content: HomeContent? = null,
    val spotlight: List<MediaItem> = emptyList(),
    val userName: String = "",
    val settings: SpotlightSettings = SpotlightSettings(),
) {
    val kinds: List<LibraryKind> get() = content?.libraries?.map { it.kind }?.distinct()?.sorted().orEmpty()
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: HomeRepository,
    private val sessions: SessionManager,
) : ViewModel() {

    private val _state = MutableStateFlow(HomeState(userName = sessions.session.value?.user?.name.orEmpty()))
    val state: StateFlow<HomeState> = _state.asStateFlow()

    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, failed = false) }
            runCatching { repository.load() }
                .onSuccess { content ->
                    _state.update { it.copy(loading = false, content = content, spotlight = spotlightOf(content, it.settings)) }
                }
                .onFailure { error ->
                    Log.w(TAG, "Loading home failed", error)
                    _state.update { it.copy(loading = false, failed = true) }
                }
        }
    }

    /** Spotlight source "Continue watching"; falls back to the newest titles like the design does. */
    private fun spotlightOf(content: HomeContent, settings: SpotlightSettings): List<MediaItem> {
        val playable = { item: MediaItem -> item.kind != ItemKind.Album && item.backdropUrl != null }
        val continuing = content.continueWatching.filter(playable)
        val source = continuing.ifEmpty { content.latest.flatMap { it.second }.filter(playable) }
        return source.take(settings.count)
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

    fun play(item: MediaItem) = navigate(PlayerRoute(item.id.toString()))

    fun openDetails(item: MediaItem) = navigate(DetailRoute(item.id.toString()))

    /** Design: episodes open their episode page, movies start playing. */
    fun openContinueWatching(item: MediaItem) =
        if (item.kind == ItemKind.Episode) openDetails(item) else play(item)

    fun openLibrary(library: Library) = navigate(LibraryRoute(library.kind.name, library.id.toString(), title = library.name))

    fun onNav(target: NavTarget) {
        when (target) {
            NavTarget.Home -> Unit
            NavTarget.Search -> navigate(SearchRoute)
            NavTarget.Settings -> navigate(SettingsRoute)
            is NavTarget.Library -> navigate(LibraryRoute(target.kind.name))
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
