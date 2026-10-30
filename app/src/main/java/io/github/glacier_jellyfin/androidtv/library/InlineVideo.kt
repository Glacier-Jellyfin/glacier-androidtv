package io.github.glacier_jellyfin.androidtv.library

import android.content.Context
import androidx.media3.common.Player
import io.github.glacier_jellyfin.androidtv.core.data.playback.PlaybackRepository
import io.github.glacier_jellyfin.androidtv.core.log.Log
import io.github.glacier_jellyfin.androidtv.core.player.GlacierPlayer
import io.github.glacier_jellyfin.androidtv.core.player.StreamRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

/** What [InlineVideo] plays: [player] once the stream of [id] is open, and how far it has come, 0 to 1. */
data class InlineVideoState(val id: UUID? = null, val player: Player? = null, val progress: Float = 0f)

/**
 * One home video playing inside a screen rather than the full player: the photo viewer, the
 * gallery's preview. Opens its stream, plays it and reports how far it has come; [onEnded] when it
 * plays to its end, also when it cannot be opened. Nothing is reported to the server: the full
 * player does that once OK hands the video over.
 */
class InlineVideo(
    private val context: Context,
    private val playback: PlaybackRepository,
    private val scope: CoroutineScope,
    private val onEnded: () -> Unit = {},
) {
    private val _state = MutableStateFlow(InlineVideoState())
    val state: StateFlow<InlineVideoState> = _state.asStateFlow()

    private var opening: Job? = null
    private var ticker: Job? = null

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) onEnded()
        }
    }

    /** Where it plays now, for the full player to go on from there; 0 before it plays. */
    val positionMs: Long get() = _state.value.player?.currentPosition ?: 0

    /**
     * Plays [id] from [startMs], after [delayMs] in which another [open] or [release] can still call it
     * off (a gallery row the focus only passes through).
     */
    fun open(id: UUID, startMs: Long = 0, delayMs: Long = 0) {
        if (_state.value.id == id && (_state.value.player != null || opening?.isActive == true)) return
        release()
        _state.update { it.copy(id = id) }
        opening = scope.launch {
            try {
                delay(delayMs)
                val source = playback.open(id, startMs = startMs, audioIndex = null, subtitleIndex = null)
                val player = GlacierPlayer.create(
                    context,
                    StreamRequest(url = source.url, isHls = source.isHls, headers = source.headers, startPositionMs = startMs),
                ).player
                player.addListener(listener)
                player.playWhenReady = true
                _state.update { it.copy(player = player, progress = 0f) }
                ticker = scope.launch {
                    while (isActive) {
                        val duration = player.duration.takeIf { it > 0 }
                        if (duration != null) _state.update { it.copy(progress = (player.currentPosition.toFloat() / duration).coerceIn(0f, 1f)) }
                        delay(TICK_MS)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Opening the video failed", e)
                onEnded()
            }
        }
    }

    fun togglePlay() {
        _state.value.player?.let { it.playWhenReady = !it.playWhenReady }
    }

    fun pause() {
        _state.value.player?.playWhenReady = false
    }

    fun release() {
        opening?.cancel()
        ticker?.cancel()
        _state.value.player?.let {
            it.removeListener(listener)
            it.release()
        }
        _state.value = InlineVideoState()
    }

    private companion object {
        const val TAG = "InlineVideo"
        const val TICK_MS = 250L
    }
}
