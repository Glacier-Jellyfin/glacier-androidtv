package io.github.glacier_jellyfin.androidtv.livetv

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.data.SessionManager
import io.github.glacier_jellyfin.androidtv.core.data.media.LiveChannel
import io.github.glacier_jellyfin.androidtv.core.data.media.LiveProgram
import io.github.glacier_jellyfin.androidtv.core.data.media.WatchedChannel
import io.github.glacier_jellyfin.androidtv.core.data.media.LiveRecording
import io.github.glacier_jellyfin.androidtv.core.data.media.LiveRecordings
import io.github.glacier_jellyfin.androidtv.core.data.media.LiveSeriesTimer
import io.github.glacier_jellyfin.androidtv.core.data.media.LiveTimer
import io.github.glacier_jellyfin.androidtv.core.data.media.LiveTvRepository
import io.github.glacier_jellyfin.androidtv.core.data.media.halfHourFloor
import io.github.glacier_jellyfin.androidtv.core.data.media.sortedChannels
import io.github.glacier_jellyfin.androidtv.core.data.playback.PlaybackRepository
import io.github.glacier_jellyfin.androidtv.core.data.playback.PlaybackSource
import io.github.glacier_jellyfin.androidtv.core.data.settings.ChannelSort
import io.github.glacier_jellyfin.androidtv.core.jellyfin.runSuspendCatching
import io.github.glacier_jellyfin.androidtv.core.log.Log
import io.github.glacier_jellyfin.androidtv.core.player.GlacierPlayback
import io.github.glacier_jellyfin.androidtv.core.player.GlacierPlayer
import io.github.glacier_jellyfin.androidtv.core.player.StreamRequest
import io.github.glacier_jellyfin.androidtv.music.MusicController
import io.github.glacier_jellyfin.androidtv.navigation.LiveTvPlayerRoute
import io.github.glacier_jellyfin.androidtv.navigation.MusicRoute
import io.github.glacier_jellyfin.androidtv.navigation.PlayerRoute
import io.github.glacier_jellyfin.androidtv.navigation.navEvent
import io.github.glacier_jellyfin.androidtv.ui.NavTarget
import io.github.glacier_jellyfin.androidtv.ui.UiEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/** The views of the Live TV page. */
enum class LiveTvView { Channels, Guide, Recordings, Radio }

/** The options sheet over the page. */
sealed interface LiveTvSheet {
    /** A guide programme: record it (or the series), and from a held OK also watch it or mark the channel. */
    data class Program(val channel: LiveChannel, val program: LiveProgram, val more: Boolean) : LiveTvSheet
    /** A channel or station of the list: favorite, and radio or TV. */
    data class Channel(val channel: LiveChannel) : LiveTvSheet
    data class Recording(val recording: LiveRecording) : LiveTvSheet
    data class ConfirmDelete(val recording: LiveRecording) : LiveTvSheet
    data class Timer(val timer: LiveTimer) : LiveTvSheet
    data class SeriesTimer(val timer: LiveSeriesTimer) : LiveTvSheet
}

data class LiveTvState(
    val loading: Boolean = true,
    val failed: Boolean = false,
    /** The TV channels; radio stations are in [stations]. */
    val channels: List<LiveChannel>? = null,
    val stations: List<LiveChannel> = emptyList(),
    /** Only the channels marked with the heart. */
    val favoritesOnly: Boolean = false,
    /** The profile's channel order, kept in its settings. */
    val sort: ChannelSort = ChannelSort.Number,
    val descending: Boolean = false,
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
    /** What the server recorded and will record; null until the view opens. */
    val recordings: LiveRecordings? = null,
    val recordingsLoading: Boolean = false,
    val recordingsFailed: Boolean = false,
    val sheet: LiveTvSheet? = null,
    /** The focused channel playing without sound in the panel, once the focus rested on it. */
    val preview: ExoPlayer? = null,
    val previewChannel: UUID? = null,
    /** The preview has a picture to show; until then (and for radio) the panel keeps its image. */
    val previewShown: Boolean = false,
) {
    /** What the list of the view holds: the radio stations or the TV channels. */
    val listed: List<LiveChannel>? get() = if (view == LiveTvView.Radio) stations else channels

    val shown: List<LiveChannel>? get() = if (favoritesOnly) listed?.filter { it.favorite } else listed

    /** Everything, TV and radio, as the server lists it. */
    val all: List<LiveChannel> get() = channels.orEmpty() + stations

    fun withChannels(all: List<LiveChannel>): LiveTvState = copy(channels = all.filterNot { it.radio }, stations = all.filter { it.radio })
}

