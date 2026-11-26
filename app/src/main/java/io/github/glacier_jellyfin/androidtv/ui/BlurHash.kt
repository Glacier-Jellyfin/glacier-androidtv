package io.github.glacier_jellyfin.androidtv.ui

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow

/*
 * A BlurHash (https://blurha.sh) is a short string that holds a few low-frequency colour components of a picture.
 * Decoded at a tiny size and drawn up smoothed, it stands in for the picture while that loads.
 */

private const val BASE83 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz#$%*+,-.:;=?@[]^_{|}~"
private const val BASE83_RADIX = 83
private const val MAX_COMPONENTS = 9
private const val SIZE_CHARS = 1
private const val MAX_AC_CHARS = 1
private const val DC_CHARS = 4
private const val AC_CHARS = 2
private const val HEADER_CHARS = SIZE_CHARS + MAX_AC_CHARS + DC_CHARS
private const val AC_LEVELS = 19
private const val AC_MIDDLE = 9f
private const val MAX_AC_STEPS = 166f
private const val ALPHA_OPAQUE = 0xFF000000.toInt()
private const val BYTE_MASK = 0xFF
private const val BYTE_MAX = 255f
private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8
private const val SRGB_THRESHOLD_ENCODED = 0.04045f
private const val SRGB_THRESHOLD_LINEAR = 0.0031308f
private const val SRGB_LINEAR_SLOPE = 12.92f
private const val SRGB_OFFSET = 0.055f
private const val SRGB_SCALE = 1.055f
private const val SRGB_GAMMA = 2.4f
private const val ROUNDING = 0.5f

/** The number of colour components across and down that [hash] holds, or null when it is not a valid BlurHash. */
internal fun blurHashComponents(hash: String): Pair<Int, Int>? {
    if (hash.length < HEADER_CHARS) return null
    val size = base83(hash, 0, SIZE_CHARS) ?: return null
    val x = size % MAX_COMPONENTS + 1
    val y = size / MAX_COMPONENTS + 1
    if (hash.length != HEADER_CHARS + AC_CHARS * (x * y - 1)) return null
    return x to y
}

/**
 * The pixels (ARGB, row by row) of [hash] decoded to [width] by [height]; null for an invalid hash or size. [punch]
 * strengthens (above 1) or softens (below 1) the contrast of the detail.
 */
fun decodeBlurHash(
    hash: String,
    width: Int,
    height: Int,
    punch: Float = 1f,
): IntArray? {
    if (width <= 0 || height <= 0) return null
    val (numX, numY) = blurHashComponents(hash) ?: return null
    val maxAc = ((base83(hash, 1, MAX_AC_CHARS) ?: return null) + 1) / MAX_AC_STEPS
    val colors = Array(numX * numY) { FloatArray(3) }
    val dc = base83(hash, SIZE_CHARS + MAX_AC_CHARS, DC_CHARS) ?: return null
    colors[0][0] = srgbToLinear(dc shr RED_SHIFT and BYTE_MASK)
    colors[0][1] = srgbToLinear(dc shr GREEN_SHIFT and BYTE_MASK)
    colors[0][2] = srgbToLinear(dc and BYTE_MASK)
    for (i in 1 until numX * numY) {
        val value = base83(hash, HEADER_CHARS + (i - 1) * AC_CHARS, AC_CHARS) ?: return null
        colors[i][0] = acComponent(value / (AC_LEVELS * AC_LEVELS), maxAc * punch)
        colors[i][1] = acComponent(value / AC_LEVELS % AC_LEVELS, maxAc * punch)
        colors[i][2] = acComponent(value % AC_LEVELS, maxAc * punch)
    }
    // The cosines of a column or a row are the same for every pixel of it.
    val cosX = Array(width) { x -> FloatArray(numX) { i -> cos(PI * x * i / width).toFloat() } }
    val cosY = Array(height) { y -> FloatArray(numY) { j -> cos(PI * y * j / height).toFloat() } }
    val pixels = IntArray(width * height)
    for (y in 0 until height) {
        for (x in 0 until width) {
            var r = 0f
            var g = 0f
            var b = 0f
            for (j in 0 until numY) {
                for (i in 0 until numX) {
                    val basis = cosX[x][i] * cosY[y][j]
                    val color = colors[j * numX + i]
                    r += color[0] * basis
                    g += color[1] * basis
                    b += color[2] * basis
                }
            }
            pixels[y * width + x] = ALPHA_OPAQUE or
                (linearToSrgb(r) shl RED_SHIFT) or (linearToSrgb(g) shl GREEN_SHIFT) or linearToSrgb(b)
        }
    }
    return pixels
}

