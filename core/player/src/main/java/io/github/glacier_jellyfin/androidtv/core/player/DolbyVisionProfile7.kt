package io.github.glacier_jellyfin.androidtv.core.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.CodecSpecificDataUtil
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.NalUnitUtil
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.TrackOutput
import io.github.glacier_jellyfin.androidtv.core.log.Log
import java.io.ByteArrayOutputStream
import java.io.EOFException

/** What the player does with Dolby Vision profile 7 (UHD Blu-ray), which most decoders lack. */
enum class DolbyVisionProfile7 {
    /** The decoder takes profile 7 itself. */
    AsIs,

    /**
     * Rewritten as profile 8.1 for a Dolby Vision decoder with profile 8: the
     * picture stays Dolby Vision, without the enhancement layer.
     */
    Profile81,

    /** Its HDR10 base layer through the HEVC decoder. */
    BaseLayer,
}

/**
 * Plays Dolby Vision profile 7 the way [mode] says. Media3 hands profile 7 to
 * a Dolby Vision decoder whether or not it declares the profile, and Amlogic
 * decoders fail on it.
 *
 * [DolbyVisionProfile7.BaseLayer] only changes the format: the base layer is
 * plain HDR10 HEVC, and HEVC decoders skip the enhancement layer.
 * [DolbyVisionProfile7.Profile81] also rewrites every sample: the
 * enhancement layer's NAL units (type 63) go, and each RPU (type 62) becomes
 * profile 8.1 ([DolbyVisionRpu]). The first sample decides: without an RPU
 * that converts, or encrypted, the track plays its base layer instead, so
 * the decoder never gets a profile 8.1 stream without 8.1 metadata.
 */