@HiltViewModel
class LiveTvViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val repository: LiveTvRepository,
    private val playback: PlaybackRepository,
    private val handoff: LiveStreamHandoff,
    private val music: MusicController,
    private val sessions: SessionManager,
) : ViewModel(), RecordActions {

    /** The channel watched last in the player, for the list and the guide to land on. */
    val lastWatched: StateFlow<WatchedChannel?> = repository.lastWatched

    private val _state = MutableStateFlow(LiveTvState(userName = sessions.session.value?.user?.name.orEmpty()))
    val state: StateFlow<LiveTvState> = _state.asStateFlow()

    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var upcomingJob: Job? = null
    /** The preview the player took over; the panel still shows it until this screen stops. */
    private var handedOver: Player? = null
    private var previewJob: Job? = null
    private var previewSource: PlaybackSource? = null
    private var previewPlayback: GlacierPlayback? = null
    private val previewListener = object : Player.Listener {
        override fun onRenderedFirstFrame() {
            _state.update { it.copy(previewShown = true) }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState != Player.STATE_ENDED) return
            // The server ended the stream: one that played starts again, one that never did goes away.
            val played = _state.value.previewShown
            Log.i(TAG, "The server ended the preview of ${_state.value.previewChannel}" + if (played) ", starting it again" else "")
            stopPreview()
            if (played) schedulePreview()
        }

        override fun onPlayerError(error: PlaybackException) {
            // A preview is a nicety: it just goes away.
            Log.w(TAG, "The preview failed", error)
            stopPreview()
        }
    }
    /** Channels whose guide is loaded or on its way. */
    private val guideRequested = mutableSetOf<UUID>()

    init {
        load()
        // A channel moved to radio or TV (here, or found out by the player): the lists follow.
        viewModelScope.launch { repository.radioMarks.drop(1).collect { refresh() } }
        viewModelScope.launch { repository.radioMarks.collect { marks -> _state.update { it.copy(sort = marks.sort, descending = marks.descending) } } }
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
                if (_state.value.all.any { channel -> channel.now?.let { it.endMs <= now } == true }) refresh()
                // A recording started or finished.
                val recordings = _state.value.recordings
                if (recordings != null && recordings.scheduled.any { it.startMs in (now - CLOCK_TICK_MS)..now || it.endMs <= now }) loadRecordings()
            }
        }
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, failed = false) }
            runSuspendCatching { repository.channels() }
                .onSuccess { channels ->
                    _state.update { state ->
                        val loaded = state.withChannels(channels).copy(loading = false, nowMs = System.currentTimeMillis())
                        // A server with radio only opens on it.
                        if (loaded.channels.isNullOrEmpty() && loaded.stations.isNotEmpty() && state.channels == null) loaded.copy(view = LiveTvView.Radio) else loaded
                    }
                }
                .onFailure { error ->
                    Log.w(TAG, "Loading channels failed", error)
                    _state.update { it.copy(loading = false, failed = true) }
                }
        }
    }

    /** In the background: what is on screen stays if it fails. */
    private fun refresh() {
        viewModelScope.launch {
            runSuspendCatching { repository.channels() }
                .onSuccess { channels -> _state.update { it.withChannels(channels).copy(upcoming = emptyMap()) } }
                .onFailure { Log.w(TAG, "Refreshing channels failed", it) }
            _state.value.focused?.let(::loadUpcoming)
        }
    }

    fun setFavoritesOnly(on: Boolean) = _state.update { it.copy(favoritesOnly = on) }

    /**
     * By number or by name, like the library's sort menu: picking the active sort again flips the
     * direction. The lists change at once, the guide and the player follow the saved choice.
     */
    fun setSort(sort: ChannelSort) {
        val descending = sort == _state.value.sort && !_state.value.descending
        _state.update { it.copy(sort = sort, descending = descending).withChannels(sortedChannels(it.all, sort, descending)) }
        viewModelScope.launch {
            runSuspendCatching { repository.setSort(sort, descending) }.onFailure { Log.w(TAG, "Saving the channel order failed", it) }
        }
    }

    fun setView(view: LiveTvView) {
        _state.update { it.copy(view = view) }
        if (view == LiveTvView.Channels) schedulePreview() else stopPreview()
        if (view == LiveTvView.Recordings) loadRecordings()
    }

    fun loadRecordings() {
        viewModelScope.launch {
            _state.update { it.copy(recordingsLoading = true, recordingsFailed = false) }
            try {
                val recordings = repository.recordings()
                _state.update { it.copy(recordingsLoading = false, recordings = recordings) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Loading the recordings failed", e)
                _state.update { it.copy(recordingsLoading = false, recordingsFailed = true) }
            }
        }
    }

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

    /** OK on a guide programme: watch it when it runs, else offer to record it. */
    fun openProgram(channel: LiveChannel, program: LiveProgram?) {
        val now = System.currentTimeMillis()
        if (program == null || program.startMs <= now) {
            play(channel)
        } else {
            _state.update { it.copy(sheet = LiveTvSheet.Program(channel, program, more = false)) }
        }
    }

    /** Held OK (or the menu key) on a guide programme: everything there is to do with it. */
    fun programMenu(channel: LiveChannel, program: LiveProgram?) {
        if (program == null || program.endMs <= System.currentTimeMillis()) {
            toggleFavorite(channel)
        } else {
            _state.update { it.copy(sheet = LiveTvSheet.Program(channel, program, more = true)) }
        }
    }

    /** Held OK on a channel or station of the list. */
    fun channelMenu(channel: LiveChannel) = _state.update { it.copy(sheet = LiveTvSheet.Channel(channel)) }

    /** Moves [channel] to the radio stations, or back to the TV channels; the lists follow [LiveTvRepository.radioMarks]. */
    fun moveChannel(channel: LiveChannel) {
        closeSheet()
        val toRadio = !channel.radio
        viewModelScope.launch {
            repository.moveChannel(channel.id, toRadio)
            _events.send(UiEvent.Toast(if (toRadio) R.string.livetv_moved_radio else R.string.livetv_moved_tv, listOf(channel.name)))
        }
    }

    fun openRecording(recording: LiveRecording) = _state.update { it.copy(sheet = LiveTvSheet.Recording(recording)) }

    fun openTimer(timer: LiveTimer) = _state.update { it.copy(sheet = LiveTvSheet.Timer(timer)) }

    fun openSeriesTimer(timer: LiveSeriesTimer) = _state.update { it.copy(sheet = LiveTvSheet.SeriesTimer(timer)) }

    fun askDelete(recording: LiveRecording) = _state.update { it.copy(sheet = LiveTvSheet.ConfirmDelete(recording)) }

    fun closeSheet() = _state.update { it.copy(sheet = null) }

    fun playRecording(recording: LiveRecording) {
        closeSheet()
        navigate(PlayerRoute(recording.id.toString()))
    }

    override fun record(program: LiveProgram) = changeTimers(R.string.livetv_record_added, program.title) { repository.record(program.id) }

    override fun recordSeries(program: LiveProgram) = changeTimers(R.string.livetv_series_added, program.title) { repository.recordSeries(program.id) }

    override fun cancelTimer(timerId: String, title: String) =
        changeTimers(R.string.livetv_record_removed, title, { it.copy(scheduled = it.scheduled.filterNot { timer -> timer.id == timerId }) }) {
            repository.cancelTimer(timerId)
        }

    override fun cancelSeriesTimer(seriesTimerId: String, title: String) =
        changeTimers(
            R.string.livetv_series_removed,
            title,
            {
                it.copy(
                    series = it.series.filterNot { series -> series.id == seriesTimerId },
                    scheduled = it.scheduled.filterNot { timer -> timer.seriesTimerId == seriesTimerId },
                )
            },
        ) { repository.cancelSeriesTimer(seriesTimerId) }

    /**
     * The recording leaves the list together with the sheet, so the focus moves on to its
     * neighbour instead of getting lost with it. When the server refuses, it comes back.
     */
    fun deleteRecording(recording: LiveRecording) {
        _state.update { state ->
            state.copy(
                sheet = null,
                recordings = state.recordings?.let { it.copy(recorded = it.recorded.filterNot { r -> r.id == recording.id }) },
            )
        }
        viewModelScope.launch {
            try {
                repository.deleteRecording(recording.id)
                _events.send(UiEvent.Toast(R.string.livetv_deleted, listOf(recording.title)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Deleting the recording failed", e)
                _events.send(UiEvent.Toast(R.string.livetv_delete_failed))
            }
            loadRecordings()
        }
    }

    /** Closes the sheet, makes the change on the server, then shows the guide and the recordings as the server has them now. */
    private fun changeTimers(
        done: Int,
        title: String,
        /** What the recordings list looks like after the change, shown at once (see [deleteRecording]). */
        expected: (LiveRecordings) -> LiveRecordings = { it },
        change: suspend () -> Unit,
    ) {
        _state.update { it.copy(sheet = null, recordings = it.recordings?.let(expected)) }
        viewModelScope.launch {
            try {
                change()
                _events.send(UiEvent.Toast(done, listOf(title)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Changing a recording failed", e)
                _events.send(UiEvent.Toast(R.string.livetv_record_failed))
            }
            reloadGuide()
            if (_state.value.recordings != null) loadRecordings()
        }
    }

    /** The loaded part of the guide again, in place, so the recording marks follow the server. */
    private suspend fun reloadGuide() {
        val state = _state.value
        val ids = state.guide.keys.toList()
        try {
            if (ids.isNotEmpty()) {
                val programs = repository.guide(ids, state.guideFromMs, state.guideToMs)
                _state.update { it.copy(guide = it.guide + ids.associateWith { id -> programs[id].orEmpty() }) }
            }
            val channels = repository.channels()
            _state.update { it.withChannels(channels).copy(upcoming = emptyMap()) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Reloading the guide failed", e)
        }
        _state.value.focused?.let(::loadUpcoming)
    }

    /** The panel follows the focus; what comes next loads after a short rest, not for every channel passed. */
    fun onFocus(channel: LiveChannel) {
        if (_state.value.focused == channel.id) return
        _state.update { it.copy(focused = channel.id) }
        loadUpcoming(channel.id)
        schedulePreview()
    }

    /**
     * Once the focus rests on a channel of the list for a moment, it plays in the panel without
     * sound. Not for every channel passed while scrolling: each preview takes a tuner and starts a stream.
     */
    fun schedulePreview() {
        // Handed to the player, which is about to open: nothing new until this screen comes back.
        if (handedOver != null) return
        previewJob?.cancel()
        val channelId = _state.value.focused ?: return stopPreview()
        // Back on the channel that plays already: it goes on.
        if (_state.value.preview != null && _state.value.previewChannel == channelId) return
        // The preview playing goes on until the next one starts, in case the focus comes back to it.
        previewJob = viewModelScope.launch {
            delay(PREVIEW_DELAY_MS)
            releasePreview()
            val state = _state.value
            if (state.view != LiveTvView.Channels || state.sheet != null || state.focused != channelId) return@launch
            try {
                val opened = playback.open(channelId, startMs = 0, audioIndex = null, subtitleIndex = null)
                previewSource = opened
                val created = GlacierPlayer.create(context, StreamRequest(opened.url, opened.isHls, opened.headers, startPositionMs = 0, live = true))
                previewPlayback = created
                created.player.volume = 0f
                created.player.addListener(previewListener)
                created.player.playWhenReady = true
                _state.update { it.copy(preview = created.player, previewChannel = channelId, previewShown = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Opening the preview of $channelId failed", e)
                previewSource = null
            }
        }
    }

    /** Back from the player: the channel it played goes on here as the preview. */
    fun onStart() {
        val stream = handoff.takeAny()?.takeIf(handoff::alive)
        val state = _state.value
        if (stream == null || state.view != LiveTvView.Channels || handedOver != null) {
            stream?.let(handoff::end)
            schedulePreview()
            return
        }
        stopPreview()
        previewSource = stream.source
        previewPlayback = stream.playback
        val player = stream.playback.player
        player.volume = 0f
        player.addListener(previewListener)
        _state.update { it.copy(preview = player, previewChannel = stream.channelId, previewShown = true) }
        Log.i(TAG, "Took back the stream of ${stream.channelId}")
        // The list lands on that channel in a moment, slower devices take a while for it. Only if
        // it does not, the focused one plays instead: landing would stop the stream just taken.
        previewJob = viewModelScope.launch {
            delay(LANDING_MS)
            if (_state.value.focused != stream.channelId) schedulePreview()
        }
    }

    /** Ends the preview and frees its tuner. */
    fun stopPreview() {
        previewJob?.cancel()
        previewJob = null
        releasePreview()
    }

    private fun releasePreview() {
        previewSource?.let { playback.reportStopped(it, 0) }
        previewSource = null
        previewPlayback = null
        val player = _state.value.preview ?: return
        // A stream handed to the player is the player's now; the panel only lets go of it.
        if (player !== handedOver) {
            player.removeListener(previewListener)
            player.release()
        }
        handedOver = null
        _state.update { it.copy(preview = null, previewChannel = null, previewShown = false) }
    }

    override fun onCleared() {
        stopPreview()
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
                // The panel says there is nothing instead of loading on.
                _state.update { it.copy(upcoming = it.upcoming + (channelId to emptyList())) }
            }
        }
    }

    fun play(channel: LiveChannel) {
        val favoritesOnly = _state.value.favoritesOnly && channel.favorite
        if (channel.radio) {
            stopPreview()
            // Started from the favorites, the radio switches only among them.
            val stations = _state.value.stations.let { all -> if (favoritesOnly) all.filter { it.favorite } else all }
            music.playRadio(stations, channel.id)
            navigate(MusicRoute())
            return
        }
        // Any other preview frees its tuner for the player.
        if (!handOverPreview(channel.id)) stopPreview()
        navigate(LiveTvPlayerRoute(channel.id.toString(), favoritesOnly = favoritesOnly))
    }

    /**
     * The preview of [channelId] already runs: the player takes it over instead of tuning again.
     * The panel keeps showing it until the player shows it: without a surface in between, the
     * decoder goes on and the picture does not stop.
     */
    private fun handOverPreview(channelId: UUID): Boolean {
        val created = previewPlayback ?: return false
        // Ended or failed meanwhile: the player tunes again.
        if (created.player.playbackState == Player.STATE_ENDED || created.player.playerError != null) return false
        val source = previewSource ?: return false
        if (_state.value.previewChannel != channelId) return false
        previewJob?.cancel()
        created.player.removeListener(previewListener)
        handoff.give(LiveStreamHandoff.Stream(channelId, created, source))
        handedOver = created.player
        previewPlayback = null
        previewSource = null
        return true
    }

    fun toggleFavorite(channel: LiveChannel) {
        closeSheet()
        // The sheet may hold an older copy of the channel.
        val favorite = !(_state.value.all.firstOrNull { it.id == channel.id } ?: channel).favorite
        setFavorite(channel.id, favorite)
        viewModelScope.launch {
            try {
                repository.setFavorite(channel.id, favorite)
                _events.send(UiEvent.Toast(if (favorite) R.string.livetv_favorite_added else R.string.livetv_favorite_removed, listOf(channel.name)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Changing the favorite failed", e)
                setFavorite(channel.id, !favorite)
                _events.send(UiEvent.Toast(R.string.livetv_favorite_failed))
            }
        }
    }

    private fun setFavorite(channelId: UUID, favorite: Boolean) {
        val change = { channel: LiveChannel -> if (channel.id == channelId) channel.copy(favorite = favorite) else channel }
        _state.update { state -> state.copy(channels = state.channels?.map(change), stations = state.stations.map(change)) }
    }

    fun onNav(target: NavTarget) {
        val event = sessions.navEvent(target, current = NavTarget.LiveTv) ?: return
        viewModelScope.launch { _events.send(event) }
    }

    private fun navigate(route: Any) {
        viewModelScope.launch { _events.send(UiEvent.Navigate(route)) }
    }

    private companion object {
        const val TAG = "LiveTv"
        const val CLOCK_TICK_MS = 30_000L
        const val UPCOMING_DELAY_MS = 350L
        const val UPCOMING_HOURS = 6L
        /** Like zapping in the player: passing channels quickly opens no stream for each. */
        const val PREVIEW_DELAY_MS = 450L
        /** How long the list may take to land on the channel back from the player. */
        const val LANDING_MS = 2_000L
    }
}

/** How far ahead the guide reaches. */
private const val GUIDE_SPAN_MS = 24 * 60 * 60_000L
