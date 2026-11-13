package io.github.glacier_jellyfin.androidtv.fuzz

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import io.github.glacier_jellyfin.androidtv.core.player.DolbyVisionRpu

/**
 * Dolby Vision RPUs from the video file, rewritten from profile 7 to 8.1
 * (DolbyVisionRpu.kt). The input is the RPU NAL unit with its header,
 * followed by one byte that chooses whether a full enhancement layer's
 * mapping goes.
 */
object DolbyVisionRpuFuzzer {

    @JvmStatic
    fun fuzzerTestOneInput(data: FuzzedDataProvider) {
        val removeFelMapping = data.consumeBoolean()
        val nal = data.consumeRemainingAsBytes()

        // What the player calls: must give null for anything it cannot read, never throw.
        DolbyVisionRpu.toProfile81(nal)

        val escaped = DolbyVisionRpu.escape(nal)
        check(DolbyVisionRpu.unescape(escaped, 0, escaped.size).contentEquals(nal)) { "escape does not undo" }
        check(!hasStartCode(escaped)) { "escape leaves a start code" }

        if (nal.size < NAL_HEADER_SIZE) return
        // Mutations rarely keep the checksum right, so it is set here to get them past the check.
        val rpu = withChecksum(DolbyVisionRpu.unescape(nal, NAL_HEADER_SIZE, nal.size - NAL_HEADER_SIZE))
        val converted = convert(rpu, removeFelMapping) ?: return
        check(converted.last() == FINAL_BYTE || converted.last() == ZERO) { "no final byte" }
        // A converted RPU is profile 8.1 already: converting it again changes nothing. Not for one without
        // sequence info, which relies on the header of an earlier RPU: read as profile 7, it is not 8.1 again.
        if (!hasSequenceInfo(rpu)) return
        val again = convert(converted, removeFelMapping)
        check(again != null && again.contentEquals(converted)) { "a second conversion changes the RPU" }
    }

    /** The conversion; it throws IllegalStateException or IllegalArgumentException where the RPU ends early. */
    private fun convert(rpu: ByteArray, removeFelMapping: Boolean): ByteArray? = try {
        DolbyVisionRpu.convert(rpu, removeFelMapping)
    } catch (_: IllegalStateException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    /** vdr_seq_info_present_flag, after the prefix, rpu_type, rpu_format, profile and level. */
    private fun hasSequenceInfo(rpu: ByteArray): Boolean =
        (rpu[SEQ_INFO_BIT / Byte.SIZE_BITS].toInt() shr (Byte.SIZE_BITS - 1 - SEQ_INFO_BIT % Byte.SIZE_BITS)) and 1 == 1

    private fun hasStartCode(data: ByteArray): Boolean = (0 until data.size - 2).any {
        data[it] == ZERO && data[it + 1] == ZERO && data[it + 2] in 0..2
    }

    /** [rpu] with the CRC-32/MPEG-2 the converter expects before the final 0x80, where it has room for one. */
    private fun withChecksum(rpu: ByteArray): ByteArray {
        val end = rpu.size - rpu.reversed().takeWhile { it == ZERO }.size
        if (end < MIN_RPU_SIZE || rpu[end - 1] != FINAL_BYTE) return rpu
        val crcStart = end - CRC_SIZE - 1
        var crc = -1
        for (i in 1 until crcStart) {
            crc = crc xor ((rpu[i].toInt() and BYTE_MASK) shl CRC_TOP_BYTE)
            repeat(Byte.SIZE_BITS) { crc = if (crc < 0) (crc shl 1) xor CRC_POLYNOMIAL else crc shl 1 }
        }
        return rpu.copyOf().also { out ->
            repeat(CRC_SIZE) { out[crcStart + it] = (crc ushr (CRC_TOP_BYTE - it * Byte.SIZE_BITS)).toByte() }
        }
    }

    private const val NAL_HEADER_SIZE = 2
    private const val SEQ_INFO_BIT = 8 + 6 + 11 + 8
    private const val MIN_RPU_SIZE = 7
    private const val CRC_SIZE = 4
    private const val CRC_TOP_BYTE = 24
    private const val CRC_POLYNOMIAL = 0x04C11DB7
    private const val BYTE_MASK = 0xff
    private const val FINAL_BYTE = 0x80.toByte()
    private const val ZERO: Byte = 0
}
