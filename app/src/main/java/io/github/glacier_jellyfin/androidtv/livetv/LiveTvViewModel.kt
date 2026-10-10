package io.github.glacier_jellyfin.androidtv.livetv

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.data.SessionManager
import io.github.glacier_jellyfin.androidtv.core.data.media.LiveChannel
import io.github.glacier_jellyfin.androidtv.core.data.media.LiveProgram
import io.github.glacier_jellyfin.androidtv.core.data.media.LiveTvRepository
import io.github.glacier_jellyfin.androidtv.core.data.media.halfHourFloor
import io.github.glacier_jellyfin.androidtv.core.log.Log
import io.github.glacier_jellyfin.androidtv.navigation.FavoritesRoute
import io.github.glacier_jellyfin.androidtv.navigation.HomeRoute
import io.github.glacier_jellyfin.androidtv.navigation.LibraryRoute
import io.github.glacier_jellyfin.androidtv.navigation.LiveTvPlayerRoute
import io.github.glacier_jellyfin.androidtv.navigation.MusicRoute
import io.github.glacier_jellyfin.androidtv.navigation.ProfilesRoute
import io.github.glacier_jellyfin.androidtv.navigation.SearchRoute
import io.github.glacier_jellyfin.androidtv.navigation.SettingsRoute
import io.github.glacier_jellyfin.androidtv.ui.NavTarget
import io.github.glacier_jellyfin.androidtv.ui.UiEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/** The two views of the Live TV page. */
enum class LiveTvView { Channels, Guide }

data class LiveTvState(
    val loading: Boolean = true,
    val failed: Boolean = false,
    val channels: List<LiveChannel>? = null,
    /** Only the channels marked with the heart. */
    val favoritesOnly: Boolean = false,
    /** The clock the programme times and progress bars follow. */
    val nowMs: Long = System.currentTimeMillis(),
    /** The channel with the focus, shown in the panel beside the list. */
    val focused: UUID? = null,
    /** Coming up on the focused channel, once loaded. */
    val upcoming: Map<UUID, List<LiveProgram>> = emptyMap(),
    val userName: String = "",
    val view: LiveTvView = LiveTvView.Channels,
    /** The guide's programmes by channel, loaded for the rows on screen. */
    val guide: Map<UUID, List<LiveProgram>> = emptyMap(),
    /** What the guide covers: from the current half hour on. */
    val guideFromMs: Long = halfHourFloor(System.currentTimeMillis()),
    val guideToMs: Long = halfHourFloor(System.currentTimeMillis()) + GUIDE_SPAN_MS,
) {
    val shown: List<LiveChannel>? get() = if (favoritesOnly) channels?.filter { it.favorite } else channels
}

