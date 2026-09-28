package io.github.glacier_jellyfin.androidtv.player

import android.content.Context
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.glacier_jellyfin.androidtv.core.data.media.TrackSelections
import io.github.glacier_jellyfin.androidtv.core.data.media.DetailRepository
import io.github.glacier_jellyfin.androidtv.core.data.media.ItemDetails
import io.github.glacier_jellyfin.androidtv.core.data.playback.PlaybackMethod
import io.github.glacier_jellyfin.androidtv.core.data.playback.PlaybackPosition
import io.github.glacier_jellyfin.androidtv.core.data.playback.PlaybackRepository
import io.github.glacier_jellyfin.androidtv.core.data.playback.PlaybackSource
import io.github.glacier_jellyfin.androidtv.core.player.GlacierPlayer
import io.github.glacier_jellyfin.androidtv.core.player.StreamRequest
import io.github.glacier_jellyfin.androidtv.navigation.PlayerRoute
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

data class PlayerUiState(
    val details: ItemDetails? = null,
    val player: ExoPlayer? = null,
    /** Until the first frame can be shown. */
    val loading: Boolean = true,
    val failed: Boolean = false,
    val method: PlaybackMethod? = null,
    val playing: Boolean = false,
)

/** Position data, kept apart from [PlayerUiState] so ticking only redraws the timeline. */
data class PlayerProgress(
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val bufferedMs: Long = 0,
)

@HiltViewModel
class PlayerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @param:ApplicationContext private val context: Context,
    private val details: DetailRepository,
    private val playback: PlaybackRepository,
    private val trackSelections: TrackSelections,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<PlayerRoute>()
    private val itemId = UUID.fromString(route.itemId)

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private val _progress = MutableStateFlow(PlayerProgress())
    val progress: StateFlow<PlayerProgress> = _progress.asStateFlow()

    /** Fires when playback is over and the screen should close. */
    private val _finished = Channel<Unit>(Channel.CONFLATED)
    val finished = _finished.receiveAsFlow()

    private var source: PlaybackSource? = null
    private var started = false
    private var ticker: Job? = null

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> {
                    _state.update { it.copy(loading = false) }
                    if (!started) {
                        started = true
                        source?.let { playback.reportStart(it, position()) }
                    }
                }
                Player.STATE_ENDED -> finish()
                else -> Unit
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.update { it.copy(playing = isPlaying) }
            if (started) source?.let { playback.reportProgress(it, position()) }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.w(TAG, "Playback failed", error)
            source?.let { playback.reportStopped(it, currentPositionMs(), failed = true) }
            _state.update { it.copy(failed = true, loading = false) }
        }
    }

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            releasePlayer()
            _state.update { it.copy(loading = true, failed = false) }
            try {
                val item = details.details(itemId)
                _state.update { it.copy(details = item) }
                val selection = trackSelections.get(itemId)
                val startMs = if (route.fromStart) 0 else item.item.resumePositionMs
                val opened = playback.open(itemId, startMs, selection?.audio, selection?.subtitle)
                source = opened
                val player = GlacierPlayer.create(context, StreamRequest(opened.url, opened.isHls, opened.headers, startMs))
                player.addListener(listener)
                player.playWhenReady = true
                _state.update { it.copy(player = player, method = opened.method) }
                startTicker()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Opening playback failed", e)
                _state.update { it.copy(failed = true, loading = false) }
            }
        }
    }

    fun togglePlay() {
        val player = _state.value.player ?: return
        if (player.isPlaying) player.pause() else player.play()
    }

    fun pause() {
        _state.value.player?.pause()
    }

    fun seekTo(positionMs: Long) {
        val player = _state.value.player ?: return
        player.seekTo(positionMs.coerceIn(0, player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE))
        _progress.update { it.copy(positionMs = player.currentPosition) }
    }

    fun seekBy(deltaMs: Long) = seekTo(currentPositionMs() + deltaMs)

    /** "Stop" and Back: report where playback ended, then close. */
    fun stop() = finish()

    private fun finish() {
        source?.let { playback.reportStopped(it, currentPositionMs()) }
        source = null
        _finished.trySend(Unit)
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = viewModelScope.launch {
            var sinceReport = 0L
            while (isActive) {
                val player = _state.value.player ?: break
                _progress.value = PlayerProgress(
                    positionMs = player.currentPosition,
                    durationMs = player.duration.coerceAtLeast(0),
                    bufferedMs = player.bufferedPosition,
                )
                sinceReport += TICK_MS
                if (sinceReport >= REPORT_INTERVAL_MS && player.isPlaying) {
                    sinceReport = 0
                    source?.let { playback.reportProgress(it, position()) }
                }
                delay(TICK_MS)
            }
        }
    }

    private fun currentPositionMs(): Long = _state.value.player?.currentPosition ?: _progress.value.positionMs

    private fun position() = PlaybackPosition(currentPositionMs(), paused = _state.value.player?.isPlaying != true)

    private fun releasePlayer() {
        ticker?.cancel()
        _state.value.player?.let {
            it.removeListener(listener)
            it.release()
        }
        _state.update { it.copy(player = null) }
        started = false
    }

    override fun onCleared() {
        // Leaving without Stop (Home button, process going away) still records the resume point.
        source?.let { playback.reportStopped(it, currentPositionMs()) }
        releasePlayer()
    }

    private companion object {
        const val TAG = "Player"
        const val TICK_MS = 500L
        const val REPORT_INTERVAL_MS = 10_000L
    }
}
