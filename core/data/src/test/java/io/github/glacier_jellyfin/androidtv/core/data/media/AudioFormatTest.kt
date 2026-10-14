package io.github.glacier_jellyfin.androidtv.core.data.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioFormatTest {

    private fun format(codec: String?) = AudioFormat(codec, sampleRate = 44_100, bitDepth = 16, channels = 2)

    @Test
    fun `flac, alac and pcm are lossless, lossy codecs are not`() {
        listOf("flac", "FLAC", "alac", "pcm_s16le", "pcm_s24le", "ape", "wavpack", "dsd_lsbf").forEach { assertTrue(it, format(it).lossless) }
        listOf(null, "", "mp3", "aac", "opus", "vorbis", "ac3").forEach { assertFalse(it.toString(), format(it).lossless) }
    }
}
