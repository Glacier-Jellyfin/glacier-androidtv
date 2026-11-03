package io.github.glacier_jellyfin.androidtv.core.player

import java.nio.ByteBuffer

/**
 * Packs Dolby TrueHD and DTS-HD into IEC 61937 bursts, as FFmpeg's spdif muxer
 * (libavformat/spdifenc.c) does. Android hands such bursts to HDMI unchanged
 * (AudioFormat.ENCODING_IEC61937), so the receiver gets the lossless stream
 * even where the platform passes on only the core (DTS) or fails (TrueHD).
 *
 * Both use the high bitrate rate of HDMI: 8 channels of 16 bit at 192 kHz,
 * 768 000 IEC 60958 frames of 4 bytes per second.
 */
internal sealed class Iec61937Packer {

    /** Packs the frames in [input], all of it, and appends each completed burst to [output]. */
    abstract fun pack(input: ByteBuffer, output: Iec61937Bursts)

    /** Forgets partial bursts and timing, after a seek. */
    abstract fun reset()

    /** Dolby TrueHD: 24 access units per MAT frame, one MAT frame per burst. */
    class TrueHd : Iec61937Packer() {
        private val frame = ByteArray(MAT_FRAME_SIZE)
        private var filled = 0
        private var samplesPerUnit = 0
        private var previousTiming = 0
        /** The previous access unit's size including MAT codes it ran into; 0 before the first. */
        private var previousSize = 0

        override fun pack(input: ByteBuffer, output: Iec61937Bursts) {
            // Media3 hands several access units per buffer; each starts with its length in 16-bit words.
            while (input.remaining() >= 4) {
                val start = input.position()
                val length = ((input.get(start).toInt() and 0x0f) shl 8 or (input.get(start + 1).toInt() and 0xff)) * 2
                if (length < 10 || length > input.remaining()) {
                    input.position(input.limit())
                    return
                }
                val unit = ByteArray(length)
                input.get(unit)
                packUnit(unit, output)
            }
            input.position(input.limit())
        }

        private fun packUnit(unit: ByteArray, output: Iec61937Bursts) {
            if (unit.u24(4) == 0xf8726f) {
                // A major sync: the sample rate, and with it the samples per access unit.
                val rateBits = when (unit[7].toInt() and 0xff) {
                    0xba -> (unit[8].toInt() and 0xff) shr 4
                    0xbb -> (unit[9].toInt() and 0xff) shr 4
                    else -> return
                }
                samplesPerUnit = 40 shl (rateBits and 3)
            }
            if (samplesPerUnit == 0) return

            val timing = unit.u16(2)
            var totalSize = unit.size
            var padding = 0
            if (previousSize != 0) {
                // One access unit at a multiple of 48 kHz lasts 1/1200 s, 2560 bytes at the IEC 61937 rate.
                val deltaSamples = (timing - previousTiming) and 0xffff
                padding = deltaSamples * 2560 / samplesPerUnit - previousSize
                if (padding < 0 || padding >= MAT_FRAME_SIZE / 2) padding = 0
            }

            var code = MAT_CODES.indexOfFirst { filled <= it.position }
            var data = 0
            while (padding > 0 || data < unit.size || MAT_CODES[code].position == filled) {
                if (MAT_CODES[code].position == filled) {
                    val matCode = MAT_CODES[code]
                    matCode.bytes.copyInto(frame, filled)
                    filled += matCode.bytes.size
                    var codeRemaining = matCode.bytes.size
                    code++
                    if (code == MAT_CODES.size) {
                        // The end code completes the MAT frame.
                        code = 0
                        output.add(TYPE_TRUEHD, MAT_FRAME_SIZE, MAT_PKT_OFFSET, frame, MAT_FRAME_SIZE)
                        filled = 0
                        // The gap between MAT frames counts as well.
                        codeRemaining += MAT_PKT_OFFSET - MAT_FRAME_SIZE
                    }
                    if (padding > 0) {
                        val asPadding = minOf(padding, codeRemaining)
                        padding -= asPadding
                        codeRemaining -= asPadding
                    }
                    totalSize += codeRemaining
                }
                if (padding > 0) {
                    val insert = minOf(MAT_CODES[code].position - filled, padding)
                    frame.fill(0, filled, filled + insert)
                    filled += insert
                    padding -= insert
                    if (padding > 0) continue
                }
                if (data < unit.size) {
                    val insert = minOf(MAT_CODES[code].position - filled, unit.size - data)
                    unit.copyInto(frame, filled, data, data + insert)
                    filled += insert
                    data += insert
                }
            }
            previousSize = totalSize
            previousTiming = timing
        }

