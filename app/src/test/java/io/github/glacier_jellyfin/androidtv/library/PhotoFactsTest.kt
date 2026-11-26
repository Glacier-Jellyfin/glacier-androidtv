package io.github.glacier_jellyfin.androidtv.library

import io.github.glacier_jellyfin.androidtv.core.data.media.PhotoDetails
import io.github.glacier_jellyfin.androidtv.core.data.playback.SourceAudio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhotoFactsTest {

    @Test
    fun `a fast shutter is a fraction`() {
        assertEquals("1/250 s", exposureText(0.004))
        assertEquals("1/3 s", exposureText(0.33))
    }

    @Test
    fun `a slow shutter is seconds`() {
        assertEquals("2 s", exposureText(2.0))
        assertEquals("1.5 s", exposureText(1.5))
    }

    @Test
    fun `missing exposure is left out`() {
        assertNull(exposureText(null))
        assertNull(exposureText(0.0))
    }

    @Test
    fun `aperture drops a trailing zero`() {
        assertEquals("f/1.8", apertureText(1.8))
        assertEquals("f/2", apertureText(2.0))
        assertNull(apertureText(0.0))
    }

    @Test
    fun `focal length shows a decimal only when needed`() {
        assertEquals("26 mm", focalLengthText(26.0))
        assertEquals("6.9 mm", focalLengthText(6.9))
        assertNull(focalLengthText(null))
    }

    @Test
    fun `iso and dimensions`() {
        assertEquals("ISO 100", isoText(100))
        assertNull(isoText(0))
        assertEquals("\u20664032 × 3024\u2069", dimensionsText(4032, 3024))
        assertNull(dimensionsText(4032, null))
        assertNull(dimensionsText(0, 10))
    }

    @Test
    fun `camera is make and model without repeating the make`() {
        assertEquals("Canon EOS R5", cameraText("Canon", "EOS R5"))
        assertEquals("Canon EOS R5", cameraText("Canon", "Canon EOS R5"))
        assertEquals("Pixel 8", cameraText(null, "Pixel 8"))
        assertEquals("Canon", cameraText("Canon", " "))
        assertNull(cameraText(null, null))
    }

    @Test
    fun `location has four decimals, a dot and a hemisphere`() {
        assertEquals("\u206659.9139° N, 10.7522° E\u2069", locationText(59.9139, 10.7522))
        assertEquals("\u206633.8688° S, 151.2093° W · 12 m\u2069", locationText(-33.8688, -151.2093, 11.6))
        assertNull(locationText(59.9, null))
        assertNull(locationText(null, 10.7))
    }

    @Test
    fun `the static file of a video comes from its details`() {
        assertNull(staticSourceFile(PhotoDetails(format = "mp4")))
        val audio = SourceAudio(1, "aac", null, 2, 48_000, 128_000)
        val file = staticSourceFile(PhotoDetails(format = "mp4", sizeBytes = 5L, bitrate = 8_000_000, audio = audio))!!
        assertEquals("mp4", file.container)
        assertEquals(5L, file.sizeBytes)
        assertEquals(listOf(audio), file.audio)
        assertNull(file.video)
    }

    @Test
    fun `format is in capitals and jpg is JPEG`() {
        assertEquals("JPEG", formatText("jpg"))
        assertEquals("JPEG", formatText("jpeg"))
        assertEquals("HEIC", formatText("heic"))
        assertEquals("MP4", formatText(" mp4 "))
        assertNull(formatText(null))
        assertNull(formatText(" "))
    }
}
