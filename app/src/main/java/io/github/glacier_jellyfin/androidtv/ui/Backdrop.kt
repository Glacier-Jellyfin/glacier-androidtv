package io.github.glacier_jellyfin.androidtv.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.LocalReduceMotion

/** The design's `gKen`: a slow 22 s zoom and drift, alternating. Off with "reduce motion". */
@Composable
fun KenBurns(content: @Composable () -> Unit) {
    if (LocalReduceMotion.current) {
        content()
        return
    }
    val transition = rememberInfiniteTransition(label = "ken")
    val t by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(22_000), RepeatMode.Reverse), label = "kenT")
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                val scale = 1.02f + 0.07f * t
                scaleX = scale
                scaleY = scale
                translationX = -0.012f * size.width * t
                translationY = -0.01f * size.height * t
            },
    ) { content() }
}

/**
 * Ground of pages with artwork at the top. The artwork fades into it, so the
 * page stays on this solid colour instead of the accent gradient behind other screens.
 */
val ImagePageGround = GlacierColors.Void

/**
 * Top backdrop of detail pages: artwork with slow zoom, darkened from the left
 * where the text sits and fading into the page ground at the bottom.
 */
@Composable
fun DetailBackdrop(url: String?, height: Int, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(height.dp).clipToBounds()) {
        KenBurns { Artwork(url, Modifier.fillMaxSize()) }
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.horizontalGradient(0f to Color(0xF70A1420), 0.44f to Color(0xCC0A1420), 0.62f to Color(0x990A1420), 0.9f to Color(0x290A1420))),
        )
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(300.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, GlacierColors.Void))),
        )
    }
}
