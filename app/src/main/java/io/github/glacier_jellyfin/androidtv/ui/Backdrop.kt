package io.github.glacier_jellyfin.androidtv.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.LocalReduceMotion
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The design's `gKen`: a slow 22 s zoom and drift, alternating. Off with "reduce motion".
 * Without [animate] it holds still where it is: each animated frame redraws the whole
 * artwork, which a video on top of it cannot afford on slower TVs.
 */
@Composable
fun KenBurns(animate: Boolean = true, content: @Composable () -> Unit) {
    if (LocalReduceMotion.current) {
        content()
        return
    }
    val t = remember { Animatable(0f) }
    var forward by remember { mutableStateOf(true) }
    LaunchedEffect(animate) {
        // Pausing cancels this effect, which stops the animation at its current value.
        while (animate) {
            val target = if (forward) 1f else 0f
            t.animateTo(target, tween((abs(target - t.value) * KEN_BURNS_MS).roundToInt()))
            forward = !forward
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                val scale = 1.02f + 0.07f * t.value
                scaleX = scale
                scaleY = scale
                translationX = -0.012f * size.width * t.value
                translationY = -0.01f * size.height * t.value
            },
    ) { content() }
}

private const val KEN_BURNS_MS = 22_000

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
