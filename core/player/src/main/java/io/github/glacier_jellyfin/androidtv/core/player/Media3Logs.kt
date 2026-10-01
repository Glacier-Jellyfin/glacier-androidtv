package io.github.glacier_jellyfin.androidtv.core.player

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.util.EventLogger
import io.github.glacier_jellyfin.androidtv.core.log.Log

/** Media3's own log lines go through Glacier's [Log], so decoder and network errors reach the file log. */
object Media3Logs {

    @OptIn(UnstableApi::class)
    fun install() {
        androidx.media3.common.util.Log.setLogger(object : androidx.media3.common.util.Log.Logger {
            override fun d(tag: String, message: String, throwable: Throwable?) = Log.d(tag, message, throwable)
            override fun i(tag: String, message: String, throwable: Throwable?) = Log.i(tag, message, throwable)
            override fun w(tag: String, message: String, throwable: Throwable?) = Log.w(tag, message, throwable)
            override fun e(tag: String, message: String, throwable: Throwable?) = Log.e(tag, message, throwable)
        })
    }

    /** With detailed logging on, every player event (tracks, formats, buffering, dropped frames). */
    @OptIn(UnstableApi::class)
    internal fun attach(player: ExoPlayer) {
        if (Log.verbose) player.addAnalyticsListener(EventLogger())
    }
}
