package io.github.glacier_jellyfin.androidtv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierShapes

/**
 * Text over a picture (a video, a bright backdrop) is not legible on its own: the
 * picture is dimmed and the content sits on a dark card in the middle, the way
 * every player shows that it failed. Without [dim] the picture stays as it is
 * around the card, for a moment like loading.
 */
@Composable
fun OverlayCard(modifier: Modifier = Modifier, dim: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    Box(modifier.fillMaxSize().then(if (dim) Modifier.background(Dim) else Modifier), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .padding(horizontal = 80.dp)
                .background(Card, RoundedCornerShape(GlacierShapes.RadiusLg))
                .padding(48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(28.dp),
            content = content,
        )
    }
}

private val Dim = Color(0xB805090F)
private val Card = GlacierColors.Deep.copy(alpha = 0.92f)
