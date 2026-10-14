package io.github.glacier_jellyfin.androidtv.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierShapes
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.glacier_jellyfin.androidtv.core.designsystem.LocalAccent
import io.github.glacier_jellyfin.androidtv.core.designsystem.PillShape
import io.github.glacier_jellyfin.androidtv.music.MusicProgress
import io.github.glacier_jellyfin.androidtv.music.NowPlaying
import io.github.glacier_jellyfin.androidtv.player.formatTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlin.random.Random

/** No key for this long while music plays brings up the [NowPlayingSaver]. */
const val SAVER_IDLE_MS = 3 * 60_000L

/** The saver's block moves to a new place this often, so nothing burns in. */
private const val DRIFT_MS = 20_000L

/** How long the block takes to fade out before it moves, and to fade in after. */
private const val DRIFT_FADE_MS = 900

/** The block's least distance from the screen's edges. */
private const val SAVER_EDGE = 96

/**
 * Full screen while music plays and nobody touches the remote: the cover, the
 * song and a progress line on black, moving now and then. The caller hides it
 * on the next key (which does nothing else).
 */
@Composable
fun NowPlayingSaver(visible: Boolean, nowPlaying: NowPlaying?, progress: StateFlow<MusicProgress>) {
    AnimatedVisibility(visible = visible && nowPlaying != null, enter = fadeIn(tween(1200)), exit = fadeOut(tween(300))) {
        val playing = nowPlaying ?: return@AnimatedVisibility
        BoxWithConstraints(Modifier.fillMaxSize().background(GlacierColors.Void)) {
            val blockWidth = 1040
            val blockHeight = 320
            // The block keeps clear of the screen's edges, where TVs cut off or round the picture.
            val minX = SAVER_EDGE
            val minY = SAVER_EDGE
            val maxX = (maxWidth.value - blockWidth - SAVER_EDGE).toInt().coerceAtLeast(minX)
            val maxY = (maxHeight.value - blockHeight - SAVER_EDGE).toInt().coerceAtLeast(minY)
            var spot by remember { mutableStateOf(IntOffset((minX + maxX) / 2, (minY + maxY) / 2)) }
            val shown = remember { Animatable(1f) }
            LaunchedEffect(maxX, maxY) {
                while (true) {
                    delay(DRIFT_MS)
                    // It fades out, moves while unseen and fades in at the new place, instead of jumping.
                    shown.animateTo(0f, tween(DRIFT_FADE_MS))
                    spot = IntOffset(Random.nextInt(minX, maxX + 1), Random.nextInt(minY, maxY + 1))
                    shown.animateTo(1f, tween(DRIFT_FADE_MS))
                }
            }
            Row(
                Modifier
                    .offset { IntOffset(spot.x.dp.roundToPx(), spot.y.dp.roundToPx()) }
                    .graphicsLayer { alpha = shown.value }
                    .width(blockWidth.dp),
                horizontalArrangement = Arrangement.spacedBy(44.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val cover = Modifier.size(blockHeight.dp).clip(RoundedCornerShape(GlacierShapes.RadiusLg))
                if (playing.live) StationLogo(playing.largeCoverUrl, playing.title, cover, textSize = 64) else Artwork(playing.largeCoverUrl, cover)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(playing.title, style = GlacierText.display(46), color = GlacierColors.Ice, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    playing.subtitle?.let { Text(it, style = GlacierText.body(26, FontWeight.Medium), color = GlacierColors.Mist, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    playing.detail?.let { Text(it, style = GlacierText.body(22), color = GlacierColors.Mist.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    if (playing.live) return@Column
                    val position by progress.collectAsState()
                    val fraction = if (position.durationMs > 0) position.positionMs.toFloat() / position.durationMs else 0f
                    // As wide as the text, with the time played and the time left below it.
                    Box(Modifier.padding(top = 22.dp).fillMaxWidth().height(6.dp).clip(PillShape).background(GlacierColors.GlassFill2)) {
                        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().clip(PillShape).background(LocalAccent.current.main))
                    }
                    Row(Modifier.fillMaxWidth()) {
                        Text(formatTime(position.positionMs), style = GlacierText.mono(20), color = GlacierColors.Ice)
                        Spacer(Modifier.weight(1f))
                        Text("−" + formatTime(position.durationMs - position.positionMs), style = GlacierText.mono(20), color = GlacierColors.Mist)
                    }
                }
            }
        }
    }
}
