package io.github.glacier_jellyfin.androidtv.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import androidx.tv.material3.Text
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierBackground
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierShapes
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.glacier_jellyfin.androidtv.core.designsystem.LocalAccent
import io.github.glacier_jellyfin.androidtv.core.designsystem.LocalReduceMotion
import io.github.glacier_jellyfin.androidtv.core.designsystem.PillShape
import io.github.glacier_jellyfin.androidtv.music.MusicClock
import io.github.glacier_jellyfin.androidtv.music.MusicProgress
import io.github.glacier_jellyfin.androidtv.music.MusicUiState
import io.github.glacier_jellyfin.androidtv.music.NowPlaying
import io.github.glacier_jellyfin.androidtv.player.formatTime
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlinx.coroutines.flow.StateFlow

/**
 * Whether the [NowPlayingSaver] covers the screen. The mini player gives it the picture of a music
 * video meanwhile and takes it back afterwards.
 */
object SaverOnScreen {
    var shown by mutableStateOf(false)
        internal set
}

/** No key for this long while music plays brings up the [NowPlayingSaver]. */
const val SAVER_IDLE_MS = 3 * 60_000L

/** The cover in the middle, and the ones beside it for the songs before and after. */
private const val CENTER_COVER = 500
private const val SIDE_COVER = 360
private const val COVER_GAP = 64

/** A music video in the middle of the saver: 16:9 at the covers' height. */
private const val CENTER_VIDEO_WIDTH = CENTER_COVER * 16 / 9

/** Distance from the middle to the first cover beside it, and from there on between covers. */
private const val FIRST_STEP = CENTER_COVER / 2 + COVER_GAP + SIDE_COVER / 2
private const val NEXT_STEP = SIDE_COVER + COVER_GAP

/** How many songs show on each side; the outer ones run off the screen. */
private const val SIDE_COUNT = 3

/** One slow sway of the whole picture, so nothing stands still long enough to burn in. */
private const val SWAY_MS = 90_000

/**
 * Full screen while music plays and nobody touches the remote: the queue as a
 * row of covers with the song in the middle, its title and a progress line, on
 * the libraries' ground, with the time of day. The row slides on when the song changes and the
 * picture sways slowly instead of jumping. The caller hides it on the next key
 * (which does nothing else).
 */
@Composable
fun NowPlayingSaver(
    visible: Boolean,
    nowPlaying: NowPlaying?,
    music: StateFlow<MusicUiState>,
    progress: StateFlow<MusicProgress>,
) {
    AnimatedVisibility(
        visible = visible && nowPlaying != null,
        enter = fadeIn(tween(1200)),
        exit = fadeOut(tween(300)),
    ) {
        val playing = nowPlaying ?: return@AnimatedVisibility
        val state by music.collectAsState()
        DisposableEffect(Unit) {
            SaverOnScreen.shown = true
            onDispose { SaverOnScreen.shown = false }
        }
        // A music video plays on in the middle; the saver keeps its picture decoding, also where no mini player is.
        val controller = LocalMusicController.current
        if (controller != null) {
            LifecycleStartEffect(controller) {
                controller.miniPictureShown()
                onStopOrDispose { controller.miniPictureGone() }
            }
        }
        val player = controller?.player?.collectAsStateWithLifecycle()?.value
        // The same ground as the libraries: the accent's glow on the dark page.
        GlacierBackground {
            val reduceMotion = LocalReduceMotion.current
            val sway by rememberInfiniteTransition(label = "sway").animateFloat(
                initialValue = -1f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(SWAY_MS, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                label = "sway",
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        if (!reduceMotion) {
                            translationX = sway * 24.dp.toPx()
                            translationY = sway * 14.dp.toPx()
                        }
                    },
            ) {
                // Where the video player shows it.
                MusicClock(Modifier.align(Alignment.TopEnd).padding(end = 80.dp, top = 60.dp))
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (playing.live) {
                        StationLogo(
                            playing.largeCoverUrl,
                            playing.title,
                            Modifier.coverShape(CENTER_COVER),
                            textSize = 72,
                        )
                    } else {
                        CoverRow(state, playing, player)
                    }
                    SongText(playing)
                    if (!playing.live) SaverProgress(progress)
                }
            }
        }
    }
}

/**
 * The queue's covers around the current song; they slide over when it changes. A music video that
 * plays stands wide in the middle and shows its picture; the covers beside it move out to make room.
 */
