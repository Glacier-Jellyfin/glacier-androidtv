package io.github.glacier_jellyfin.androidtv.core.player

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Against dovi_tool's own conversions (src/test/resources/dolby-vision/README.md). */
class DolbyVisionRpuTest {

    @Test
    fun `converts a minimal enhancement layer RPU like dovi_tool`() {
        assertArrayEquals(rpu("mel_to_81.bin"), DolbyVisionRpu.convert(rpu("mel_orig.bin"), removeFelMapping = true))
    }

    @Test
    fun `converts a full enhancement layer RPU like dovi_tool with the mapping kept`() {
        assertArrayEquals(rpu("fel_to_81.bin"), DolbyVisionRpu.convert(rpu("fel_orig.bin"), removeFelMapping = false))
    }

    @Test
    fun `leaves the base layer as it is for a full enhancement layer like dovi_tool mode 2`() {
        val converted = assertNotNull(DolbyVisionRpu.convert(rpu("fel_orig.bin"), removeFelMapping = true))
        assertArrayEquals(rpu("fel_to_81_mapping_removed.bin"), converted)
        // A valid RPU again, whose own conversion changes nothing more.
        assertArrayEquals(converted, DolbyVisionRpu.convert(converted, removeFelMapping = true))
    }

    @Test
    fun `refuses an RPU whose checksum does not match`() {
        val broken = rpu("mel_orig.bin").also { it[20] = (it[20].toInt() xor 1).toByte() }
        assertNull(DolbyVisionRpu.convert(broken, removeFelMapping = true))
    }

    @Test
    fun `refuses an RPU whose pivot count overflows`() {
        // The fuzzer's find, with a matching checksum: a pivot count past Int.MAX_VALUE.
        val rpu = (
            "190809004061b650001b0000020000036000000020000000c200000000ffffffffc2c2c2c2c2c2c2c2c2c2c238c2c2c2" +
                "c2c2c2c2c2c2c2c2c2c2fc000000000000000303600000002000e7fa8ffa8ffa8d0ae7fffc00000000000000019081f7" +
                "380546300803180169b9997b41282180088ffa8fb6854100020200000008050000fdf900420300006400000000120c1f" +
                "400064000000008ebc2bc480"
            ).chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertNull(DolbyVisionRpu.convert(rpu, removeFelMapping = true))
        assertNull(DolbyVisionRpu.convert(rpu, removeFelMapping = false))
    }

    @Test
    fun `rewrites the NAL unit with its header and emulation prevention`() {
        val file = resource("mel_orig.bin")
        val nal = byteArrayOf(0x7C, 0x01) + file.copyOfRange(4, file.size)
        val converted = assertNotNull(DolbyVisionRpu.toProfile81(nal))
        val expected = resource("mel_to_81.bin")
        assertArrayEquals(byteArrayOf(0x7C, 0x01) + expected.copyOfRange(4, expected.size), converted)
    }

    /** The RPU of a fixture: after the start code, without emulation prevention. */
    private fun rpu(name: String): ByteArray {
        val file = resource(name)
        return DolbyVisionRpu.unescape(file, 4, file.size - 4)
    }

    private fun resource(name: String): ByteArray =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("dolby-vision/$name")).use { it.readBytes() }

    private fun <T> assertNotNull(value: T?): T {
        assertNotNull(null, value)
        return value!!
    }
}
