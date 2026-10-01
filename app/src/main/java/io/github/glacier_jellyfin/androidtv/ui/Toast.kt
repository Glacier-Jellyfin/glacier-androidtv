package io.github.glacier_jellyfin.androidtv.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierShapes
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import kotlinx.coroutines.delay

/** Shows short notices; see [ToastHost]. */
class Toaster {
    internal var message by mutableStateOf<String?>(null)
    internal var sequence by mutableStateOf(0)

    fun show(text: String) {
        message = text
        sequence++
    }
}

val LocalToaster = staticCompositionLocalOf { Toaster() }

/**
 * The design's toast outside the player: a card at the top right that
 * disappears after 2.2 s, below the [MiniPlayer] while music is loaded. Only
 * the "notice" tone is used so far.
 */
@Composable
fun BoxScope.ToastHost(toaster: Toaster) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(toaster.sequence) {
        if (toaster.message == null) return@LaunchedEffect
        visible = true
        delay(2200)
        visible = false
    }
    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally { it / 10 } + fadeIn(),
        exit = fadeOut(),
        modifier = Modifier
            .align(Alignment.TopEnd)
            // Below the mini player while it shows (same corner).
            .padding(top = if (LocalNowPlaying.current != null) (34 + MINI_PLAYER_HEIGHT + 12).dp else 34.dp, end = 96.dp),
    ) {
        val shape = RoundedCornerShape(GlacierShapes.RadiusMd)
        Row(
            modifier = Modifier
                .width(440.dp)
                .clip(shape)
                .background(GlacierColors.Deep)
                .border(1.dp, GlacierColors.GlassBorder2, shape)
                .padding(start = 18.dp, top = 18.dp, end = 24.dp, bottom = 22.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(GlacierColors.Ice),
                contentAlignment = Alignment.Center,
            ) {
                Text("!", style = GlacierText.body(20, FontWeight.Bold), color = GlacierColors.Void)
            }
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(stringResource(R.string.toast_notice).uppercase(), style = GlacierText.label(15, 0.04), color = GlacierColors.Mist)
                Text(toaster.message.orEmpty(), style = GlacierText.body(20), color = GlacierColors.Ice)
            }
        }
    }
}
