package io.github.glacier_jellyfin.androidtv.livetv

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.glacier_jellyfin.androidtv.core.data.media.LiveChannel
import io.github.glacier_jellyfin.androidtv.core.data.media.LiveProgram
import io.github.glacier_jellyfin.androidtv.core.data.media.LiveTvRepository
import io.github.glacier_jellyfin.androidtv.core.data.media.channelAfter
import io.github.glacier_jellyfin.androidtv.core.data.media.channelByNumber
import io.github.glacier_jellyfin.androidtv.core.data.media.nowAndNext
import io.github.glacier_jellyfin.androidtv.core.data.playback.PlaybackPosition
import io.github.glacier_jellyfin.androidtv.core.data.playback.PlaybackRepository
import io.github.glacier_jellyfin.androidtv.core.data.playback.PlaybackSource
import io.github.glacier_jellyfin.androidtv.core.log.Log
import io.github.glacier_jellyfin.androidtv.core.player.GlacierPlayback
import io.github.glacier_jellyfin.androidtv.core.player.GlacierPlayer
import io.github.glacier_jellyfin.androidtv.core.player.StreamRequest
import io.github.glacier_jellyfin.androidtv.music.MusicController
import io.github.glacier_jellyfin.androidtv.navigation.LiveTvPlayerRoute
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
    val channels: List<LiveChannel> = emptyList(),
    val now: LiveProgram? = null,
    val next: LiveProgram? = null,
    val player: ExoPlayer? = null,
    val playback: GlacierPlayback? = null,
    /** Until the first frame of the channel can be shown. */
    val loading: Boolean = true,
    val failed: Boolean = false,
    val playing: Boolean = false,
    /** Paused by the user; the picture stands still. */
    val paused: Boolean = false,
    val nowMs: Long = System.currentTimeMillis(),
)

@HiltViewModel
class LiveTvPlayerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @param:ApplicationContext private val context: Context,
    private val repository: LiveTvRepository,
    private val playback: PlaybackRepository,
    music: MusicController,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<LiveTvPlayerRoute>()

    private val _state = MutableStateFlow(LiveTvPlayerState())
    val state: StateFlow<LiveTvPlayerState> = _state.asStateFlow()

    /** Fires when the screen should close. */
    private val _finished = Channel<Unit>(Channel.CONFLATED)
    val finished = _finished.receiveAsFlow()

    private var source: PlaybackSource? = null
    private var started = false
    private var closing = false
    private var opening: Job? = null
    private var guide: Job? = null
    /** The channel watched before this one, for the remote's "last channel" key. */
    private var previous: UUID? = null
    /** The channel tuned to; known before the channel list is. */
    private var tuned: UUID? = null
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
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.update { it.copy(playing = isPlaying) }
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
                val channels = repository.channels()
                val channel = channels.firstOrNull { it.id == channelId }
                _state.update { it.copy(channels = channels, channel = channels.firstOrNull { c -> c.id == tuned } ?: channel ?: it.channel) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Zapping needs the list; the channel itself still plays without it.
                Log.w(TAG, "Loading channels failed", e)
            }
        }
        tune(channelId, delayMs = 0)
        viewModelScope.launch {
            var sinceReport = 0L
            while (isActive) {
                delay(TICK_MS)
                val now = System.currentTimeMillis()
                _state.update { it.copy(nowMs = now) }
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
        val target = channelAfter(_state.value.channels, current, by) ?: return
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
        val channel = _state.value.channels.firstOrNull { it.id == channelId }
        _state.update {
            it.copy(
                channel = channel ?: it.channel?.takeIf { c -> c.id == channelId },
                now = channel?.now,
                next = null,
                loading = true,
                failed = false,
            )
        }
        opening?.cancel()
        opening = viewModelScope.launch {
            delay(delayMs)
            stopStream()
            loadGuide(channelId)
            try {
                val opened = playback.open(channelId, startMs = 0, audioIndex = null, subtitleIndex = null)
                source = opened
                Log.i(TAG, "Tuned to $channelId: ${opened.method}" + if (opened.live) "" else " (no live stream id)")
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

    private fun loadGuide(channelId: UUID) {
        guide?.cancel()
        guide = viewModelScope.launch {
            try {
                val programs = repository.programs(channelId, hours = GUIDE_HOURS)
                val (now, next) = nowAndNext(programs, System.currentTimeMillis())
                if (tuned == channelId) _state.update { it.copy(now = now, next = next) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Loading the guide of $channelId failed", e)
            }
        }
    }

    fun togglePlay() {
        val player = _state.value.player ?: return
        if (player.playWhenReady) {
            player.pause()
            _state.update { it.copy(paused = true) }
        } else {
            // Live means live: after a pause it goes on from now, not from where it stopped.
            player.seekToDefaultPosition()
            player.play()
            _state.update { it.copy(paused = false) }
        }
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
        stopStream()
        _finished.trySend(Unit)
    }

    private fun stopStream() {
        source?.let { playback.reportStopped(it, 0) }
        source = null
        started = false
        _state.value.player?.let {
            it.removeListener(listener)
            it.release()
        }
        _state.update { it.copy(player = null, playback = null, playing = false) }
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
        const val GUIDE_HOURS = 6L
        /** A second failure within this time shows the error instead of tuning again. */
        const val AUTO_RETRY_WINDOW_MS = 60_000L
    }
}