        /** Completes a started MAT frame with padding, so the last access units play at the end of the stream. */
        fun finish(output: Iec61937Bursts) {
            if (filled == 0) return
            var code = MAT_CODES.indexOfFirst { filled <= it.position }
            while (true) {
                val matCode = MAT_CODES[code]
                frame.fill(0, filled, matCode.position)
                matCode.bytes.copyInto(frame, matCode.position)
                filled = matCode.position + matCode.bytes.size
                if (++code == MAT_CODES.size) break
            }
            output.add(TYPE_TRUEHD, MAT_FRAME_SIZE, MAT_PKT_OFFSET, frame, MAT_FRAME_SIZE)
            reset()
        }

        override fun reset() {
            filled = 0
            samplesPerUnit = 0
            previousTiming = 0
            previousSize = 0
        }

        private class MatCode(val position: Int, vararg bytes: Int) {
            val bytes = ByteArray(bytes.size) { bytes[it].toByte() }
        }

        private companion object {
            const val MAT_PKT_OFFSET = 61440
            const val MAT_FRAME_SIZE = 61424

            val MAT_CODES = listOf(
                MatCode(
                    0,
                    0x07, 0x9E, 0x00, 0x03, 0x84, 0x01, 0x01, 0x01, 0x80, 0x00, 0x56, 0xA5, 0x3B, 0xF4, 0x81, 0x83,
                    0x49, 0x80, 0x77, 0xE0,
                ),
                MatCode(30708, 0xC3, 0xC1, 0x42, 0x49, 0x3B, 0xFA, 0x82, 0x83, 0x49, 0x80, 0x77, 0xE0),
                MatCode(
                    MAT_FRAME_SIZE - 16,
                    0xC3, 0xC2, 0xC0, 0xC4, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x97, 0x11,
                ),
            )
        }
    }

    /**
     * DTS-HD: each whole frame, core and extension, in one type IV burst. When a
     * frame does not fit, only the core goes for a minute, as FFmpeg does.
     */
    class DtsHd : Iec61937Packer() {
        private var payload = ByteArray(0)
        /** Frames left to send as core only after one that did not fit. */
        private var coreOnly = 0

        override fun pack(input: ByteBuffer, output: Iec61937Bursts) {
            val frame = ByteArray(input.remaining())
            input.get(frame)
            packFrame(frame, output)
        }

        private fun packFrame(frame: ByteArray, output: Iec61937Bursts) {
            // Only big-endian frames with a core carry the sizes the burst needs; Matroska and MP4 hold those.
            if (frame.size < 9 || frame.u32(0) != CORE_SYNCWORD) return
            val blocks = ((frame.u16(4) shr 2) and 0x7f) + 1
            val coreSize = ((frame.u24(5) shr 4) and 0x3fff) + 1
            val sampleRate = SAMPLE_RATES[(frame[8].toInt() shr 2) and 0x0f]
            if (sampleRate == 0) return

            val period = HBR_RATE * (blocks shl 5) / sampleRate
            val subtype = SUBTYPES[period] ?: return
            val burstSize = period * 4
            var size = frame.size
            if (START_CODE.size + 2 + size > burstSize - BURST_HEADER_SIZE) {
                coreOnly = sampleRate * FALLBACK_SECONDS / (blocks shl 5)
            }
            if (coreOnly > 0) {
                size = coreSize
                coreOnly--
            }

            val length = START_CODE.size + 2 + size
            if (payload.size < length) payload = ByteArray(length)
            START_CODE.copyInto(payload)
            payload[START_CODE.size] = (size shr 8).toByte()
            payload[START_CODE.size + 1] = size.toByte()
            frame.copyInto(payload, START_CODE.size + 2, 0, size)
            // Aligned so that (length code & 0xf) == 0x8, which some receivers reportedly need.
            val lengthCode = ((length + 0x8 + 0xf) and 0xf.inv()) - 0x8
            output.add(TYPE_DTSHD or (subtype shl 8), lengthCode, burstSize, payload, length)
        }

