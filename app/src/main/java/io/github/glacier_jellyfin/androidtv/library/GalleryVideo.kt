package io.github.glacier_jellyfin.androidtv.library

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Icon
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierIcons
import io.github.glacier_jellyfin.androidtv.core.designsystem.LocalAccent
import io.github.glacier_jellyfin.androidtv.core.designsystem.PillShape
import io.github.glacier_jellyfin.androidtv.ui.Artwork
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

@OptIn(UnstableApi::class)
@Composable
internal fun GalleryVideo(
    thumbUrl: String?,
    player: Player?,
) {
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    Box(Modifier.fillMaxSize().clipToBounds().onSizeChanged { viewport = it }) {
        if (player == null) {
            Artwork(thumbUrl, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            Box(
                Modifier.align(Alignment.Center).size(96.dp).clip(PillShape).background(LocalAccent.current.main),
                contentAlignment = Alignment.Center,
            ) {
                Icon(GlacierIcons.Play, null, tint = GlacierColors.Void, modifier = Modifier.size(36.dp))
            }
        } else {
            key(player) { GalleryVideoPlayback(thumbUrl, player, viewport) }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun GalleryVideoPlayback(
    thumbUrl: String?,
    player: Player,
    viewport: IntSize,
) {
    var rendered by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(player.isPlaying) }
    var videoSize by remember { mutableStateOf(player.videoSize) }
    var surface by remember { mutableStateOf<SurfaceView?>(null) }
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    val aspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
    val sampleSize = backdropSampleSize(aspect, viewport)
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                rendered = true
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }

            override fun onVideoSizeChanged(size: VideoSize) {
                videoSize = size
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    LaunchedEffect(surface, rendered, playing, sampleSize) {
        val view = surface
        if (!rendered || view == null || sampleSize == null) return@LaunchedEffect
        val bitmap = Bitmap.createBitmap(sampleSize.width, sampleSize.height, Bitmap.Config.ARGB_8888)
        do {
            if (view.copyVideoFrame(bitmap)) {
                frame = withContext(Dispatchers.Default) { bitmap.blurredFrame().asImageBitmap() }
            }
            if (playing) delay(BACKDROP_FRAME_INTERVAL_MS)
        } while (playing && isActive)
    }
    if (!rendered) {
        Artwork(thumbUrl, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
    } else if (sampleSize != null) {
        frame?.let { BlurredMediaBackdrop(it) }
    }
    AndroidView(
        factory = { context ->
            PlayerView(context).apply {
                setEnableComposeSurfaceSyncWorkaround(true)
                useController = false
                artworkDisplayMode = PlayerView.ARTWORK_DISPLAY_MODE_OFF
                setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                isFocusable = false
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                surface = videoSurfaceView as? SurfaceView
            }
        },
        update = { it.player = player },
        onRelease = { it.player = null },
        modifier = Modifier.fillMaxSize(),
    )
}

private suspend fun SurfaceView.copyVideoFrame(bitmap: Bitmap): Boolean {
    if (!isAttachedToWindow) return false
    return suspendCancellableCoroutine { continuation ->
        // The surface can disappear when the user browses away during a copy.
        try {
            PixelCopy.request(
                this,
                bitmap,
                { continuation.resume(it == PixelCopy.SUCCESS) },
                Handler(Looper.getMainLooper()),
            )
        } catch (_: IllegalArgumentException) {
            continuation.resume(false)
        }
    }
}

private const val BACKDROP_FRAME_INTERVAL_MS = 100L
