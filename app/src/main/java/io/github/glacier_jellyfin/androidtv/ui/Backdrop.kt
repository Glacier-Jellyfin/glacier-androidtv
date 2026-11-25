package io.github.glacier_jellyfin.androidtv.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Scale
import coil3.toBitmap
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.LocalReduceMotion
import io.github.glacier_jellyfin.androidtv.core.designsystem.startGradient
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

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
        if (!animate) return@LaunchedEffect
        // Pausing cancels this effect, which stops the animation at its current value.
        while (isActive) {
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
fun DetailBackdrop(
    url: String?,
    height: Int,
    modifier: Modifier = Modifier,
    rotation: List<String> = emptyList(),
    visible: Boolean = true,
) {
    Box(modifier.fillMaxWidth().height(height.dp).clipToBounds()) {
        KenBurns(animate = visible) {
            if (rotation.size > 1 && !LocalReduceMotion.current) {
                RotatingBackdrop(rotation, visible)
            } else {
                Artwork(rotation.firstOrNull() ?: url, Modifier.fillMaxSize())
            }
        }
        Box(
            Modifier
                .fillMaxSize()
                .startGradient(
                    0f to Color(0xF70A1420),
                    0.44f to Color(0xCC0A1420),
                    0.62f to Color(0x990A1420),
                    0.9f to Color(0x290A1420),
                ),
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

/** Loads each next backdrop before replacing the one on screen. */
@Composable
private fun RotatingBackdrop(urls: List<String>, visible: Boolean) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var size by remember { mutableStateOf(IntSize.Zero) }
    var next by remember(urls) { mutableIntStateOf(1) }
    var image by remember(urls) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(urls, visible, size, lifecycle, context) {
        if (!visible || size.width == 0 || size.height == 0) return@LaunchedEffect
        val loader = SingletonImageLoader.get(context)
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (isActive) {
                delay(BACKDROP_INTERVAL_MS)
                val request = ImageRequest.Builder(context)
                    .data(urls[next])
                    .size(size.width, size.height)
                    .scale(Scale.FILL)
                    .build()
                val result = loader.execute(request)
                next = (next + 1) % urls.size
                if (result is SuccessResult) image = result.image.toBitmap().asImageBitmap()
            }
        }
    }
    AnimatedContent(
        targetState = image,
        modifier = Modifier.fillMaxSize().onSizeChanged { size = it },
        transitionSpec = {
            // Keeping the old image opaque prevents the page ground from showing through the dissolve.
            (fadeIn(tween(BACKDROP_FADE_MS)) togetherWith ExitTransition.KeepUntilTransitionsFinished).using(null)
        },
        label = "detail backdrop",
    ) { shown ->
        if (shown == null) {
            Artwork(urls.first(), Modifier.fillMaxSize())
        } else {
            Image(shown, contentDescription = null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
    }
}

private const val BACKDROP_INTERVAL_MS = 9_000L
private const val BACKDROP_FADE_MS = 1_500
