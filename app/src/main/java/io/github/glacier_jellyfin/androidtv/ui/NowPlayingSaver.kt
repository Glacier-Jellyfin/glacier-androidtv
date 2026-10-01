package io.github.glacier_jellyfin.androidtv.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlin.random.Random

/** No key for this long while music plays brings up the [NowPlayingSaver]. */
const val SAVER_IDLE_MS = 3 * 60_000L

/** The saver's block moves to a new place this often, so nothing burns in. */
private const val DRIFT_MS = 20_000L

/**
 * Full screen while music plays and nobody touches the remote: the cover, the
 * song and a progress line on black, moving now and then. The caller hides it
 * on the next key (which does nothing else).
 */
@Composable
fun NowPlayingSaver(visible: Boolean, nowPlaying: NowPlaying?, progress: StateFlow<MusicProgress>) {
    AnimatedVisibility(visible = visible && nowPlaying != null, enter = fadeIn(tween(1200)), exit = fadeOut(tween(300))) {
        val track = nowPlaying?.track ?: return@AnimatedVisibility
        BoxWithConstraints(Modifier.fillMaxSize().background(GlacierColors.Void)) {
            val blockWidth = 1040
            val blockHeight = 320
            val maxX = (maxWidth.value - blockWidth).coerceAtLeast(0f).toInt()
            val maxY = (maxHeight.value - blockHeight).coerceAtLeast(0f).toInt()
            var spot by remember { mutableStateOf(IntOffset(maxX / 2, maxY / 2)) }
            LaunchedEffect(maxX, maxY) {
                while (true) {
                    delay(DRIFT_MS)
                    spot = IntOffset(Random.nextInt(maxX + 1), Random.nextInt(maxY + 1))
                }
            }
            Row(
                Modifier
                    .offset { IntOffset(spot.x.dp.roundToPx(), spot.y.dp.roundToPx()) }
                    .width(blockWidth.dp),
                horizontalArrangement = Arrangement.spacedBy(44.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Artwork(track.largeCoverUrl ?: track.coverUrl, Modifier.size(blockHeight.dp).clip(RoundedCornerShape(GlacierShapes.RadiusLg)))
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(track.title, style = GlacierText.display(46), color = GlacierColors.Ice, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    track.artist?.let { Text(it, style = GlacierText.body(26, FontWeight.Medium), color = GlacierColors.Mist, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    track.album?.let { Text(it, style = GlacierText.body(22), color = GlacierColors.Mist.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    val position by progress.collectAsState()
                    val fraction = if (position.durationMs > 0) position.positionMs.toFloat() / position.durationMs else 0f
                    Box(Modifier.padding(top = 18.dp).width(520.dp).height(4.dp).clip(PillShape).background(GlacierColors.GlassFill2)) {
                        Box(Modifier.width((520 * fraction.coerceIn(0f, 1f)).dp).height(4.dp).clip(PillShape).background(LocalAccent.current.main))
                    }
                }
            }
        }
    }
}
