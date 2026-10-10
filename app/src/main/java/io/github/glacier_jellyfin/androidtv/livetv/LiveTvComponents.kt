package io.github.glacier_jellyfin.androidtv.livetv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.data.media.LiveChannel
import io.github.glacier_jellyfin.androidtv.core.data.media.LiveProgram
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.glacier_jellyfin.androidtv.core.designsystem.LocalAccent
import io.github.glacier_jellyfin.androidtv.core.designsystem.PillShape
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val ClockFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** "14:30" in the device's time zone. */
fun clockText(timeMs: Long): String = Instant.ofEpochMilli(timeMs).atZone(ZoneId.systemDefault()).format(ClockFormat)

/** "14:30 – 15:15". */
fun timeRange(program: LiveProgram): String = clockText(program.startMs) + " – " + clockText(program.endMs)

/** "20 min left", rounded up so a programme in its last minute still has one. */
@Composable
fun minutesLeft(program: LiveProgram, nowMs: Long): String {
    val minutes = ((program.endMs - nowMs + 59_999) / 60_000).coerceAtLeast(1).toInt()
    return stringResource(R.string.livetv_minutes_left, minutes)
}

/**
 * The channel's logo on a glass tile; without one, its number (or the
 * first letters of its name) stands in.
 */
@Composable
fun ChannelLogo(channel: LiveChannel, modifier: Modifier = Modifier, textSize: Int = 24) {
    var failed by remember(channel.logoUrl) { mutableStateOf(false) }
    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(GlacierColors.GlassFill2),
        contentAlignment = Alignment.Center,
    ) {
        if (channel.logoUrl == null || failed) {
            Text(
                channel.number ?: channel.name.take(3).uppercase(),
                style = GlacierText.display(textSize),
                color = GlacierColors.Mist,
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
        } else {
            AsyncImage(
                model = channel.logoUrl,
                contentDescription = channel.name,
                contentScale = ContentScale.Fit,
                onError = { failed = true },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(10.dp),
            )
        }
    }
}

/** A thin bar for how much of a programme ran. */
@Composable
fun ProgramProgress(fraction: Float, modifier: Modifier = Modifier, height: Int = 4) {
    Box(
        modifier
            .height(height.dp)
            .clip(PillShape)
            .background(Color.White.copy(alpha = 0.14f)),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .clip(PillShape)
                .background(LocalAccent.current.main),
        )
    }
}

/** The red "Live" mark of the player and the panel. */
@Composable
fun LiveBadge(modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(LiveRed)
            .padding(horizontal = 10.dp, vertical = 3.dp),
    ) {
        Text(stringResource(R.string.livetv_live), style = GlacierText.label(15), color = Color.White)
    }
}

private val LiveRed = Color(0xFFE5484D)