@OptIn(UnstableApi::class)
internal class DolbyVisionProfile7Extractors(
    private val inner: ExtractorsFactory,
    private val mode: DolbyVisionProfile7,
) : ExtractorsFactory by inner {

    override fun createExtractors(): Array<Extractor> = inner.createExtractors().map(::Profile7Extractor).toTypedArray()

    override fun createExtractors(uri: android.net.Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        inner.createExtractors(uri, responseHeaders).map(::Profile7Extractor).toTypedArray()

    private inner class Profile7Extractor(private val inner: Extractor) : Extractor by inner {
        private var output: Profile7Output? = null

        override fun init(output: ExtractorOutput) {
            val tracks = Profile7Output(output).also { this.output = it }
            // A dual layer MKV can keep its RPUs in block additions.
            (inner.underlyingImplementation as? BlockAdditionSource)?.blockAdditions =
                {
                    tracks.byId[it.trackNumber]?.onBlockAddition(it)
                }
            inner.init(tracks)
        }

        override fun getUnderlyingImplementation(): Extractor = inner.underlyingImplementation

        // After a seek the extractor starts over; a sample begun before it never completes.
        override fun seek(position: Long, timeUs: Long) {
            output?.byId?.values?.forEach { it.dropPending() }
            inner.seek(position, timeUs)
        }

        override fun release() {
            output?.byId?.values?.forEach { it.logSummary() }
            inner.release()
        }
    }

    private inner class Profile7Output(private val inner: ExtractorOutput) : ExtractorOutput by inner {
        val byId = mutableMapOf<Int, Profile7Track>()

        override fun track(id: Int, type: Int): TrackOutput = Profile7Track(inner.track(id, type), mode)
            .also { byId[id] = it }
    }

    /** RPUs met by a converted track, for the log. */
    class RpuCounts {
        var seen = 0
        var converted = 0

        /** Unreadable ones; they are dropped, the decoder keeps the metadata before them. */
        var failed = 0

        override fun toString() = "$seen RPUs, $converted converted, $failed dropped"
    }

    /**
     * One track. A profile 7 video track to convert holds its format back
     * until the first sample shows whether it converts, and its samples wait
     * in [pending] until complete, with every part Media3 writes.
     */
    private class Profile7Track(private val inner: TrackOutput, private val mode: DolbyVisionProfile7) :
        TrackOutput by inner {
        private var converting = false

        /** The profile 7 format, until the first sample decides how it plays. */
        private var held: Format? = null
        private var pending = ByteArray(0)
        private var pendingSize = 0
        private val counts = RpuCounts()

        /** The RPU from the block addition of the coming sample, a NAL unit with its header. */
        private var additionRpu = ByteArray(0)
        private var additionRpuSize = 0

        override fun format(format: Format) {
            val profile7 = format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION && format.codecs.isProfile7()
            when {
                !profile7 -> inner.format(format)

                mode == DolbyVisionProfile7.BaseLayer -> inner.format(baseLayer(format))

                mode == DolbyVisionProfile7.Profile81 && converting -> inner.format(profile81(format))

                mode == DolbyVisionProfile7.Profile81 -> {
                    held = format
                    converting = true
                }

                else -> inner.format(format)
            }
        }

        override fun sampleData(
            input: DataReader,
            length: Int,
            allowEndOfInput: Boolean,
            sampleDataPart: Int,
        ): Int {
            if (!converting) return inner.sampleData(input, length, allowEndOfInput, sampleDataPart)
            ensureCapacity(pendingSize + length)
            val read = input.read(pending, pendingSize, length)
            if (read == C.RESULT_END_OF_INPUT) {
                if (allowEndOfInput) return C.RESULT_END_OF_INPUT
                throw EOFException()
            }
            pendingSize += read
            return read
        }

        override fun sampleData(
            data: ParsableByteArray,
            length: Int,
            sampleDataPart: Int,
        ) {
            if (!converting) {
                inner.sampleData(data, length, sampleDataPart)
                return
            }
            ensureCapacity(pendingSize + length)
            data.data.copyInto(pending, pendingSize, data.position, data.position + length)
            data.skipBytes(length)
            pendingSize += length
        }

        override fun sampleMetadata(
            timeUs: Long,
            flags: Int,
            size: Int,
            offset: Int,
            cryptoData: TrackOutput.CryptoData?,
        ) {
            if (!converting) {
                inner.sampleMetadata(timeUs, flags, size, offset, cryptoData)
                return
            }
            // The sample ends [offset] bytes before what has arrived; those bytes belong to the next one.
            val end = pendingSize - offset
            val start = end - size
            val sample = if (cryptoData == null) convert(start, end, flags) else null
            additionRpuSize = 0
            held?.let { format ->
                held = null
                val reason = when {
                    sample == null -> "the samples are encrypted"
                    counts.converted == 0 && counts.seen == 0 -> "the stream has no RPU"
                    counts.converted == 0 -> "its RPU does not read"
                    else -> null
                }
                if (reason != null) {
                    // The base layer as it is, also for every later sample.
                    Log.i(TAG, "Dolby Vision profile 7 plays as its HDR10 base layer: $reason")
                    converting = false
                    inner.format(baseLayer(format))
                    inner.sampleData(
                        ParsableByteArray(pending.copyOfRange(start, pendingSize)),
                        pendingSize - start,
                        TrackOutput.SAMPLE_DATA_PART_MAIN,
                    )
                    inner.sampleMetadata(timeUs, flags, size, offset, cryptoData)
                    pendingSize = 0
                    return
                }
                Log.i(TAG, "Dolby Vision profile 7 plays as profile 8.1, without the enhancement layer")
                inner.format(profile81(format))
            }
            val out = sample ?: pending.copyOfRange(start, end)
            inner.sampleData(ParsableByteArray(out), out.size, TrackOutput.SAMPLE_DATA_PART_MAIN)
            inner.sampleMetadata(timeUs, flags, out.size, 0, cryptoData)
            pending.copyInto(pending, 0, end, pendingSize)
            pendingSize = offset
        }

        /**
         * The sample in [pending] from [start] to [end] as profile 8.1. A
         * sample with block additions ([C.BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA])
         * starts with the length of its video data, and their data follows.
         */
        private fun convert(
            start: Int,
            end: Int,
            flags: Int,
        ): ByteArray {
            val wrapped = flags and C.BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA != 0
            val dataStart = if (wrapped) start + 4 else start
            val dataEnd = if (wrapped) dataStart + readLength(pending, start) else end
            check(dataEnd in dataStart..end) { "A sample's video data runs past the sample" }
            val seen = counts.seen
            var sample = toProfile81(pending, dataStart, dataEnd - dataStart, counts)
            // Without an RPU of its own, the one from the block addition ends the access unit.
            if (counts.seen == seen && additionRpuSize > 0) sample += convertAdditionRpu()
            return if (wrapped) lengthOf(sample.size) + sample + pending.copyOfRange(dataEnd, end) else sample
        }

        private fun convertAdditionRpu(): ByteArray {
            counts.seen++
            val rpu = DolbyVisionRpu.toProfile81(additionRpu, 0, additionRpuSize)
            if (rpu == null) {
                if (counts.failed++ == 0) Log.w(TAG, "Dropped an RPU that does not read as profile 7")
                return ByteArray(0)
            }
            counts.converted++
            return START_CODE + rpu
        }

        /** Keeps the RPU of a block addition (dual layer MKV) for the sample it belongs to; the enhancement layer goes. */
        fun onBlockAddition(addition: BlockAddition) {
            if (!converting) return
            val rpu = findRpu(addition.data, addition.size, addition.lengthSize) ?: return
            if (additionRpu.size < rpu.count()) additionRpu = ByteArray(rpu.count())
            addition.data.copyInto(additionRpu, 0, rpu.first, rpu.last + 1)
            additionRpuSize = rpu.count()
        }

        fun dropPending() {
            pendingSize = 0
            additionRpuSize = 0
        }

        fun logSummary() {
            if (counts.seen > 0) Log.i(TAG, "Dolby Vision profile 8.1: $counts")
        }

        private fun ensureCapacity(capacity: Int) {
            if (pending.size < capacity) pending = pending.copyOf(maxOf(capacity, pending.size * 2, 256 * 1024))
        }
    }

    companion object {
        /** Profile 7 as the HEVC stream it carries; every other format as it is. */
        fun baseLayer(format: Format): Format =
            if (format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION && format.codecs.isProfile7()) {
                format.buildUpon().setSampleMimeType(MimeTypes.VIDEO_H265).setCodecs(hevcCodecs(format)).build()
            } else {
                format
            }

        /** "dvhe.07.06" or "dvh1.07.06". */
        private fun String?.isProfile7() = this?.split('.')?.getOrNull(1)?.toIntOrNull() == 7

        /** "dvhe.07.06" → "dvhe.08.06": same level, profile 8. */
        private fun profile81(format: Format): Format = format.buildUpon().setCodecs(
            format.codecs!!.split('.').toMutableList().also { it[1] = "08" }.joinToString("."),
        ).build()

        /**
         * One Annex B sample (start codes, as Media3's extractors write H.265)
         * without the enhancement layer and with profile 8.1 RPUs; an RPU that
         * does not read goes too. [counts] learns what it met.
         */
        fun toProfile81(
            data: ByteArray,
            offset: Int,
            length: Int,
            counts: RpuCounts = RpuCounts(),
        ): ByteArray {
            val end = offset + length
            val out = ByteArrayOutputStream(length)
            var nal = nextNal(data, offset, end) ?: return data.copyOfRange(offset, end)
            // Anything before the first start code goes over as it is.
            out.write(data, offset, nal.first - offset)
            while (true) {
                val (codeStart, payloadStart) = nal
                val next = nextNal(data, payloadStart, end)
                val payloadEnd = next?.first ?: end
                when ((data[payloadStart].toInt() shr 1) and 0x3f) {
                    NAL_ENHANCEMENT_LAYER -> Unit

                    NAL_RPU -> {
                        counts.seen++
                        val rpu = DolbyVisionRpu.toProfile81(data, payloadStart, payloadEnd - payloadStart)
                        if (rpu != null) {
                            counts.converted++
                            out.write(data, codeStart, payloadStart - codeStart)
                            out.write(rpu)
                        } else {
                            if (counts.failed++ == 0) Log.w(TAG, "Dropped an RPU that does not read as profile 7")
                        }
                    }

                    else -> out.write(data, codeStart, payloadEnd - codeStart)
                }
                nal = next ?: break
            }
            return out.toByteArray()
        }

        /** The next start code from [from]: where it starts and where its NAL unit begins; null if none. */
        private fun nextNal(
            data: ByteArray,
            from: Int,
            end: Int,
        ): Pair<Int, Int>? {
            var i = from
            while (i + 3 <= end) {
                if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) {
                    val codeStart = if (i > from && data[i - 1].toInt() == 0) i - 1 else i
                    return if (i + 3 < end) codeStart to i + 3 else null
                }
                i++
            }
            return null
        }

        /**
         * The RPU (type 62, with its header) among [size] bytes of NAL units
         * that each follow a length of [lengthSize] bytes, as a block addition
         * holds them; null without one or when the lengths do not add up.
         */
        fun findRpu(
            data: ByteArray,
            size: Int,
            lengthSize: Int,
        ): IntRange? {
            if (lengthSize !in 1..4) return null
            var position = 0
            while (position + lengthSize <= size) {
                var length = 0
                for (i in 0 until lengthSize) length = (length shl 8) or (data[position + i].toInt() and 0xff)
                val start = position + lengthSize
                if (length <= 0 || start + length > size) return null
                if ((data[start].toInt() shr 1) and 0x3f == NAL_RPU) return start until start + length
                position = start + length
            }
            return null
        }

        /** The 4-byte length Media3 puts before a sample with block additions. */
        private fun readLength(data: ByteArray, offset: Int): Int =
            (data[offset].toInt() and 0xff shl 24) or (data[offset + 1].toInt() and 0xff shl 16) or
                (data[offset + 2].toInt() and 0xff shl 8) or (data[offset + 3].toInt() and 0xff)

        private fun lengthOf(length: Int) = byteArrayOf(
            (length ushr 24).toByte(),
            (length ushr 16).toByte(),
            (length ushr 8).toByte(),
            length.toByte(),
        )

        private val START_CODE = byteArrayOf(0, 0, 0, 1)
        private const val TAG = "DolbyVision"
        private const val NAL_RPU = 62
        private const val NAL_ENHANCEMENT_LAYER = 63

        /**
         * "hvc1.2.4.L153.B0" from the base layer's SPS, so Media3 checks profile
         * and level against the HEVC decoder; null when there is no SPS to read.
         */
        private fun hevcCodecs(format: Format): String? = runCatching {
            // The initialization data holds VPS, SPS and PPS, each after a start code.
            val csd = format.initializationData.firstOrNull() ?: return null
            var start = NalUnitUtil.findNalUnit(csd, 0, csd.size, BooleanArray(3))
            while (start < csd.size) {
                val next = NalUnitUtil.findNalUnit(csd, start + 3, csd.size, BooleanArray(3))
                if (NalUnitUtil.getH265NalUnitType(csd, start) == NalUnitUtil.H265_NAL_UNIT_TYPE_SPS) {
                    val level = NalUnitUtil.parseH265SpsNalUnit(
                        csd,
                        start + 3,
                        next,
                        null,
                    ).profileTierLevel ?: return null
                    return CodecSpecificDataUtil.buildHevcCodecString(
                        level.generalProfileSpace,
                        level.generalTierFlag,
                        level.generalProfileIdc,
                        level.generalProfileCompatibilityFlags,
                        level.constraintBytes,
                        level.generalLevelIdc,
                    )
                }
                start = next
            }
            null
        }.getOrNull()
    }
}
