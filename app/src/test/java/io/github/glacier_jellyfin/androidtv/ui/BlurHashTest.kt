package io.github.glacier_jellyfin.androidtv.ui

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BlurHashTest {

    private val digits = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz#$%*+,-.:;=?@[]^_{|}~"
    private val known = "LEHV6nWB2yk8pyo0adR*.7kCMdnj"

    private fun encode83(value: Int, length: Int): String {
        var rest = value
        val out = CharArray(length)
        for (i in length - 1 downTo 0) {
            out[i] = digits[rest % 83]
            rest /= 83
        }
        return String(out)
    }

    /** A hash of one component, so the whole picture has the single colour [rgb]. */
    private fun flat(rgb: Int) = "0" + "0" + encode83(rgb, 4)

    private fun channel(pixel: Int, shift: Int) = pixel shr shift and 0xFF

    @Test
    fun `invalid hashes give null`() {
        assertNull(decodeBlurHash("", 8, 8))
        assertNull(decodeBlurHash("LEHV6", 8, 8))
        // One character short and one too long.
        assertNull(decodeBlurHash(known.dropLast(1), 8, 8))
        assertNull(decodeBlurHash(known + "0", 8, 8))
        // A space is not in the alphabet.
        assertNull(decodeBlurHash(known.replaceRange(10, 11, " "), 8, 8))
        // A size that is not positive.
        assertNull(decodeBlurHash(known, 0, 8))
        assertNull(decodeBlurHash(known, 8, -1))
    }

    @Test
    fun `component count comes from the first character`() {
        assertEquals(4 to 3, blurHashComponents(known))
        assertEquals(1 to 1, blurHashComponents(flat(0)))
        assertNull(blurHashComponents("L"))
    }

    @Test
    fun `decodes to the requested size`() {
        val pixels = assertNotNull(decodeBlurHash(known, 32, 20))
        assertEquals(32 * 20, pixels.size)
        assertTrue(pixels.all { it.toLong() and 0xFF000000L == 0xFF000000L })
    }

    @Test
    fun `a single component is a flat colour of the DC value`() {
        val pixels = assertNotNull(decodeBlurHash(flat(0xC8641E), 4, 3))
        pixels.forEach {
            assertEquals(0xC8, channel(it, 16))
            assertEquals(0x64, channel(it, 8))
            assertEquals(0x1E, channel(it, 0))
        }
    }

    @Test
    fun `the average of the known hash is its DC colour`() {
        // The DC colour sits in characters 2 to 5, as sRGB bytes.
        val dc = known.substring(2, 6).fold(0) { acc, c -> acc * 83 + digits.indexOf(c) }
        val size = 64
        val pixels = assertNotNull(decodeBlurHash(known, size, size))

        // The AC components average out over the picture, so the mean is the DC in linear light.
        fun linear(byte: Int): Double {
            val v = byte / 255.0
            return if (v <= 0.04045) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
        }

        for (shift in intArrayOf(16, 8, 0)) {
            val mean = pixels.map { linear(channel(it, shift)) }.average()
            assertTrue("channel $shift", abs(mean - linear(channel(dc, shift))) < 0.02)
        }
    }

    @Test
    fun `corners of the known hash are plausible`() {
        val pixels = assertNotNull(decodeBlurHash(known, 32, 32))
        // The picture has detail, so opposite corners differ, and none is pure black or white.
        val first = pixels[0]
        val last = pixels[32 * 32 - 1]
        assertTrue(first != last)
        for (pixel in listOf(first, last)) {
            val sum = channel(pixel, 16) + channel(pixel, 8) + channel(pixel, 0)
            assertTrue(sum in 30 until 3 * 255 - 30)
        }
    }

    private fun <T> assertNotNull(value: T?): T = checkNotNull(value)
}
