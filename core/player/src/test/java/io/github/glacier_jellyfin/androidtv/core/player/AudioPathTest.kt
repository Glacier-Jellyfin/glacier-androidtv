package io.github.glacier_jellyfin.androidtv.core.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AudioPathTest {

    @Test
    fun `leaves compressed sound to FFmpeg only while it is decoded here and FFmpeg can`() {
        assertFalse(AudioPath.platformMayDecode(MimeTypes.AUDIO_AC3, decodeCompressed = true, ffmpegDecodes = true))
        assertFalse(AudioPath.platformMayDecode(MimeTypes.AUDIO_E_AC3, true, true))
        assertTrue("Flag off: unchanged", AudioPath.platformMayDecode(MimeTypes.AUDIO_AC3, false, true))
        assertTrue(
            "Without FFmpeg the platform decoder stays",
            AudioPath.platformMayDecode(MimeTypes.AUDIO_AC3, true, false),
        )
        assertTrue("Not compressed", AudioPath.platformMayDecode(MimeTypes.AUDIO_AAC, true, true))
        assertTrue("Video", AudioPath.platformMayDecode(MimeTypes.VIDEO_H264, true, true))
        assertTrue(AudioPath.platformMayDecode(null, true, true))
    }

    @Test
    fun `decodes other streams only when the file is decoded and the title alternates`() {
        assertTrue(AudioPath.decodeOtherStreams(alternates = true, fileDecoded = true))
        assertFalse("A bitstream file leaves the transcode alone", AudioPath.decodeOtherStreams(true, false))
        assertFalse("Not known", AudioPath.decodeOtherStreams(true, null))
        assertFalse("Music or Live TV", AudioPath.decodeOtherStreams(false, true))
    }

    @Test
    fun `tells decoded sound from a bitstream by the encoding`() {
        assertTrue(AudioPath.isDecoded(C.ENCODING_PCM_16BIT))
        assertTrue(AudioPath.isDecoded(C.ENCODING_PCM_FLOAT))
        assertFalse(AudioPath.isDecoded(C.ENCODING_AC3))
        assertFalse(AudioPath.isDecoded(C.ENCODING_DTS_HD))
    }

    @Test
    fun `predicts the way of the file from the support of the output`() {
        // DTS-HD MA on a device that takes no DTS: decoded here.
        assertEquals(true, AudioPath.predictDecoded(MimeTypes.AUDIO_DTS_HD, passthrough = false, iec61937 = false))
        assertEquals(false, AudioPath.predictDecoded(MimeTypes.AUDIO_DTS_HD, passthrough = true, iec61937 = false))
        assertEquals(false, AudioPath.predictDecoded(MimeTypes.AUDIO_TRUEHD, passthrough = false, iec61937 = true))
        assertEquals(false, AudioPath.predictDecoded(MimeTypes.AUDIO_AC3, passthrough = true, iec61937 = false))
        assertEquals(true, AudioPath.predictDecoded(MimeTypes.AUDIO_AAC, passthrough = false, iec61937 = false))
        assertNull(AudioPath.predictDecoded(null, passthrough = false, iec61937 = false))
    }

    @Test
    fun `names the file's mime type from the server's codec`() {
        assertEquals(MimeTypes.AUDIO_DTS_HD, AudioPath.mimeOf("dts", "DTS-HD MA"))
        assertEquals(MimeTypes.AUDIO_DTS, AudioPath.mimeOf("dts", "DTS"))
        assertEquals(MimeTypes.AUDIO_E_AC3_JOC, AudioPath.mimeOf("eac3", "Dolby Digital Plus + Dolby Atmos"))
        assertEquals(MimeTypes.AUDIO_RAW, AudioPath.mimeOf("aac", null))
        assertNull(AudioPath.mimeOf(null, null))
    }

    /** A sink under the guard that takes every format directly, like a receiver that does AC-3. */
    private fun guard(decoders: (String) -> Boolean = { true }): GuardedAudioSink {
        val inner = Proxy.newProxyInstance(
            AudioSink::class.java.classLoader,
            arrayOf(AudioSink::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "supportsFormat" -> true

                "getFormatSupport" -> AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY

                else -> when (method.returnType) {
                    java.lang.Boolean.TYPE -> false
                    Integer.TYPE -> 0
                    java.lang.Long.TYPE -> 0L
                    else -> null
                }
            }
        } as AudioSink
        return GuardedAudioSink(inner, onStall = {}, decoders = decoders)
    }

    private fun format(mime: String) = Format.Builder()
        .setSampleMimeType(mime)
        .setChannelCount(6)
        .setSampleRate(48_000)
        .build()

    @Test
    fun `the sink reports compressed formats as not supported while it decodes`() {
        val sink = guard()
        assertTrue(sink.supportsFormat(format(MimeTypes.AUDIO_AC3)))
        sink.decodeCompressed = true
        for (mime in listOf(
            MimeTypes.AUDIO_AC3,
            MimeTypes.AUDIO_E_AC3,
            MimeTypes.AUDIO_E_AC3_JOC,
            MimeTypes.AUDIO_DTS,
        )) {
            assertFalse(mime, sink.supportsFormat(format(mime)))
            assertEquals(mime, AudioSink.SINK_FORMAT_UNSUPPORTED, sink.getFormatSupport(format(mime)))
        }
        // Decoded sound itself still goes out.
        assertTrue(sink.supportsFormat(format(MimeTypes.AUDIO_RAW)))
        sink.decodeCompressed = false
        assertEquals(AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY, sink.getFormatSupport(format(MimeTypes.AUDIO_AC3)))
    }

    @Test
    fun `the sink leaves a format alone that no decoder takes`() {
        val sink = guard(decoders = { false })
        sink.decodeCompressed = true
        assertTrue(sink.supportsFormat(format(MimeTypes.AUDIO_AC3)))
    }
}
