package io.github.glacier_jellyfin.androidtv.livetv

import android.os.Handler
import android.os.Looper
import androidx.media3.common.Player
import io.github.glacier_jellyfin.androidtv.core.log.Log
import io.github.glacier_jellyfin.androidtv.core.data.playback.PlaybackRepository
import io.github.glacier_jellyfin.androidtv.core.data.playback.PlaybackSource
import io.github.glacier_jellyfin.androidtv.core.player.GlacierPlayback
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A channel stream that already runs, passed between the channel list's
 * preview and the player, both ways: the other side shows it at once
 * instead of tuning again.
 */
@Singleton
class LiveStreamHandoff @Inject constructor(
    private val playback: PlaybackRepository,
) {

    class Stream(val channelId: UUID, val playback: GlacierPlayback, val source: PlaybackSource)

    private var waiting: Stream? = null
    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable { waiting?.let(::end) }

    fun give(stream: Stream) {
        waiting?.let(::end)
        waiting = stream
        // Nobody came for it: its tuner is freed.
        handler.postDelayed(timeout, TIMEOUT_MS)
    }

    /** The stream of [channelId], if one waits; any other one is ended. */
    fun take(channelId: UUID): Stream? {
        val stream = waiting ?: return null
        if (stream.channelId != channelId) {
            end(stream)
            return null
        }
        return takeAny()
    }

    /** Whatever stream waits. */
    fun takeAny(): Stream? {
        handler.removeCallbacks(timeout)
        return waiting.also { waiting = null }
    }

    /**
     * Whether [stream] still plays. One the server ended or that failed is ended here,
     * and the taker tunes again instead of showing a stream that never moves.
     */
    fun alive(stream: Stream): Boolean {
        val player = stream.playback.player
        if (player.playbackState != Player.STATE_ENDED && player.playerError == null) return true
        Log.i(TAG, "The stream of ${stream.channelId} ended before it was taken")
        end(stream)
        return false
    }

    /** Ends a stream nobody wants, so its tuner is freed. */
    fun end(stream: Stream) {
        if (waiting === stream) takeAny()
        playback.reportStopped(stream.source, 0)
        stream.playback.player.release()
    }

    private companion object {
        const val TAG = "LiveStreamHandoff"
        const val TIMEOUT_MS = 5_000L
    }
}
