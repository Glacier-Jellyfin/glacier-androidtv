package io.github.glacier_jellyfin.androidtv.livetv

import io.github.glacier_jellyfin.androidtv.core.data.playback.PlaybackRepository
import io.github.glacier_jellyfin.androidtv.core.data.playback.PlaybackSource
import io.github.glacier_jellyfin.androidtv.core.player.GlacierPlayback
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A channel stream that already runs, passed from the channel list's preview
 * to the player: the player shows it at once instead of tuning again.
 */
@Singleton
class LiveStreamHandoff @Inject constructor(
    private val playback: PlaybackRepository,
) {

    class Stream(val channelId: UUID, val playback: GlacierPlayback, val source: PlaybackSource)

    private var waiting: Stream? = null

    fun give(stream: Stream) {
        drop()
        waiting = stream
    }

    /** The stream of [channelId], if one waits; any other one is ended. */
    fun take(channelId: UUID): Stream? {
        val stream = waiting?.takeIf { it.channelId == channelId }
        if (stream != null) waiting = null else drop()
        return stream
    }

    /** Ends a stream nobody took, so its tuner is freed. */
    private fun drop() {
        val stream = waiting ?: return
        waiting = null
        playback.reportStopped(stream.source, 0)
        stream.playback.player.release()
    }
}
