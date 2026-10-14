package io.github.glacier_jellyfin.androidtv.livetv

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.data.media.LiveChannel
import io.github.glacier_jellyfin.androidtv.core.data.media.LiveProgram
import io.github.glacier_jellyfin.androidtv.core.data.media.LiveTvRepository
import io.github.glacier_jellyfin.androidtv.core.data.media.channelAfter
import io.github.glacier_jellyfin.androidtv.core.data.media.channelByNumber
import io.github.glacier_jellyfin.androidtv.core.data.media.nowAndNext
import io.github.glacier_jellyfin.androidtv.core.data.playback.PlaybackPosition
import io.github.glacier_jellyfin.androidtv.core.data.playback.PlaybackRepository
import io.github.glacier_jellyfin.androidtv.core.data.playback.PlaybackSource
import io.github.glacier_jellyfin.androidtv.core.data.settings.SettingsRepository
import io.github.glacier_jellyfin.androidtv.core.log.Log
import io.github.glacier_jellyfin.androidtv.core.player.GlacierPlayback
import io.github.glacier_jellyfin.androidtv.core.player.GlacierPlayer
import io.github.glacier_jellyfin.androidtv.core.player.StreamRequest
import io.github.glacier_jellyfin.androidtv.music.MusicController
import io.github.glacier_jellyfin.androidtv.navigation.LiveTvPlayerRoute
import io.github.glacier_jellyfin.androidtv.navigation.MusicRoute
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

data class LiveTvPlayerState(
    /** The channel tuned to, or about to be while zapping. */
    val channel: LiveChannel? = null,
    /** Every channel, for the number keys. */
    val channels: List<LiveChannel> = emptyList(),
    /** The channels zapping and the channel list go through: only the favorites when started from them. */
    val zapChannels: List<LiveChannel> = emptyList(),
    val now: LiveProgram? = null,
    val next: LiveProgram? = null,
    /** The channel's programmes from now on, for the programme list. */
    val programs: List<LiveProgram> = emptyList(),
    /** The programmes of the channel arrived (or could not be loaded); until then a loader stands in for them. */
    val guideLoaded: Boolean = false,
    val player: ExoPlayer? = null,
    val playback: GlacierPlayback? = null,
    /** Until the first frame of the channel can be shown. */
    val loading: Boolean = true,
    val failed: Boolean = false,
    val playing: Boolean = false,
    /** Paused by the user; the picture stands still. */
    val paused: Boolean = false,
    /** What the server kept of the channel since it was tuned; null while it cannot be wound back. */
    val timeshift: Timeshift? = null,
    /** Remote Left/Right in the time bar (Settings › Playback). */
    val seekBackMs: Long = 10_000,
    val seekForwardMs: Long = 30_000,
    val nowMs: Long = System.currentTimeMillis(),
)

/**
 * The stretch of a channel the server keeps since tuning in (an HLS event
 * playlist): from 0 to [liveMs], the live edge, with the picture at [positionMs].
 */
data class Timeshift(val liveMs: Long, val positionMs: Long) {
    val behindMs: Long get() = (liveMs - positionMs).coerceAtLeast(0)
    /** Close enough to the edge to count as live. */
    val live: Boolean get() = behindMs < LIVE_SLACK_MS

    private companion object {
        const val LIVE_SLACK_MS = 8_000L
    }
}

