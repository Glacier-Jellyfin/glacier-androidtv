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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Scale
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

/** Time on each picture and the crossfade into the next. */
private const val ROTATION_INTERVAL_MS = 10_000L
private const val ROTATION_FADE_MS = 1_500

/**
 * [urls] one after the other under a single [KenBurns], so the zoom runs on across the changes.
 * Two layers alternate: the one showing stays fully opaque while the other fades in over it, and
 * only the hidden one ever gets a new picture. Nothing runs while not [active].
 */
@Composable
private fun RotatingArtwork(urls: List<String>, active: Boolean) {
    val context = LocalContext.current
    var size by remember { mutableStateOf(IntSize.Zero) }
    // The picture each layer shows, which layer is on top, and how far the top one has faded in.
    val layers = remember { mutableStateListOf<String?>(urls.first(), null) }
    var top by remember { mutableIntStateOf(0) }
    val fade = remember { Animatable(1f) }
    var current by remember { mutableIntStateOf(0) }
    val failed = remember { mutableSetOf<Int>() }

    LaunchedEffect(active, size) {
        if (!active || size == IntSize.Zero) return@LaunchedEffect
        // Paused mid-fade: finish it first.
        if (fade.value < 1f) fade.animateTo(1f, tween(ROTATION_FADE_MS))
        val loader = SingletonImageLoader.get(context)
        while (true) {
            // Preload so the picture is in the memory cache, under the key Artwork will ask for.
            val next = nextLoaded(urls, current, failed) { url ->
                val request = ImageRequest.Builder(context).data(url).size(size.width, size.height)
                    .scale(Scale.FILL).build()
                loader.execute(request) is SuccessResult
            } ?: return@LaunchedEffect
            delay(ROTATION_INTERVAL_MS)
            val back = 1 - top
            layers[back] = urls[next]
            top = back
            current = next
            fade.snapTo(0f)
            fade.animateTo(1f, tween(ROTATION_FADE_MS))
        }
    }

    KenBurns(animate = active) {
        Box(Modifier.fillMaxSize().onSizeChanged { size = it }.artworkFill()) {
            layers.forEachIndexed { slot, url ->
                val isTop = slot == top
                Artwork(
                    url,
                    Modifier
                        .fillMaxSize()
                        .zIndex(if (isTop) 1f else 0f)
                        .graphicsLayer { alpha = if (isTop) fade.value else 1f },
                    fill = false,
                )
            }
        }
    }
}

/** The next picture after [current] that [load]s, marking those that do not as [failed]; null if none. */
private suspend fun nextLoaded(
    urls: List<String>,
    current: Int,
    failed: MutableSet<Int>,
    load: suspend (String) -> Boolean,
): Int? {
    for (step in 1 until urls.size) {
        val index = (current + step) % urls.size
        if (index in failed) continue
        if (load(urls[index])) return index
        failed += index
    }
    return null
}

/**
 * Top backdrop of detail pages: artwork with slow zoom, darkened from the left
 * where the text sits and fading into the page ground at the bottom. With several [urls]
 * the pictures take turns; [paused] holds them (the page scrolled away).
 */
@Composable
fun DetailBackdrop(
    url: String?,
    height: Int,
    modifier: Modifier = Modifier,
    urls: List<String> = emptyList(),
    paused: Boolean = false,
) {
    // Rotation needs two distinct pictures; "reduce motion" keeps the single still one.
    val rotation = remember(urls) { urls.distinct() }
    val rotate = rotation.size >= 2 && !LocalReduceMotion.current
    val resumed = LocalLifecycleOwner.current.lifecycle.currentStateAsState().value.isAtLeast(Lifecycle.State.RESUMED)
    Box(modifier.fillMaxWidth().height(height.dp).clipToBounds()) {
        if (rotate) {
            key(rotation) { RotatingArtwork(rotation, active = resumed && !paused) }
        } else {
            KenBurns { Artwork(url, Modifier.fillMaxSize()) }
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