@HiltViewModel
class LiveTvViewModel @Inject constructor(
    private val repository: LiveTvRepository,
    private val sessions: SessionManager,
) : ViewModel() {

    private val _state = MutableStateFlow(LiveTvState(userName = sessions.session.value?.user?.name.orEmpty()))
    val state: StateFlow<LiveTvState> = _state.asStateFlow()

    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var upcomingJob: Job? = null
    /** Channels whose guide is loaded or on its way. */
    private val guideRequested = mutableSetOf<UUID>()

    init {
        load()
        viewModelScope.launch {
            while (isActive) {
                delay(CLOCK_TICK_MS)
                val now = System.currentTimeMillis()
                _state.update { it.copy(nowMs = now) }
                // A new half hour: the guide starts there now; what ended drops off.
                if (halfHourFloor(now) != _state.value.guideFromMs) {
                    guideRequested.clear()
                    _state.update { it.copy(guide = emptyMap(), guideFromMs = halfHourFloor(now), guideToMs = halfHourFloor(now) + GUIDE_SPAN_MS) }
                }
                // A programme ended: the guide moved on.
                if (_state.value.channels?.any { channel -> channel.now?.let { it.endMs <= now } == true } == true) refresh()
            }
        }
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, failed = false) }
            runCatching { repository.channels() }
                .onSuccess { channels -> _state.update { it.copy(loading = false, channels = channels, nowMs = System.currentTimeMillis()) } }
                .onFailure { error ->
                    Log.w(TAG, "Loading channels failed", error)
                    _state.update { it.copy(loading = false, failed = true) }
                }
        }
    }

    /** In the background: what is on screen stays if it fails. */
    private fun refresh() {
        viewModelScope.launch {
            runCatching { repository.channels() }
                .onSuccess { channels -> _state.update { it.copy(channels = channels, upcoming = emptyMap()) } }
                .onFailure { Log.w(TAG, "Refreshing channels failed", it) }
            _state.value.focused?.let(::loadUpcoming)
        }
    }

    fun setFavoritesOnly(on: Boolean) = _state.update { it.copy(favoritesOnly = on) }

    fun setView(view: LiveTvView) = _state.update { it.copy(view = view) }

    /** The guide of [channelIds] (the rows on screen and a few more), for those not loaded yet, in one request. */
    fun loadGuide(channelIds: List<UUID>) {
        val missing = channelIds.filter { guideRequested.add(it) }
        if (missing.isEmpty()) return
        val state = _state.value
        viewModelScope.launch {
            try {
                val programs = repository.guide(missing, state.guideFromMs, state.guideToMs)
                _state.update { it.copy(guide = it.guide + missing.associateWith { id -> programs[id].orEmpty() }) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Loading the guide failed", e)
                guideRequested.removeAll(missing.toSet())
            }
        }
    }

    /** OK on a guide programme: watch it when it runs, else say when it starts. */
    fun openProgram(channel: LiveChannel, program: LiveProgram?) {
        val now = System.currentTimeMillis()
        if (program == null || program.startMs <= now) {
            play(channel)
        } else {
            viewModelScope.launch { _events.send(UiEvent.Toast(R.string.livetv_starts_at, listOf(program.title, clockText(program.startMs)))) }
        }
    }

    /** The panel follows the focus; what comes next loads after a short rest, not for every channel passed. */
    fun onFocus(channel: LiveChannel) {
        if (_state.value.focused == channel.id) return
        _state.update { it.copy(focused = channel.id) }
        loadUpcoming(channel.id)
    }

    private fun loadUpcoming(channelId: UUID) {
        if (channelId in _state.value.upcoming) return
        upcomingJob?.cancel()
        upcomingJob = viewModelScope.launch {
            delay(UPCOMING_DELAY_MS)
            try {
                val programs = repository.programs(channelId, hours = UPCOMING_HOURS)
                _state.update { it.copy(upcoming = it.upcoming + (channelId to programs)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Loading the programmes of $channelId failed", e)
            }
        }
    }

    fun play(channel: LiveChannel) = navigate(LiveTvPlayerRoute(channel.id.toString()))

    fun toggleFavorite(channel: LiveChannel) {
        val favorite = !channel.favorite
        _state.update { state -> state.copy(channels = state.channels?.map { if (it.id == channel.id) it.copy(favorite = favorite) else it }) }
        viewModelScope.launch {
            try {
                repository.setFavorite(channel.id, favorite)
                _events.send(UiEvent.Toast(if (favorite) R.string.livetv_favorite_added else R.string.livetv_favorite_removed, listOf(channel.name)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Changing the favorite failed", e)
                _state.update { state -> state.copy(channels = state.channels?.map { if (it.id == channel.id) it.copy(favorite = !favorite) else it }) }
                _events.send(UiEvent.Toast(R.string.livetv_favorite_failed))
            }
        }
    }

    fun onNav(target: NavTarget) {
        when (target) {
            NavTarget.LiveTv -> Unit
            NavTarget.Home -> viewModelScope.launch { _events.send(UiEvent.Navigate(HomeRoute, clearBackStack = true)) }
            NavTarget.Search -> navigate(SearchRoute())
            NavTarget.Settings -> navigate(SettingsRoute)
            NavTarget.Favorites -> navigate(FavoritesRoute)
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
        const val TAG = "LiveTv"
        const val CLOCK_TICK_MS = 30_000L
        const val UPCOMING_DELAY_MS = 350L
        const val UPCOMING_HOURS = 6L
    }
}

/** How far ahead the guide reaches. */
private const val GUIDE_SPAN_MS = 24 * 60 * 60_000L
