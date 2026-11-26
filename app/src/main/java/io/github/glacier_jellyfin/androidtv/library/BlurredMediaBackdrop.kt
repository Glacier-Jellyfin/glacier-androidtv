package io.github.glacier_jellyfin.androidtv.library

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
internal fun BlurredMediaBackdrop(image: ImageBitmap) {
    Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = BACKDROP_DIM)))
}

/** Only media that leaves space in the viewport needs a backdrop. */
internal fun backdropSampleSize(aspect: Float, viewport: IntSize): IntSize? {
    if (viewport.width <= 0 || viewport.height <= 0) return null
    val viewportAspect = viewport.width.toFloat() / viewport.height
    if (!aspect.isFinite() || aspect <= 0f || abs(aspect / viewportAspect - 1f) < MIN_ASPECT_DIFFERENCE) return null
    return IntSize(
        (BACKDROP_SAMPLE_EDGE * aspect.coerceAtMost(1f)).roundToInt().coerceAtLeast(1),
        (BACKDROP_SAMPLE_EDGE / aspect.coerceAtLeast(1f)).roundToInt().coerceAtLeast(1),
    )
}

/** Two separable box passes soften a small sample of the media. */
internal fun Bitmap.blurredFrame(): Bitmap {
    val source = IntArray(width * height)
    val scratch = IntArray(source.size)
    getPixels(source, 0, width, 0, 0, width, height)
    val radius = (minOf(width, height) / BLUR_RADIUS_DIVISOR).coerceAtLeast(1)
    repeat(BLUR_PASSES) {
        blurRows(source, scratch, IntSize(width, height), radius)
        // Each pass transposes the output, so the second call blurs the other axis.
        blurRows(scratch, source, IntSize(height, width), radius)
    }
    return Bitmap.createBitmap(source, width, height, Bitmap.Config.ARGB_8888)
}

private fun blurRows(
    source: IntArray,
    target: IntArray,
    size: IntSize,
    radius: Int,
) {
    val diameter = radius * 2 + 1
    for (y in 0 until size.height) {
        val row = y * size.width
        for (x in 0 until size.width) {
            var red = 0
            var green = 0
            var blue = 0
            for (offset in -radius..radius) {
                val pixel = source[row + (x + offset).coerceIn(0, size.width - 1)]
                red += android.graphics.Color.red(pixel)
                green += android.graphics.Color.green(pixel)
                blue += android.graphics.Color.blue(pixel)
            }
            target[x * size.height + y] = android.graphics.Color.rgb(red / diameter, green / diameter, blue / diameter)
        }
    }
}

private const val BACKDROP_SAMPLE_EDGE = 96
private const val BACKDROP_DIM = 0.18f
private const val MIN_ASPECT_DIFFERENCE = 0.02f
private const val BLUR_RADIUS_DIVISOR = 6
private const val BLUR_PASSES = 2
