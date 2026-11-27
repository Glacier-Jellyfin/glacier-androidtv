package io.github.glacier_jellyfin.androidtv.library

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.transformations
import coil3.size.Size
import coil3.transform.Transformation
import io.github.glacier_jellyfin.androidtv.core.data.playback.SourceVideo

/**
 * Whether a picture of [width] by [height] pixels, turned by [rotationDegrees] (0, 90, 180 or 270; other values count as
 * upright) and with pixels of the shape [pixelWidthHeightRatio], is taller than wide when it is shown. Only such a
 * picture, a phone video held upright, gets the blurred backdrop; a landscape one never does, not even in 4:3.
 */
internal fun isPortrait(
    width: Int,
    height: Int,
    rotationDegrees: Int = 0,
    pixelWidthHeightRatio: Float = 1f,
): Boolean {
    if (width <= 0 || height <= 0) return false
    val ratio = if (pixelWidthHeightRatio > 0f) pixelWidthHeightRatio else 1f
    val shownWidth = width * ratio
    val quarterTurn = ((rotationDegrees % HALF_TURN) + HALF_TURN) % HALF_TURN == QUARTER_TURN
    return if (quarterTurn) shownWidth > height else height > shownWidth
}

/**
 * Whether the video [video] gets a blurred background made by the player: the setting is [enabled], the picture is
 * portrait as it is shown, and it is not HDR (the effects pipeline would flatten HDR to a dull picture, so those keep
 * the plain path with black bars).
 */
internal fun wantsBlurredBackdrop(video: SourceVideo?, enabled: Boolean): Boolean {
    if (!enabled || video == null) return false
    val hdr = video.rangeType?.let { !it.equals("SDR", ignoreCase = true) && !it.equals("Unknown", ignoreCase = true) }
    if (hdr == true) return false
    return isPortrait(video.width ?: 0, video.height ?: 0, video.rotation ?: 0)
}

/**
 * A blurred, dimmed copy of the photo at [url], filling the whole screen, for behind a photo that leaves black bars. It
 * is the same request the viewer shows, so it comes from Coil's memory cache and costs no download. From Android 12 the
 * GPU blurs it ([Modifier.blur]); before that, where there is no such effect, a small decode of it is blurred once on the
 * CPU ([BoxBlur]) and kept in the memory cache, so browsing back is free. Until the photo is loaded nothing shows.
 */
@Composable
internal fun BlurredPhotoBackdrop(url: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Box(modifier.fillMaxSize()) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().blur(BLUR_RADIUS_DP.dp, BlurredEdgeTreatment.Rectangle),
            )
        } else {
            val request = remember(url) {
                ImageRequest.Builder(context)
                    .data(url)
                    .size(SOFT_PX, SOFT_PX)
                    .transformations(BoxBlur(SOFT_RADIUS))
                    .memoryCacheKey(softCacheKey(url))
                    .build()
            }
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = SCRIM_ALPHA)))
    }
}

/** The memory cache key of the small, blurred decode of [url], apart from the photo's own entry. */
internal fun softCacheKey(url: String): String = "backdrop:$url"

/** Blurs a picture on the CPU with three box passes, which come close to a Gaussian, edge pixels repeated. */
internal class BoxBlur(private val radius: Int) : Transformation() {
    override val cacheKey: String = "boxblur:$radius"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val width = input.width
        val height = input.height
        var pixels = IntArray(width * height).also { input.getPixels(it, 0, width, 0, 0, width, height) }
        var spare = IntArray(pixels.size)
        repeat(PASSES) {
            boxPass(pixels, spare, Axis(lines = height, length = width, step = 1, lineStep = width), radius)
            pixels = spare.also { spare = pixels }
            boxPass(pixels, spare, Axis(lines = width, length = height, step = width, lineStep = 1), radius)
            pixels = spare.also { spare = pixels }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private companion object {
        const val PASSES = 3
    }
}

/** How the pixels of one direction lie in an array: [lines] rows of [length] pixels, [step] apart, rows [lineStep] apart. */
internal class Axis(
    val lines: Int,
    val length: Int,
    val step: Int,
    val lineStep: Int,
)

/** One running-average pass of [radius] pixels either side over every line of [axis], from [src] into [dst]. */
internal fun boxPass(
    src: IntArray,
    dst: IntArray,
    axis: Axis,
    radius: Int,
) {
    val window = 2 * radius + 1
    val last = axis.length - 1
    for (line in 0 until axis.lines) {
        val base = line * axis.lineStep
        var sumA = 0
        var sumR = 0
        var sumG = 0
        var sumB = 0
        for (i in -radius..radius) {
            val p = src[base + i.coerceIn(0, last) * axis.step]
            sumA += p ushr ALPHA_SHIFT
            sumR += (p shr RED_SHIFT) and CHANNEL
            sumG += (p shr GREEN_SHIFT) and CHANNEL
            sumB += p and CHANNEL
        }
        for (i in 0..last) {
            dst[base + i * axis.step] = (sumA / window shl ALPHA_SHIFT) or (sumR / window shl RED_SHIFT) or
                (sumG / window shl GREEN_SHIFT) or (sumB / window)
            val out = src[base + (i - radius).coerceIn(0, last) * axis.step]
            val inn = src[base + (i + radius + 1).coerceIn(0, last) * axis.step]
            sumA += (inn ushr ALPHA_SHIFT) - (out ushr ALPHA_SHIFT)
            sumR += ((inn shr RED_SHIFT) and CHANNEL) - ((out shr RED_SHIFT) and CHANNEL)
            sumG += ((inn shr GREEN_SHIFT) and CHANNEL) - ((out shr GREEN_SHIFT) and CHANNEL)
            sumB += (inn and CHANNEL) - (out and CHANNEL)
        }
    }
}

/** The radius of the GPU blur from Android 12, in dp of the 1920 design grid. */
private const val BLUR_RADIUS_DP = 50

/** Before Android 12: the photo is decoded to this many pixels on its long side and blurred by [SOFT_RADIUS] there. */
private const val SOFT_PX = 240
private const val SOFT_RADIUS = 14

/** How dark the layer over the blurred picture is. */
private const val SCRIM_ALPHA = 0.45f

private const val QUARTER_TURN = 90
private const val HALF_TURN = 180
private const val ALPHA_SHIFT = 24
private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8
private const val CHANNEL = 0xFF
