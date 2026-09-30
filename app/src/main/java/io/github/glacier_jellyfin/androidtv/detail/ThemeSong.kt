package io.github.glacier_jellyfin.androidtv.detail

import android.content.Context
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import io.github.glacier_jellyfin.androidtv.core.data.playback.PlaybackSource
import io.github.glacier_jellyfin.androidtv.core.player.GlacierPlayer
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A theme song quietly behind the page (design: `themeOn`), faded in.
 *
 * [area] names the title the song belongs to: a movie, or the show for a show
 * and all of its episode pages. The song keeps playing while the user moves
 * between pages of the same area and ends with the song, when the area is left
 * or when the app goes to the background; it does not start again within the
 * same visit.
 */
@Composable
internal fun ThemeSong(area: String, source: PlaybackSource?) {
    val context = LocalContext.current
    // The activity, not the page: a page is stopped as soon as the next one opens.
    val lifecycle = (LocalActivity.current as? LifecycleOwner ?: LocalLifecycleOwner.current).lifecycle
    DisposableEffect(area) {
        ThemeSongs.enter(area)
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) ThemeSongs.finish() }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            ThemeSongs.leave(area)
        }
    }
    LaunchedEffect(area, source) {
        if (source != null) ThemeSongs.play(context.applicationContext, area, source)
    }
}

/** The one theme song of the app, outliving single pages of its area. */
private object ThemeSongs {
    private val scope = MainScope()
    private var area: String? = null
    private var holders = 0
    private var finished = false
    private var player: ExoPlayer? = null
    private var fade: Job? = null
    private var release: Job? = null

    fun enter(key: String) {
        release?.cancel()
        if (key != area) {
            stopPlayer()
            area = key
            holders = 0
            finished = false
        }
        holders++
    }

    /** The next page of the area is composed before the last one goes, the grace covers the gap otherwise. */
    fun leave(key: String) {
        if (key != area) return
        holders--
        if (holders > 0) return
        release = scope.launch {
            delay(LEAVE_GRACE_MS)
            stopPlayer()
            area = null
            finished = false
        }
    }

    fun play(context: Context, key: String, source: PlaybackSource) {
        if (key != area || finished || player != null) return
        val created = GlacierPlayer.createAudio(context, source.headers).apply {
            volume = 0f
            setMediaItem(GlacierPlayer.audioItem(source.itemId.toString(), source.url, source.isHls))
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) finish()
                }
            })
            prepare()
            play()
        }
        player = created
        fade = scope.launch {
            for (step in 1..FADE_STEPS) {
                delay(FADE_IN_MS / FADE_STEPS)
                created.volume = THEME_VOLUME * step / FADE_STEPS
            }
        }
    }

    fun finish() {
        stopPlayer()
        if (area != null) finished = true
    }

    /** Fades the song out quickly rather than cutting it off, then lets the player go. */
    private fun stopPlayer() {
        fade?.cancel()
        val stopping = player ?: return
        player = null
        scope.launch {
            val start = stopping.volume
            for (step in FADE_OUT_STEPS - 1 downTo 0) {
                delay(FADE_OUT_MS / FADE_OUT_STEPS)
                stopping.volume = start * step / FADE_OUT_STEPS
            }
            stopping.release()
        }
    }
}

private const val THEME_VOLUME = 0.35f
private const val FADE_IN_MS = 2500L
private const val FADE_STEPS = 50
private const val FADE_OUT_MS = 700L
private const val FADE_OUT_STEPS = 20
private const val LEAVE_GRACE_MS = 400L
