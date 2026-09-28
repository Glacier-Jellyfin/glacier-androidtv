package io.github.glacier_jellyfin.androidtv.detail

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.Player
import io.github.glacier_jellyfin.androidtv.core.data.playback.PlaybackSource
import io.github.glacier_jellyfin.androidtv.core.player.GlacierPlayer

/**
 * A theme song quietly behind the page (design: `themeOn`), faded in.
 * It ends with the song, when the page is left or when the app goes to the
 * background; [onDone] then keeps it from starting again on return.
 */
@Composable
internal fun ThemeSong(source: PlaybackSource, onDone: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val done by rememberUpdatedState(onDone)
    val player = remember(source) {
        GlacierPlayer.createAudio(context, source.headers).apply {
            volume = 0f
            setMediaItem(GlacierPlayer.audioItem(source.itemId.toString(), source.url, source.isHls))
            prepare()
            play()
        }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) done()
            }
        }
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) done() }
        player.addListener(listener)
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            player.release()
            done()
        }
    }
    LaunchedEffect(player) {
        animate(0f, THEME_VOLUME, animationSpec = tween(FADE_IN_MS)) { value, _ -> player.volume = value }
    }
}

private const val THEME_VOLUME = 0.35f
private const val FADE_IN_MS = 2500
