package io.github.glacier_jellyfin.androidtv.core.player

import java.io.ByteArrayOutputStream

/**
 * Rewrites a Dolby Vision profile 7 RPU (the metadata NAL unit, type 62) as
 * profile 8.1, the way dovi_tool's mode 2 does (quietvoid/dovi_tool, MIT):
 * no residual from the enhancement layer, the colour coefficients of 8.1,
 * and for a full enhancement layer (FEL) mapping curves that leave the base
 * layer as it is, since its residual no longer arrives.
 *
 * Header and mapping are parsed field by field; the display management
 * metadata after them goes over as it is, apart from its colour coefficients.
 */
internal object DolbyVisionRpu {

    /**
     * The profile 8.1 RPU for [nal], a type 62 NAL unit with its 2-byte
     * header and emulation prevention bytes; null for one this cannot read,
     * which then stays as it is.
     */
    fun toProfile81(nal: ByteArray, offset: Int = 0, length: Int = nal.size - offset): ByteArray? {
        if (length < 3) return null
        val rpu = unescape(nal, offset + 2, length - 2)
        val converted = runCatching { convert(rpu, removeFelMapping = true) }.getOrNull() ?: return null
        return byteArrayOf(nal[offset], nal[offset + 1]) + escape(converted)
    }

    /** [rpu] from its prefix byte 0x19 to the final 0x80 and any trailing zeros, converted; null if it does not read. */
    fun convert(rpu: ByteArray, removeFelMapping: Boolean): ByteArray? {
        val trailingZeros = rpu.reversed().takeWhile { it.toInt() == 0 }.size
        val end = rpu.size - trailingZeros
        if (end < 7 || rpu[end - 1] != FINAL_BYTE) return null
        val crcStart = end - 5
        if (crc32(rpu, 1, crcStart) != BitReader(rpu, crcStart * 8, end * 8).bits(32).toInt()) return null

        val r = BitReader(rpu, 0, crcStart * 8)
        if (r.bits(8).toInt() != 0x19) return null
        val w = BitWriter()
        w.bits(8, 0x19)

        // rpu_data_header
        val rpuType = r.bits(6)
        if (rpuType != 2L) return null
        val rpuFormat = r.bits(11)
        w.bits(6, rpuType)
        w.bits(11, rpuFormat)
        w.bits(8, r.bits(8)) // vdr_rpu_profile, vdr_rpu_level
        val seqInfo = r.bit()
        w.bit(seqInfo)
        var coefficientType = 0L
        var denomLength = 0
        var blBitDepth = 10
        var elBitDepth = 10
        var disableResidual = false
        var compressedDm = false
        if (seqInfo) {
            w.bit(r.bit()) // chroma_resampling_explicit_filter_flag
            coefficientType = r.bits(2)
            w.bits(2, coefficientType)
            if (coefficientType == 0L) {
                val denom = r.ue()
                w.ue(denom)
                denomLength = denom.toInt()
            } else if (coefficientType == 1L) {
                denomLength = 32
            } else {
                return null
            }
            w.bits(3, r.bits(3)) // vdr_rpu_normalized_idc, bl_video_full_range_flag
            if (rpuFormat and 0x700 == 0L) {
                val bl = r.ue()
                val el = r.ue()
                blBitDepth = bl.toInt() + 8
                elBitDepth = (el and 0xff).toInt() + 8
                w.ue(bl)
                w.ue(el)
                w.ue(r.ue()) // vdr_bit_depth_minus8
                w.bit(r.bit()) // spatial_resampling_filter_flag
                val reserved = r.bits(3)
                compressedDm = reserved == 1L
                w.bits(3, reserved)
                r.bit()
                disableResidual = r.bit()
                // Profile 8.1: no enhancement layer resampling, no residual.
                w.bit(false)
                w.bit(true)
            }
        }
        val dmPresent = r.bit()
        w.bit(dmPresent)
        val usePrevious = r.bit()
        w.bit(usePrevious)
        if (usePrevious) w.ue(r.ue())

        // rpu_data_mapping
        if (!usePrevious) {
            repeat(3) { w.ue(r.ue()) } // vdr_rpu_id, mapping_color_space, mapping_chroma_format_idc
            val pieces = IntArray(3)
            val pivots = Array(3) { LongArray(0) }
            for (component in 0 until 3) {
                val count = r.ue().toInt() + 2
                if (count > 9) return null
                pieces[component] = count - 1
                pivots[component] = LongArray(count) { r.bits(blBitDepth) }
            }
            val hasNlq = rpuFormat and 0x700 == 0L && !disableResidual
            if (hasNlq) {
                if (r.bits(3) != 0L) return null // nlq_method_idc: only the linear dead zone exists
                r.bits(blBitDepth)
                r.bits(blBitDepth)
            }
            r.ue()
            r.ue() // num_x_partitions_minus1, num_y_partitions_minus1

            // The curves' coefficients, read to know where they end.
            val curvesStart = r.position
            for (component in 0 until 3) {
                repeat(pieces[component]) {
                    when (r.ue()) {
                        0L -> {
                            val order = r.ue()
                            if (order > 1) return null
                            if (order == 0L && r.bit()) return null // linear interpolation: not in the wild
                            repeat(order.toInt() + 2) { r.coefficient(coefficientType, denomLength) }
                        }
                        1L -> {
                            val order = r.bits(2)
                            if (order > 2) return null
                            r.coefficient(coefficientType, denomLength)
                            repeat((order.toInt() + 1) * 7) { r.coefficient(coefficientType, denomLength) }
                        }
                        else -> return null
                    }
                }
            }
            val curvesEnd = r.position

            var fullEnhancementLayer = false
            if (hasNlq) {
                for (component in 0 until 3) {
                    val nlqOffset = r.bits(elBitDepth)
                    val maxInt = if (coefficientType == 0L) r.ue() else 1L
                    val max = r.bits(denomLength)
                    val slopeInt = if (coefficientType == 0L) r.ue() else 0L
                    val slope = r.bits(denomLength)
                    val thresholdInt = if (coefficientType == 0L) r.ue() else 0L
                    val threshold = r.bits(denomLength)
                    val mel = nlqOffset == 0L && maxInt == 1L && max == 0L && slopeInt == 0L && slope == 0L &&
                        thresholdInt == 0L && threshold == 0L
                    if (!mel) fullEnhancementLayer = true
                }
            }

            if (fullEnhancementLayer && removeFelMapping) {
                // Mapping curves that keep the base layer: one linear piece from 0 to 1023 per component.
                repeat(3) {
                    w.ue(0)
                    w.bits(blBitDepth, 0)
                    w.bits(blBitDepth, 1023)
                }
                w.ue(0)
                w.ue(0)
                repeat(3) {
                    w.ue(0) // polynomial
                    w.ue(0) // first order
                    w.bit(false)
                    if (coefficientType == 0L) w.se(0)
                    w.bits(denomLength, 0)
                    if (coefficientType == 0L) w.se(1)
                    w.bits(denomLength, 0)
                }
            } else {
                for (component in 0 until 3) {
                    w.ue(pieces[component] - 1L)
                    pivots[component].forEach { w.bits(blBitDepth, it) }
                }
                w.ue(0)
                w.ue(0)
                w.copy(rpu, curvesStart, curvesEnd)
            }
        }

        // vdr_dm_data_payload
        if (dmPresent) {
            repeat(3) { w.ue(r.ue()) } // affected_dm_metadata_id, current_dm_metadata_id, scene_refresh_flag
            if (!compressedDm) {
                r.skip(9 * 16 + 3 * 32 + 9 * 16)
                P81_YCC_TO_RGB.forEach { w.bits(16, it.toLong() and 0xffff) }
                P81_YCC_TO_RGB_OFFSETS.forEach { w.bits(32, it) }
                P81_RGB_TO_LMS.forEach { w.bits(16, it.toLong() and 0xffff) }
                w.bits(16 * 3, r.bits(16 * 3)) // signal_eotf, signal_eotf_param0 and 1
                w.bits(32 + 5, r.bits(32 + 5)) // signal_eotf_param2, signal_bit_depth
                r.bits(2)
                w.bits(2, 0) // signal_color_space
                w.bits(2 + 2 + 12 + 12 + 10, r.bits(2 + 2 + 12 + 12 + 10))
            }
            w.ue(r.ue()) // num_ext_blocks of the CM v2.9 metadata
        }
        // Byte aligned from here on in both, so the rest goes over as it is.
        r.alignToByte()
        w.alignToByte()
        w.bytes(rpu, r.position / 8, crcStart)

        val out = w.toByteArray()
        val crc = crc32(out, 1, out.size)
        return out + byteArrayOf((crc ushr 24).toByte(), (crc ushr 16).toByte(), (crc ushr 8).toByte(), crc.toByte(), FINAL_BYTE) +
            ByteArray(trailingZeros)
    }

