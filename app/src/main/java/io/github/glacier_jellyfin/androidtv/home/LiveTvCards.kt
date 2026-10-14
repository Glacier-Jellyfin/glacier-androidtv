package io.github.glacier_jellyfin.androidtv.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import io.github.glacier_jellyfin.androidtv.core.data.media.LiveChannel
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierCard
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierShapes
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.glacier_jellyfin.androidtv.core.designsystem.LocalAccent
import io.github.glacier_jellyfin.androidtv.core.designsystem.focusFrame
import io.github.glacier_jellyfin.androidtv.livetv.ChannelLogo
import io.github.glacier_jellyfin.androidtv.livetv.minutesLeft
import io.github.glacier_jellyfin.androidtv.ui.Artwork
import io.github.glacier_jellyfin.androidtv.ui.LocalCardSizes
import io.github.glacier_jellyfin.androidtv.ui.MarqueeText
import io.github.glacier_jellyfin.androidtv.ui.ProgressBar
import kotlinx.coroutines.delay

/** The time for the progress bars of the Live TV rows; moves on every half minute. */
@Composable
fun rememberLiveClock(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(CLOCK_STEP_MS)
            now = System.currentTimeMillis()
        }
    }
    return now
}

/**
 * What runs on [channel] now: the programme's picture (the channel's logo when
 * the guide has none), how far it got, and the channel below its title.
 */
@Composable
fun OnNowCard(channel: LiveChannel, nowMs: Long, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val program = channel.now ?: return
    val accent = LocalAccent.current.main
    val shape = RoundedCornerShape(GlacierShapes.RadiusMd)
    val sizes = LocalCardSizes.current
    GlacierCard(onClick = onClick, modifier = modifier.width(sizes.continueWidth.dp)) { focused ->
        Box(
            Modifier
                .size(sizes.continueWidth.dp, sizes.continueHeight.dp)
                .focusFrame(focused, shape)
                .clip(shape),
        ) {
            if (program.imageUrl != null) {
                Artwork(program.imageUrl, Modifier.fillMaxSize())
            } else {
                Box(Modifier.fillMaxSize().background(GlacierColors.Deep), contentAlignment = Alignment.Center) {
                    ChannelLogo(channel, Modifier.size(150.dp, 84.dp))
                }
            }
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(70.dp)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xD905090F)))),
            )
            ProgressBar(
                progress = program.progressAt(nowMs),
                track = Color(0x42E8F4F7),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 16.dp, end = 16.dp, bottom = 20.dp)
                    .height(5.dp),
            )
        }
        Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            MarqueeText(program.title, style = GlacierText.body(19, FontWeight.SemiBold), color = if (focused) accent else GlacierColors.Ice, active = focused)
            Text(
                channel.name + " · " + minutesLeft(program, nowMs),
                style = GlacierText.body(16),
                color = GlacierColors.Mist,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A channel: its logo on a glass tile and its name. What runs on it is the other row's. */
@Composable
fun ChannelCard(channel: LiveChannel, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val accent = LocalAccent.current.main
    val shape = RoundedCornerShape(GlacierShapes.RadiusMd)
    val width = LocalCardSizes.current.posterWidth
    GlacierCard(onClick = onClick, modifier = modifier.width(width.dp)) { focused ->
        Box(
            Modifier
                .size(width.dp, (width * 9 / 16).dp)
                .focusFrame(focused, shape)
                .clip(shape),
        ) {
            ChannelLogo(channel, Modifier.fillMaxSize(), textSize = 30)
        }
        val name = listOfNotNull(channel.number, channel.name).joinToString(" ")
        MarqueeText(
            name,
            style = GlacierText.body(19, FontWeight.SemiBold),
            color = if (focused) accent else GlacierColors.Ice,
            active = focused,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

private const val CLOCK_STEP_MS = 30_000L