@Composable
private fun CoverRow(
    state: MusicUiState,
    playing: NowPlaying,
    player: ExoPlayer?,
) {
    val queue = state.queue
    val place = remember { Animatable(state.index.toFloat()) }
    LaunchedEffect(state.index) {
        // A jump across the queue lands at once, a step to the song beside slides.
        if (abs(state.index - place.value) > 2f) {
            place.snapTo(state.index.toFloat())
        } else {
            place.animateTo(state.index.toFloat(), tween(900, easing = FastOutSlowInEasing))
        }
    }
    Box(Modifier.fillMaxWidth().height(CENTER_COVER.dp), contentAlignment = Alignment.Center) {
        if (queue.isEmpty()) {
            Artwork(playing.largeCoverUrl, Modifier.coverShape(CENTER_COVER))
            return@Box
        }
        val at = place.value
        val videoAt = state.index.takeIf { queue.getOrNull(it)?.track?.isVideo == true }
        // How wide the video in the middle is beyond a cover, fading as the row slides away from it.
        val videoNear = videoAt?.let { min(abs(it - at), 1f) } ?: 1f
        val extra = (CENTER_VIDEO_WIDTH - CENTER_COVER) / 2f * (1f - videoNear)
        val first = floor(at - SIDE_COUNT).toInt().coerceAtLeast(0)
        val last = ceil(at + SIDE_COUNT).toInt().coerceAtMost(queue.lastIndex)
        for (i in first..last) {
            val entry = queue[i]
            val from = i - at
            val distance = abs(from)
            val near = min(distance, 1f)
            val shift = if (distance <= 1f) distance * FIRST_STEP else FIRST_STEP + (distance - 1f) * NEXT_STEP
            // Everything but the video itself moves out by its extra width.
            val moved = shift + if (i == videoAt) 0f else extra
            key(entry.key) {
                val layer = Modifier.graphicsLayer {
                    val scale = 1f - near * (1f - SIDE_COVER.toFloat() / CENTER_COVER)
                    scaleX = scale
                    scaleY = scale
                    translationX = (if (from < 0) -moved else moved).dp.toPx()
                    alpha = 1f - near * 0.45f
                }
                if (i == videoAt) {
                    SaverVideo(
                        entry.track.largeCoverUrl ?: entry.track.coverUrl,
                        player,
                        CENTER_COVER + (CENTER_VIDEO_WIDTH - CENTER_COVER) * (1f - videoNear),
                        layer,
                    )
                } else {
                    Artwork(entry.track.largeCoverUrl ?: entry.track.coverUrl, layer.coverShape(CENTER_COVER))
                }
            }
        }
    }
}

/** The music video that plays, as wide as [width] (16:9 in the middle); its still until the first frame. */
@androidx.annotation.OptIn(UnstableApi::class) // ContentFrame
@Composable
private fun SaverVideo(stillUrl: String?, player: ExoPlayer?, width: Float, modifier: Modifier) {
    Box(
        modifier
            .width(width.dp)
            .height(CENTER_COVER.dp)
            .shadow(32.dp, RoundedCornerShape(GlacierShapes.RadiusLg))
            .clip(RoundedCornerShape(GlacierShapes.RadiusLg))
            .background(GlacierColors.Void),
    ) {
        if (player == null) {
            Artwork(stillUrl, Modifier.fillMaxSize())
        } else {
            ContentFrame(
                player = player,
                modifier = Modifier.fillMaxSize(),
                surfaceType = SURFACE_TYPE_TEXTURE_VIEW,
                contentScale = ContentScale.Crop,
                shutter = { Artwork(stillUrl, Modifier.fillMaxSize()) },
            )
        }
    }
}

private fun Modifier.coverShape(size: Int) = this
    .size(size.dp)
    .shadow(32.dp, RoundedCornerShape(GlacierShapes.RadiusLg))
    .clip(RoundedCornerShape(GlacierShapes.RadiusLg))
    // Solid, so the shadow does not show through a missing cover's see-through fill.
    .background(GlacierColors.Deep)

@Composable
private fun SongText(playing: NowPlaying) {
    Column(Modifier.width(1100.dp).padding(top = 44.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            playing.title,
            style = GlacierText.display(44),
            color = GlacierColors.Ice,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        playing.subtitle?.let {
            Text(
                it,
                style = GlacierText.body(26, FontWeight.Medium),
                color = GlacierColors.Mist,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/** Time played, the line and the song's length, in one row as wide as the covers' middle. */
@Composable
private fun SaverProgress(progress: StateFlow<MusicProgress>) {
    val position by progress.collectAsState()
    val fraction = if (position.durationMs > 0) position.positionMs.toFloat() / position.durationMs else 0f
    Row(
        Modifier.width(1100.dp).padding(top = 36.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            formatTime(position.positionMs),
            style = GlacierText.mono(20),
            color = GlacierColors.Ice,
            textAlign = TextAlign.End,
            modifier = Modifier.width(90.dp),
        )
        Box(Modifier.weight(1f).height(6.dp).clip(PillShape).background(GlacierColors.GlassFill2)) {
            Box(
                Modifier
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .clip(PillShape)
                    .background(LocalAccent.current.main),
            )
        }
        Text(
            formatTime(position.durationMs),
            style = GlacierText.mono(20),
            color = GlacierColors.Mist,
            modifier = Modifier.width(90.dp),
        )
    }
}
