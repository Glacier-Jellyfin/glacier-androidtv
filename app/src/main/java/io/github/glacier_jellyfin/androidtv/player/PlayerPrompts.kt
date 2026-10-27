package io.github.glacier_jellyfin.androidtv.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.LongState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaItem
import io.github.glacier_jellyfin.androidtv.core.data.playback.MediaSegment
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierClickable
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierIcons
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierShapes
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.glacier_jellyfin.androidtv.core.designsystem.LocalAccent
import io.github.glacier_jellyfin.androidtv.core.designsystem.PillShape
import io.github.glacier_jellyfin.androidtv.ui.Artwork

/**
 * Milliseconds played since [sample] arrived, counted on frame by frame while
 * [playing], so prompts move smoothly between the ticks of the position.
 * Read it in the draw phase; reading it in composition recomposes every frame.
 */
@Composable
private fun rememberSinceSample(sample: Any, playing: Boolean): LongState {
    val elapsed = remember { mutableLongStateOf(0) }
    LaunchedEffect(sample, playing) {
        elapsed.longValue = 0
        if (!playing) return@LaunchedEffect
        val anchor = withFrameMillis { it }
        while (true) withFrameMillis { elapsed.longValue = it - anchor }
    }
    return elapsed
}

/**
 * "Skip intro" and friends (design: 68 high, bottom right). The ring empties
 * as the segment plays; inside it, the time left until it ends.

 */
@Composable
fun SkipButton(segment: MediaSegment, positionMs: Long, playing: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val accent = LocalAccent.current.main
    val elapsed = rememberSinceSample(positionMs, playing)
    // The label recomposes once a second; the ring reads the position while drawing.
    val remaining by remember(segment, positionMs) {
        derivedStateOf { (segment.endMs - positionMs - elapsed.longValue).coerceAtLeast(0) / 1000 * 1000 }
    }
    GlacierClickable(onClick = onClick, shape = PillShape, modifier = modifier) { focused ->
        val color = if (focused) GlacierColors.Void else GlacierColors.Ice
        Row(
            Modifier
                .height(68.dp)
                .clip(PillShape)
                .background(if (focused) accent else GlacierColors.GlassFill2)
                .border(2.dp, if (focused) accent else GlacierColors.GlassBorder2, PillShape)
                .padding(start = 32.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(GlacierIcons.SkipForward, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
                Text(stringResource(segment.kind.skipLabel()), style = GlacierText.body(22, FontWeight.SemiBold), color = color)
            }
            Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val played = ((positionMs + elapsed.longValue - segment.startMs).toFloat() / (segment.endMs - segment.startMs)).coerceIn(0f, 1f)
                    val stroke = 4.dp.toPx()
                    val inset = stroke / 2 + 1.5.dp.toPx()
                    val arcSize = Size(size.width - 2 * inset, size.height - 2 * inset)
                    val topLeft = Offset(inset, inset)
                    drawArc(color.copy(alpha = 0.22f), 0f, 360f, useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(stroke))
                    drawArc(
                        color,
                        startAngle = -90f,
                        sweepAngle = 360f * (1 - played),
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(stroke, cap = StrokeCap.Round),
                    )
                }
                val long = remaining >= 60_000
                Text(
                    if (long) formatTime(remaining) else (remaining / 1000).toString(),
                    style = GlacierText.body(if (long) 14 else 17, FontWeight.Bold),
                    color = color,
                )
            }
        }
    }
}

/** "Next episode in 23 s" with the episode and two actions (design: 520 wide, bottom right). */
@Composable
fun UpNextCard(
    next: MediaItem,
    countdown: UpNextCountdown,
    playing: Boolean,
    playFocus: FocusRequester,
    onPlay: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = LocalAccent.current.main
    val shape = RoundedCornerShape(GlacierShapes.RadiusLg)
    Column(
        modifier
            .width(520.dp)
            .clip(shape)
            // Dark base under the glass, as in the top navigation: on a bright
            // scene the accent label and the grey line kept too little contrast.
            .background(GlacierColors.Deep.copy(alpha = 0.72f))
            .background(GlacierColors.GlassFill2)
            .border(1.dp, GlacierColors.GlassBorder2, shape)
            .padding(26.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val elapsed = rememberSinceSample(countdown, playing)
            val remainingMs by remember(countdown) {
                derivedStateOf { (countdown.remainingMs - elapsed.longValue).coerceAtLeast(0) / 1000 * 1000 }
            }
            val seconds = remainingMs / 1000
            val remaining = if (seconds >= 60) formatTime(remainingMs) else "$seconds s"
            Text(
                stringResource(R.string.up_next_in, remaining).uppercase(),
                style = GlacierText.body(16, FontWeight.Bold).copy(letterSpacing = 0.09.em),
                color = accent,
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(PillShape)
                    .background(GlacierColors.GlassFill2)
                    .drawBehind {
                        // The lead time behind the fraction stays put, so the bar shrinks with the time left.
                        val left = (countdown.remainingMs - elapsed.longValue).coerceAtLeast(0)
                        val fraction = if (countdown.remainingMs > 0) countdown.fraction * left / countdown.remainingMs else countdown.fraction
                        val width = size.width * fraction.coerceIn(0f, 1f)
                        drawRoundRect(accent, size = size.copy(width = width), cornerRadius = CornerRadius(size.height / 2))
                    },
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Artwork(
                next.thumbUrl,
                Modifier
                    .size(180.dp, 101.dp)
                    .clip(RoundedCornerShape(GlacierShapes.RadiusSm)),
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val season = next.seasonNumber
                val episode = next.episodeNumber
                if (season != null && episode != null) {
                    Text(stringResource(R.string.up_next_episode, season, episode), style = GlacierText.body(17), color = GlacierColors.Mist)
                }
                Text(
                    next.title,
                    style = GlacierText.body(22, FontWeight.SemiBold),
                    color = GlacierColors.Ice,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CardButton(stringResource(R.string.up_next_play), onClick = onPlay, primary = true, modifier = Modifier.focusRequester(playFocus))
            CardButton(stringResource(R.string.up_next_credits), onClick = onDismiss)
        }
    }
}

/** The card's buttons: smaller than [io.github.glacier_jellyfin.androidtv.core.designsystem.PillButton] (design: 56 high, 19 px). */
@Composable
private fun CardButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false) {
    val accent = LocalAccent.current
    GlacierClickable(onClick = onClick, shape = PillShape, modifier = modifier) { focused ->
        Box(
            Modifier
                .height(56.dp)
                .clip(PillShape)
                .background(if (focused) accent.main else if (primary) accent.deep else Color.Transparent)
                .border(2.dp, if (focused) accent.main else GlacierColors.GlassBorder2, PillShape)
                .padding(horizontal = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text,
                style = GlacierText.body(19, FontWeight.SemiBold),
                color = if (focused || primary) GlacierColors.Void else GlacierColors.Ice,
                maxLines = 1,
            )
        }
    }
}