@HiltViewModel
class LiveTvPlayerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @param:ApplicationContext private val context: Context,
    private val repository: LiveTvRepository,
    private val playback: PlaybackRepository,
    private val handoff: LiveStreamHandoff,
    private val music: MusicController,
    settings: SettingsRepository,
) : ViewModel(), RecordActions {

    private val route = savedStateHandle.toRoute<LiveTvPlayerRoute>()

    private val _state = MutableStateFlow(LiveTvPlayerState())
    val state: StateFlow<LiveTvPlayerState> = _state.asStateFlow()

    /** Fires when the screen should close. */
    private val _finished = Channel<Unit>(Channel.CONFLATED)
    val finished = _finished.receiveAsFlow()

    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var source: PlaybackSource? = null
    private var started = false
    private var closing = false
    private var opening: Job? = null
    private var guide: Job? = null
    /** The channel watched before this one, for the remote's "last channel" key. */
    private var previous: UUID? = null
    /** The channel tuned to; known before the channel list is. */
    private var tuned: UUID? = null
    private var radioCheck: Job? = null
    /** The stream handed back to the channel list on leaving; it is the list's now. */
    private var handedBack: Player? = null
    /** The app went to the background and freed the tuner; coming back tunes again. */
    private var backgrounded = false
    /** When playback last failed and was tuned again by itself. */
    private var lastAutoRetry = 0L

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                _state.update { it.copy(loading = false) }
                if (!started) {
                    started = true
                    source?.let { playback.reportStart(it, position()) }
                    checkForRadio()
                    logTimeshift()
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.update { it.copy(playing = isPlaying) }
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            refreshTimeshift()
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            refreshTimeshift()
        }

        override fun onPlayerError(error: PlaybackException) {
            // A live stream that fell behind the window starts again at the live edge.
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                Log.i(TAG, "Fell behind the live window, back to live")
                _state.value.player?.let {
                    it.seekToDefaultPosition()
                    it.prepare()
                }
                return
            }
            // A live stream can stall for a moment (tuner, network): tune again once before giving up.
            val now = System.currentTimeMillis()
            if (now - lastAutoRetry > AUTO_RETRY_WINDOW_MS) {
                lastAutoRetry = now
                Log.w(TAG, "Live TV playback failed, tuning again", error)
                retry()
                return
            }
            Log.w(TAG, "Live TV playback failed", error)
            source?.let { playback.reportStopped(it, 0, failed = true) }
            source = null
            _state.update { it.copy(failed = true, loading = false) }
        }
    }

    init {
        // Live TV ends the music, like a video.
        music.pause()
        val channelId = UUID.fromString(route.channelId)
        viewModelScope.launch {
            try {
                // Radio plays in the music player: zapping and numbers stay among the TV channels.
                val channels = repository.channels().filterNot { it.radio }
                val channel = channels.firstOrNull { it.id == channelId }
                // Only the favorites, when started from them and there are some.
                val zapChannels = if (route.favoritesOnly) channels.filter { it.favorite }.ifEmpty { channels } else channels
                _state.update {
                    it.copy(
                        channels = channels,
                        zapChannels = zapChannels,
                        channel = channels.firstOrNull { c -> c.id == tuned } ?: channel ?: it.channel,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Zapping needs the list; the channel itself still plays without it.
                Log.w(TAG, "Loading channels failed", e)
            }
        }
        viewModelScope.launch {
            settings.settings.collect { profile ->
                _state.update { it.copy(seekBackMs = profile.playback.seekBack.ms, seekForwardMs = profile.playback.seekForward.ms) }
            }
        }
        tune(channelId, delayMs = 0)
        viewModelScope.launch {
            var sinceReport = 0L
            while (isActive) {
                delay(TICK_MS)
                val now = System.currentTimeMillis()
                _state.update { it.copy(nowMs = now) }
                refreshTimeshift()
                // The programme ended: the guide moves on.
                val current = _state.value.now
                if (current != null && current.endMs <= now) tuned?.let(::loadGuide)
                sinceReport += TICK_MS
                if (sinceReport >= REPORT_INTERVAL_MS && started) {
                    sinceReport = 0
                    source?.let { playback.reportProgress(it, position()) }
                }
            }
        }
    }

    /** Channel up ([by] 1) or down (-1), wrapping at either end. */
    fun zap(by: Int) {
        val current = tuned ?: return
        val target = channelAfter(_state.value.zapChannels, current, by) ?: return
        if (target.id != current) tune(target.id)
    }

    /** A channel picked by its number on the remote; false when there is none. */
    fun tuneNumber(number: String): Boolean {
        val target = channelByNumber(_state.value.channels, number) ?: return false
        if (target.id != tuned) tune(target.id)
        return true
    }

    fun tune(channel: LiveChannel) {
        if (channel.id != tuned) tune(channel.id)
    }

    /** Back to the channel watched before. */
    fun lastChannel() {
        previous?.let { tune(it) }
    }

    fun retry() {
        tuned?.let { tune(it, delayMs = 0, force = true) }
    }

    /**
     * Shows [channelId] at once and opens its stream after a short rest, so
     * zapping through several channels does not open a tuner stream for each.
     */
    private fun tune(channelId: UUID, delayMs: Long = ZAP_DELAY_MS, force: Boolean = false) {
        val current = tuned
        if (!force && current != null && current != channelId) previous = current
        tuned = channelId
        repository.watched(channelId)
        val channel = _state.value.channels.firstOrNull { it.id == channelId }
        _state.update {
            it.copy(
                channel = channel ?: it.channel?.takeIf { c -> c.id == channelId },
                now = channel?.now,
                next = null,
                programs = emptyList(),
                guideLoaded = false,
                loading = true,
                failed = false,
                timeshift = null,
            )
        }
        opening?.cancel()
        opening = viewModelScope.launch {
            delay(delayMs)
            stopStream()
            loadGuide(channelId)
            // The channel list's preview of this channel runs already: it goes on, with sound.
            val handed = if (force) null else handoff.take(channelId)
            if (handed != null) {
                source = handed.source
                val player = handed.playback.player
                player.volume = 1f
                player.addListener(listener)
                player.playWhenReady = true
                Log.i(TAG, "Took over the preview of $channelId")
                _state.update { it.copy(player = player, playback = handed.playback, paused = false) }
                // Ready before the listener came: what it does then happens now.
                if (player.playbackState == Player.STATE_READY) listener.onPlaybackStateChanged(Player.STATE_READY)
                return@launch
            }
            try {
                val opened = playback.open(channelId, startMs = 0, audioIndex = null, subtitleIndex = null)
                source = opened
                Log.i(TAG, "Tuned to $channelId: ${opened.method}, hls ${opened.isHls}" + if (opened.live) "" else " (no live stream id)")
                val created = GlacierPlayer.create(context, StreamRequest(opened.url, opened.isHls, opened.headers, startPositionMs = 0, live = true))
                created.player.addListener(listener)
                created.player.playWhenReady = true
                _state.update { it.copy(player = created.player, playback = created, paused = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Tuning to $channelId failed", e)
                _state.update { it.copy(failed = true, loading = false) }
            }
        }
    }

    /**
     * Jellyfin's own tuners call radio stations TV channels too. One that
     * plays with sound and no picture is radio: it moves to the radio
     * stations, and when the player was opened on it, the music player
     * takes over. Checked a moment after the start, once the tracks settled.
     */
    private fun checkForRadio() {
        val channelId = tuned ?: return
        radioCheck?.cancel()
        radioCheck = viewModelScope.launch {
            delay(RADIO_CHECK_MS)
            val player = _state.value.player ?: return@launch
            if (tuned != channelId || closing || !player.isPlaying) return@launch
            val tracks = player.currentTracks
            if (!tracks.containsType(C.TRACK_TYPE_AUDIO) || tracks.containsType(C.TRACK_TYPE_VIDEO)) return@launch
            Log.i(TAG, "$channelId plays without a picture: radio")
            try {
                repository.heardAsRadio(channelId)
                // While zapping it only moves for next time; the zapping goes on.
                if (channelId.toString() != route.channelId || previous != null) return@launch
                val stations = repository.channels().filter { it.radio }
                    .let { all -> if (route.favoritesOnly) all.filter { it.favorite }.ifEmpty { all } else all }
                if (stations.none { it.id == channelId } || tuned != channelId || closing) return@launch
                closing = true
                opening?.cancel()
                stopStream()
                music.playRadio(stations, channelId)
                _events.send(UiEvent.Navigate(MusicRoute(), replace = true))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Moving $channelId to radio failed", e)
            }
        }
    }

    private fun loadGuide(channelId: UUID) {
        guide?.cancel()
        guide = viewModelScope.launch {
            try {
                val programs = repository.programs(channelId, hours = GUIDE_HOURS)
                val (now, next) = nowAndNext(programs, System.currentTimeMillis())
                if (tuned == channelId) _state.update { it.copy(now = now, next = next, programs = programs.sortedBy { p -> p.startMs }, guideLoaded = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Loading the guide of $channelId failed", e)
                if (tuned == channelId) _state.update { it.copy(guideLoaded = true) }
            }
        }
    }

    override fun record(program: LiveProgram) = changeTimers(R.string.livetv_record_added, program.title) { repository.record(program.id) }

    override fun recordSeries(program: LiveProgram) = changeTimers(R.string.livetv_series_added, program.title) { repository.recordSeries(program.id) }

    override fun cancelTimer(timerId: String, title: String) = changeTimers(R.string.livetv_record_removed, title) { repository.cancelTimer(timerId) }

    override fun cancelSeriesTimer(seriesTimerId: String, title: String) =
        changeTimers(R.string.livetv_series_removed, title) { repository.cancelSeriesTimer(seriesTimerId) }

    /** Makes the change on the server, says so, and shows the programmes with their marks as they are now. */
    private fun changeTimers(done: Int, title: String, change: suspend () -> Unit) {
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
            tuned?.let(::loadGuide)
        }
    }

    /** A pause goes on where it stopped: the server keeps recording the channel meanwhile. */
    fun togglePlay() {
        val player = _state.value.player ?: return
        if (player.playWhenReady) {
            player.pause()
            _state.update { it.copy(paused = true) }
        } else {
            player.play()
            _state.update { it.copy(paused = false) }
        }
    }

    /** Back or forward in what the server kept, never past the live edge. */
    fun seekTo(positionMs: Long) {
        val player = _state.value.player ?: return
        val shift = _state.value.timeshift ?: return
        val target = positionMs.coerceIn(0, shift.liveMs)
        if (shift.liveMs - target < LIVE_SNAP_MS) player.seekToDefaultPosition() else player.seekTo(target)
        refreshTimeshift()
    }

    /** Straight back to now, playing. */
    fun goLive() {
        val player = _state.value.player ?: return
        player.seekToDefaultPosition()
        player.play()
        _state.update { it.copy(paused = false) }
        refreshTimeshift()
    }

    private fun refreshTimeshift() {
        val player = _state.value.player
        val shift = player?.let(::timeshiftOf)
        if (shift != _state.value.timeshift) _state.update { it.copy(timeshift = shift) }
    }

    /** Says once per channel whether it can be wound back, and if not, what the stream lacks. */
    private fun logTimeshift() {
        val player = _state.value.player ?: return
        val timeline = player.currentTimeline
        val window = if (timeline.isEmpty) null else timeline.getWindow(player.currentMediaItemIndex, Timeline.Window())
        val verdict = if (timeshiftOf(player) != null) "available" else "not available"
        Log.i(
            TAG,
            "Timeshift $verdict: hls ${source?.isHls}, dynamic ${window?.isDynamic}, live ${window?.isLive}, " +
                "seekable ${window?.isSeekable}, live edge ${window?.defaultPositionMs} ms, window ${window?.durationMs} ms",
        )
    }

    private fun timeshiftOf(player: Player): Timeshift? {
        val timeline = player.currentTimeline
        if (timeline.isEmpty || !player.isCurrentMediaItemDynamic) return null
        val window = timeline.getWindow(player.currentMediaItemIndex, Timeline.Window())
        val liveMs = window.defaultPositionMs
        if (!window.isSeekable || liveMs <= 0) return null
        return Timeshift(liveMs, player.currentPosition.coerceIn(0, liveMs))
    }

    /** Leaving the app frees the tuner; nobody is watching. */
    fun onBackground() {
        if (closing) return
        backgrounded = true
        opening?.cancel()
        stopStream()
    }

    /** Back in the app: the channel plays on, from now. */
    fun onForeground() {
        // ON_START also arrives when the screen first shows, while the first tune is still under way.
        if (closing || !backgrounded) return
        backgrounded = false
        retry()
    }

    /** Back: report the stop (the server closes the tuner stream), then close. */
    fun stop() {
        if (closing) return
        closing = true
        opening?.cancel()
        radioCheck?.cancel()
        if (!handBack()) stopStream()
        _finished.trySend(Unit)
    }

    /**
     * Back to the channel list, the stream goes on there as its preview. The picture
     * stays here until the list shows it, so the decoder does not stop in between.
     */
    private fun handBack(): Boolean {
        if (!route.handBack) return false
        val channelId = tuned ?: return false
        val current = source ?: return false
        val handed = _state.value.playback ?: return false
        val player = handed.player
        if (_state.value.failed || !player.playWhenReady) return false
        player.removeListener(listener)
        player.volume = 0f
        // The list previews what runs now, not where the viewer wound back to.
        if (_state.value.timeshift?.live == false) player.seekToDefaultPosition()
        handoff.give(LiveStreamHandoff.Stream(channelId, handed, current))
        handedBack = player
        source = null
        return true
    }

    private fun stopStream() {
        source?.let { playback.reportStopped(it, 0) }
        source = null
        started = false
        _state.value.player?.takeIf { it !== handedBack }?.let {
            it.removeListener(listener)
            it.release()
        }
        _state.update { it.copy(player = null, playback = null, playing = false, timeshift = null) }
    }

    private fun position() = PlaybackPosition(0, paused = _state.value.player?.isPlaying != true)

    override fun onCleared() {
        stopStream()
    }

    private companion object {
        const val TAG = "LiveTvPlayer"
        const val TICK_MS = 1_000L
        const val REPORT_INTERVAL_MS = 10_000L
        /** Zapping rests this long on a channel before its stream opens. */
        const val ZAP_DELAY_MS = 450L
        /** A seek this close to the edge goes to the edge itself. */
        const val LIVE_SNAP_MS = 5_000L
        /** How far ahead the programme list reaches. */
        const val GUIDE_HOURS = 12L
        /** A second failure within this time shows the error instead of tuning again. */
        const val AUTO_RETRY_WINDOW_MS = 60_000L
        /** How long a channel plays before its tracks count: a picture may come a little after the sound. */
        const val RADIO_CHECK_MS = 2_000L
    }
}
