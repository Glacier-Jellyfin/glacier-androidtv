package io.github.glacier_jellyfin.androidtv.core.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Profile 7 as 8.1 from samples the way Media3's Matroska extractor writes them. */
@OptIn(UnstableApi::class)
class DolbyVisionDualLayerTest {

    private val source = FakeExtractor()
    private val output = FakeOutput()

    init {
        DolbyVisionProfile7Extractors(ExtractorsFactory { arrayOf(source) }, DolbyVisionProfile7.Profile81)
            .createExtractors()
            .single()
            .init(output)
    }

    private val track: TrackOutput by lazy {
        source.output.track(1, C.TRACK_TYPE_VIDEO).also {
            it.format(Format.Builder().setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION).setCodecs("dvhe.07.06").build())
        }
    }

    @Test
    fun `takes the RPU of a dual layer MKV from the block addition`() {
        val slice = START + SLICE
        // Media3 puts the length of the video data first when a track has block additions.
        track.sampleData(ParsableByteArray(lengthOf(slice.size)), 4, TrackOutput.SAMPLE_DATA_PART_SUPPLEMENTAL)
        track.sampleData(ParsableByteArray(slice), slice.size, TrackOutput.SAMPLE_DATA_PART_MAIN)
        // The block addition: the enhancement layer and the RPU, each after a 4-byte length.
        source.add(lengthOf(EL.size) + EL + lengthOf(RPU_HEADER.size + rpu.size) + RPU_HEADER + rpu)
        track.sampleMetadata(0, C.BUFFER_FLAG_KEY_FRAME or C.BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA, 4 + slice.size, 0, null)

        val converted = slice + START + RPU_HEADER + convertedRpu
        assertEquals("dvhe.08.06", output.track.format?.codecs)
        assertArrayEquals(lengthOf(converted.size) + converted, output.track.samples.single())
    }

    @Test
    fun `keeps the RPU of the sample itself and drops its enhancement layer`() {
        val sample = START + SLICE + START + byteArrayOf(0x7E, 0x01, 0x44) + START + RPU_HEADER + rpu
        track.sampleData(ParsableByteArray(lengthOf(sample.size)), 4, TrackOutput.SAMPLE_DATA_PART_SUPPLEMENTAL)
        track.sampleData(ParsableByteArray(sample), sample.size, TrackOutput.SAMPLE_DATA_PART_MAIN)
        // Bytes of the next sample, already written.
        track.sampleData(ParsableByteArray(byteArrayOf(1, 2, 3)), 3, TrackOutput.SAMPLE_DATA_PART_MAIN)
        track.sampleMetadata(
            0,
            C.BUFFER_FLAG_KEY_FRAME or C.BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA,
            4 + sample.size,
            3,
            null,
        )

        val converted = START + SLICE + START + RPU_HEADER + convertedRpu
        assertArrayEquals(lengthOf(converted.size) + converted, output.track.samples.single())
    }

    @Test
    fun `plays the base layer when neither the sample nor a block addition has an RPU`() {
        val slice = START + SLICE
        track.sampleData(ParsableByteArray(lengthOf(slice.size)), 4, TrackOutput.SAMPLE_DATA_PART_SUPPLEMENTAL)
        track.sampleData(ParsableByteArray(slice), slice.size, TrackOutput.SAMPLE_DATA_PART_MAIN)
        source.add(lengthOf(EL.size) + EL)
        track.sampleMetadata(0, C.BUFFER_FLAG_KEY_FRAME or C.BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA, 4 + slice.size, 0, null)

        assertEquals(MimeTypes.VIDEO_H265, output.track.format?.sampleMimeType)
        assertArrayEquals(lengthOf(slice.size) + slice, output.track.samples.single())
    }

    @Test
    fun `finds the RPU among the NAL units of a block addition`() {
        val addition = lengthOf(EL.size) + EL + lengthOf(3) + byteArrayOf(0x7C, 0x01, 0x19)
        assertEquals(EL.size + 8 until addition.size, DolbyVisionProfile7Extractors.findRpu(addition, addition.size, 4))
        assertNull(DolbyVisionProfile7Extractors.findRpu(addition, addition.size - 1, 4))
        assertNull(DolbyVisionProfile7Extractors.findRpu(lengthOf(EL.size) + EL, EL.size + 4, 4))
    }

    private val rpu = resource("mel_orig.bin").let { it.copyOfRange(4, it.size) }
    private val convertedRpu = resource("mel_to_81.bin").let { it.copyOfRange(4, it.size) }

    private fun resource(name: String): ByteArray =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("dolby-vision/$name")).use { it.readBytes() }

    private fun lengthOf(length: Int) = byteArrayOf(
        (length ushr 24).toByte(),
        (length ushr 16).toByte(),
        (length ushr 8).toByte(),
        length.toByte(),
    )

    /** Stands in for the Matroska extractor: the test writes through its output and hands on block additions. */
    private class FakeExtractor :
        Extractor,
        BlockAdditionSource {
        override var blockAdditions: ((BlockAddition) -> Unit)? = null
        lateinit var output: ExtractorOutput

        fun add(data: ByteArray) {
            blockAdditions?.invoke(
                BlockAddition().also {
                    it.trackNumber = 1
                    it.lengthSize = 4
                    it.data = data
                    it.size = data.size
                },
            )
        }

        override fun sniff(input: ExtractorInput) = true
        override fun init(output: ExtractorOutput) {
            this.output = output
        }
        override fun read(input: ExtractorInput, seekPosition: PositionHolder) = Extractor.RESULT_END_OF_INPUT
        override fun seek(position: Long, timeUs: Long) = Unit
        override fun release() = Unit
    }

    private class FakeOutput : ExtractorOutput {
        val track = FakeTrack()
        override fun track(id: Int, type: Int): TrackOutput = track
        override fun endTracks() = Unit
        override fun seekMap(seekMap: SeekMap) = Unit
    }

    /** Collects whole samples as the sample queue would read them. */
    private class FakeTrack : TrackOutput {
        var format: Format? = null
        val samples = mutableListOf<ByteArray>()
        private val data = ByteArrayOutputStream()

        override fun format(format: Format) {
            this.format = format
        }

        override fun sampleData(
            input: DataReader,
            length: Int,
            allowEndOfInput: Boolean,
            sampleDataPart: Int,
        ): Int {
            val bytes = ByteArray(length)
            val read = input.read(bytes, 0, length)
            data.write(bytes, 0, read)
            return read
        }

        override fun sampleData(
            data: ParsableByteArray,
            length: Int,
            sampleDataPart: Int,
        ) {
            this.data.write(data.data, data.position, length)
            data.skipBytes(length)
        }

        override fun sampleMetadata(
            timeUs: Long,
            flags: Int,
            size: Int,
            offset: Int,
            cryptoData: TrackOutput.CryptoData?,
        ) {
            val all = data.toByteArray()
            val end = all.size - offset
            samples += all.copyOfRange(end - size, end)
            data.reset()
            data.write(all, end, offset)
        }
    }

    private companion object {
        val START = byteArrayOf(0, 0, 0, 1)
        val SLICE = byteArrayOf(0x02, 0x01, 0x11, 0x22, 0x33)

        /** An enhancement layer slice as a block addition holds it. */
        val EL = byteArrayOf(0x02, 0x01, 0x55, 0x66)
        val RPU_HEADER = byteArrayOf(0x7C, 0x01)
    }
}
