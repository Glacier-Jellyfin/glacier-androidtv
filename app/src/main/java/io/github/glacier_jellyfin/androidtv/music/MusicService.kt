package io.github.glacier_jellyfin.androidtv.music

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dagger.hilt.android.AndroidEntryPoint
import io.github.glacier_jellyfin.androidtv.MainActivity
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Keeps music going outside the player screen and the app: a media session on
 * [MusicController]'s player, so the system shows the song ("Now playing"),
 * the remote's media keys reach it from anywhere, and the process stays alive
 * as a foreground service while it plays. Started with the first song, gone
 * once the music stops.
 */
@AndroidEntryPoint
class MusicService : MediaSessionService() {

    @Inject lateinit var controller: MusicController

    private val scope = MainScope()
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            controller.player.collect { player ->
                val current = session
                when {
                    player == null -> {
                        current?.release()
                        session = null
                        stopSelf()
                    }
                    current == null -> session = MediaSession.Builder(this@MusicService, player)
                        .setSessionActivity(openApp())
                        .build()
                        .also(::addSession)
                    else -> current.player = player
                }
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        scope.cancel()
        // The player belongs to the controller; only the session ends here.
        session?.release()
        session = null
        super.onDestroy()
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
