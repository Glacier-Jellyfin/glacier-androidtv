package io.github.glacier_jellyfin.androidtv.core.player

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class DolbyVisionSampleTest {

    @Test
    fun `drops the enhancement layer and rewrites the RPU of a sample`() {
        val rpu = resource("mel_orig.bin").let { it.copyOfRange(4, it.size) }
        val converted = resource("mel_to_81.bin").let { it.copyOfRange(4, it.size) }
        // A slice of the base layer (type 1), the enhancement layer wrapped in type 63, the RPU (type 62).
        val slice = byteArrayOf(0x02, 0x01, 0x11, 0x22, 0x33)
        val enhancementLayer = byteArrayOf(0x7E, 0x01, 0x44, 0x55)
        val sample = START + slice + START + enhancementLayer + START + RPU_HEADER + rpu
        val expected = START + slice + START + RPU_HEADER + converted

        val result = DolbyVisionProfile7Extractors.toProfile81(byteArrayOf(9) + sample + byteArrayOf(9), 1, sample.size)
        assertArrayEquals(expected, result)
    }

    @Test
    fun `converts a whole profile 7 stream like dovi_tool`() {
        // A test pattern with an enhancement layer and RPUs, muxed and converted by dovi_tool (README.md).
        val stream = resource("profile7_fel.hevc")
        val result = DolbyVisionProfile7Extractors.toProfile81(stream, 0, stream.size)
        assertArrayEquals(resource("profile7_fel_to_81.hevc"), result)
    }

    @Test
    fun `drops an RPU that does not read and counts it`() {
        val rpu = resource(
            "mel_orig.bin",
        ).let { it.copyOfRange(4, it.size) }.also { it[24] = (it[24].toInt() xor 1).toByte() }
        val slice = byteArrayOf(0x02, 0x01, 0x11, 0x22, 0x33)
        val sample = START + slice + START + RPU_HEADER + rpu
        val counts = DolbyVisionProfile7Extractors.RpuCounts()

        val result = DolbyVisionProfile7Extractors.toProfile81(sample, 0, sample.size, counts)
        assertArrayEquals(START + slice, result)
        assertEquals(listOf(1, 0, 1), listOf(counts.seen, counts.converted, counts.failed))
    }

    private fun resource(name: String): ByteArray =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("dolby-vision/$name")).use { it.readBytes() }

    private companion object {
        val START = byteArrayOf(0, 0, 0, 1)
        val RPU_HEADER = byteArrayOf(0x7C, 0x01)
    }
}