/** [hash] as a bitmap of [width] by [height]; null for an invalid hash. */
fun blurHashBitmap(
    hash: String,
    width: Int,
    height: Int,
): ImageBitmap? {
    val pixels = decodeBlurHash(hash, width, height) ?: return null
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/** The size to decode a placeholder of a picture with [aspectRatio] (width over height): [PLACEHOLDER_PX] on its long side. */
fun blurHashSize(aspectRatio: Float): Pair<Int, Int> = if (aspectRatio >= 1f) {
    PLACEHOLDER_PX to (PLACEHOLDER_PX / aspectRatio).toInt().coerceAtLeast(1)
} else {
    (PLACEHOLDER_PX * aspectRatio).toInt().coerceAtLeast(1) to PLACEHOLDER_PX
}

/** The long side of a decoded placeholder; it is drawn up smoothed, so more pixels would add nothing. */
private const val PLACEHOLDER_PX = 32

/** Decoded placeholders by hash and size, so the neighbours decoded ahead show at once. */
object BlurHashCache {
    private const val ENTRIES = 30
    private val cache = LruCache<String, ImageBitmap>(ENTRIES)

    fun get(
        hash: String,
        width: Int,
        height: Int,
    ): ImageBitmap? = cache.get(key(hash, width, height))

    /** The placeholder from the cache, else decoded now: call off the main thread. Null for an invalid hash. */
    fun decode(
        hash: String,
        width: Int,
        height: Int,
    ): ImageBitmap? = get(hash, width, height)
        ?: blurHashBitmap(hash, width, height)?.also { cache.put(key(hash, width, height), it) }

    private fun key(
        hash: String,
        width: Int,
        height: Int,
    ) = "$hash:$width:$height"
}

private fun base83(
    text: String,
    start: Int,
    length: Int,
): Int? {
    var value = 0
    for (i in start until start + length) {
        val digit = BASE83.indexOf(text[i])
        if (digit < 0) return null
        value = value * BASE83_RADIX + digit
    }
    return value
}

/** One colour channel of an AC component: its [level] (0 to 18) as a signed square, scaled by [scale]. */
private fun acComponent(level: Int, scale: Float): Float {
    val v = (level - AC_MIDDLE) / AC_MIDDLE
    return v * abs(v) * scale
}

private fun srgbToLinear(value: Int): Float {
    val v = value / BYTE_MAX
    return if (v <= SRGB_THRESHOLD_ENCODED) {
        v / SRGB_LINEAR_SLOPE
    } else {
        ((v + SRGB_OFFSET) / SRGB_SCALE).pow(SRGB_GAMMA)
    }
}

private fun linearToSrgb(value: Float): Int {
    val v = value.coerceIn(0f, 1f)
    val encoded = if (v <= SRGB_THRESHOLD_LINEAR) {
        v * SRGB_LINEAR_SLOPE
    } else {
        SRGB_SCALE * v.pow(1f / SRGB_GAMMA) - SRGB_OFFSET
    }
    return (encoded * BYTE_MAX + ROUNDING).toInt().coerceIn(0, BYTE_MASK)
}