        override fun reset() {
            coreOnly = 0
        }

        private companion object {
            const val CORE_SYNCWORD = 0x7FFE8001L
            const val HBR_RATE = 768000
            const val FALLBACK_SECONDS = 60
            val START_CODE = byteArrayOf(0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0xfe.toByte(), 0xfe.toByte())
            /** Repetition period in IEC 60958 frames, to the type IV subtype. */
            val SUBTYPES = mapOf(512 to 0x0, 1024 to 0x1, 2048 to 0x2, 4096 to 0x3, 8192 to 0x4, 16384 to 0x5)
            val SAMPLE_RATES = intArrayOf(
                0, 8000, 16000, 32000, 0, 0, 11025, 22050, 44100, 0, 0, 12000, 24000, 48000, 96000, 192000,
            )
        }
    }

    protected companion object {
        const val TYPE_DTSHD = 0x11
        const val TYPE_TRUEHD = 0x16
        const val BURST_HEADER_SIZE = 8

        fun ByteArray.u16(at: Int) = (this[at].toInt() and 0xff) shl 8 or (this[at + 1].toInt() and 0xff)
        fun ByteArray.u24(at: Int) = u16(at) shl 8 or (this[at + 2].toInt() and 0xff)
        fun ByteArray.u32(at: Int) = (u16(at).toLong() shl 16) or u16(at + 2).toLong()
    }
}

/**
 * Bursts waiting for the audio output, as 16-bit little-endian words: the
 * burst header, the big-endian payload swapped word by word, zeros up to the
 * repetition period.
 */
internal class Iec61937Bursts {
    private var bytes = ByteArray(0)

    /** Bytes waiting. */
    var size = 0
        private set

    fun add(type: Int, lengthCode: Int, burstSize: Int, payload: ByteArray, length: Int) {
        ensureCapacity(size + burstSize)
        val start = size
        putWord(SYNCWORD1)
        putWord(SYNCWORD2)
        putWord(type)
        putWord(lengthCode)
        var i = 0
        while (i + 1 < length) {
            bytes[size++] = payload[i + 1]
            bytes[size++] = payload[i]
            i += 2
        }
        // A last lone byte goes in the upper half of a word.
        if (length and 1 != 0) putWord((payload[length - 1].toInt() and 0xff) shl 8)
        val end = start + burstSize
        bytes.fill(0, size, end)
        size = end
    }

    /** The waiting bytes, for writing; [consume] then drops what was written. */
    fun buffer(): ByteBuffer = ByteBuffer.wrap(bytes, 0, size)

    fun consume(count: Int) {
        bytes.copyInto(bytes, 0, count, size)
        size -= count
    }

    fun clear() {
        size = 0
    }

    private fun putWord(value: Int) {
        bytes[size++] = value.toByte()
        bytes[size++] = (value shr 8).toByte()
    }

    private fun ensureCapacity(capacity: Int) {
        if (bytes.size < capacity) bytes = bytes.copyOf(maxOf(capacity, bytes.size * 2))
    }

    private companion object {
        const val SYNCWORD1 = 0xF872
        const val SYNCWORD2 = 0x4E1F
    }
}