    /** The NAL payload without emulation prevention bytes (00 00 03 → 00 00). */
    internal fun unescape(data: ByteArray, offset: Int, length: Int): ByteArray {
        val out = ByteArrayOutputStream(length)
        var zeros = 0
        for (i in offset until offset + length) {
            val b = data[i].toInt() and 0xff
            if (zeros >= 2 && b == 3) {
                zeros = 0
                continue
            }
            out.write(b)
            zeros = if (b == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    /** Emulation prevention bytes back in, so no start code appears inside the payload. */
    internal fun escape(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size + data.size / 64 + 4)
        var zeros = 0
        for (byte in data) {
            val b = byte.toInt() and 0xff
            if (zeros >= 2 && b <= 3) {
                out.write(3)
                zeros = 0
            }
            out.write(b)
            zeros = if (b == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    /** CRC-32/MPEG-2 over [from] until [to]. */
    private fun crc32(data: ByteArray, from: Int, to: Int): Int {
        var crc = -1
        for (i in from until to) {
            crc = crc xor ((data[i].toInt() and 0xff) shl 24)
            repeat(8) { crc = if (crc < 0) (crc shl 1) xor 0x04C11DB7 else crc shl 1 }
        }
        return crc
    }

    private fun BitReader.coefficient(type: Long, denomLength: Int) {
        if (type == 0L) se()
        bits(denomLength)
    }

    private const val FINAL_BYTE = 0x80.toByte()
    private val P81_YCC_TO_RGB = intArrayOf(9574, 0, 13802, 9574, -1540, -5348, 9574, 17610, 0)
    private val P81_YCC_TO_RGB_OFFSETS = longArrayOf(16777216, 134217728, 134217728)
    private val P81_RGB_TO_LMS = intArrayOf(7222, 8771, 390, 2654, 12430, 1300, 0, 422, 15962)
}

/** Reads bits MSB first from [data], between bit [position] and bit [limit]. */
private class BitReader(private val data: ByteArray, var position: Int, private val limit: Int) {

    fun bit(): Boolean {
        check(position < limit) { "RPU ends early" }
        val value = (data[position ushr 3].toInt() shr (7 - (position and 7))) and 1
        position++
        return value == 1
    }

    fun bits(count: Int): Long {
        require(count in 0..64)
        var value = 0L
        repeat(count) { value = (value shl 1) or (if (bit()) 1L else 0L) }
        return value
    }

    fun skip(count: Int) {
        check(position + count <= limit) { "RPU ends early" }
        position += count
    }

    fun ue(): Long {
        var zeros = 0
        while (!bit()) {
            if (++zeros > 32) error("Exp-Golomb code too long")
        }
        return (1L shl zeros) - 1 + bits(zeros)
    }

    fun se(): Long {
        val code = ue()
        return if (code and 1L == 1L) (code + 1) / 2 else -(code / 2)
    }

    fun alignToByte() {
        while (position and 7 != 0) bit()
    }
}

/** Writes bits MSB first. */
private class BitWriter {
    private val out = ByteArrayOutputStream()
    private var current = 0
    private var filled = 0

    fun bit(value: Boolean) {
        current = (current shl 1) or (if (value) 1 else 0)
        if (++filled == 8) {
            out.write(current)
            current = 0
            filled = 0
        }
    }

    fun bits(count: Int, value: Long) {
        for (i in count - 1 downTo 0) bit((value ushr i) and 1L == 1L)
    }

    fun ue(value: Long) {
        val code = value + 1
        val length = 64 - java.lang.Long.numberOfLeadingZeros(code)
        bits(length - 1, 0)
        bits(length, code)
    }

    fun se(value: Long) = ue(if (value > 0) value * 2 - 1 else -value * 2)

    /** The bits of [data] from bit [from] to bit [to]. */
    fun copy(data: ByteArray, from: Int, to: Int) {
        for (position in from until to) bit((data[position ushr 3].toInt() shr (7 - (position and 7))) and 1 == 1)
    }

    fun alignToByte() {
        while (filled != 0) bit(false)
    }

    /** Whole bytes, at a byte boundary. */
    fun bytes(data: ByteArray, from: Int, to: Int) {
        check(filled == 0)
        out.write(data, from, to - from)
    }

    fun toByteArray(): ByteArray {
        check(filled == 0)
        return out.toByteArray()
    }
}
