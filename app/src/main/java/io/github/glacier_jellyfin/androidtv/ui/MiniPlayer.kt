package io.github.glacier_jellyfin.androidtv.ui

import androidx.compose.animation.core.RepeatMode as AnimationRepeat
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierShapes
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.glacier_jellyfin.androidtv.core.designsystem.LocalAccent
import io.github.glacier_jellyfin.androidtv.core.designsystem.LocalReduceMotion
import io.github.glacier_jellyfin.androidtv.core.designsystem.PillShape
import io.github.glacier_jellyfin.androidtv.core.designsystem.focusScale
import io.github.glacier_jellyfin.androidtv.music.MusicProgress
import io.github.glacier_jellyfin.androidtv.music.NowPlaying
import kotlinx.coroutines.flow.StateFlow

/** The song that plays, for the [MiniPlayer]; null while no music is loaded. */
val LocalNowPlaying = staticCompositionLocalOf<NowPlaying?> { null }

/** Where the song is; read only by the [MiniPlayer], so the ticking redraws nothing else. */
val LocalMusicProgress = staticCompositionLocalOf<StateFlow<MusicProgress>?> { null }

/** Height of the [MiniPlayer]; toasts move below it while it shows. */
const val MINI_PLAYER_HEIGHT = 84

/** Whether a [MiniPlayer] is on screen; pages without the top navigation show none. */
object MiniPlayerOnScreen {
    var count by mutableIntStateOf(0)
        private set

    val shown: Boolean get() = count > 0

    internal fun enter() {
        count++
    }

    internal fun leave() {
        count--
    }
}

/**
 * The mini player at the top right, laid out like the toast: cover, title,
 * artist and a progress line along the bottom edge. OK opens the full player.
 */
@Composable
fun MiniPlayer(nowPlaying: NowPlaying, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val accent = LocalAccent.current.main
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(GlacierShapes.RadiusMd)
    val track = nowPlaying.track
    val label = stringResource(R.string.nav_now_playing, track.title)
    DisposableEffect(Unit) {
        MiniPlayerOnScreen.enter()
        onDispose { MiniPlayerOnScreen.leave() }
    }
    Box(
        modifier
            .semantics { contentDescription = label }
            .focusScale(focused)
            .width(420.dp)
            .height(MINI_PLAYER_HEIGHT.dp)
            .clip(shape)
            .background(GlacierColors.Deep)
            .border(if (focused) 2.dp else 1.dp, if (focused) accent else GlacierColors.GlassBorder2, shape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(track.coverUrl, Modifier.size(56.dp).clip(RoundedCornerShape(GlacierShapes.RadiusSm)))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    EqualizerBars(accent, moving = nowPlaying.playing)
                    Text(
                        stringResource(if (nowPlaying.playing) R.string.music_now_playing else R.string.mini_player_paused).uppercase(),
                        style = GlacierText.label(13, 0.06),
                        color = if (nowPlaying.playing) accent else GlacierColors.Mist,
                    )
                }
                Text(
                    track.title,
                    style = GlacierText.body(19, FontWeight.SemiBold),
                    color = if (focused) accent else GlacierColors.Ice,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                track.artist?.let {
                    Text(it, style = GlacierText.body(15), color = GlacierColors.Mist, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        LocalMusicProgress.current?.let { flow ->
            val progress by flow.collectAsState()
            val fraction = if (progress.durationMs > 0) progress.positionMs.toFloat() / progress.durationMs else 0f
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .height(3.dp)
                    .background(accent),
            )
        }
    }
}

/** Three bars bouncing out of step; still when paused or with reduced motion. */
@Composable
private fun EqualizerBars(color: Color, moving: Boolean) {
    val still = !moving || LocalReduceMotion.current
    val transition = rememberInfiniteTransition(label = "equalizer")
    Row(Modifier.height(13.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        listOf(520, 380, 640).forEachIndexed { i, period ->
            val level by transition.animateFloat(
                initialValue = 0.3f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(period), AnimationRepeat.Reverse),
                label = "bar$i",
            )
            Box(
                Modifier
                    .width(3.dp)
                    .fillMaxHeight(if (still) 0.3f + 0.3f * i else level)
                    .clip(PillShape)
                    .background(color),
            )
        }
    }
}
