package io.github.glacier_jellyfin.androidtv.core.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.text.SubtitleParser
import io.github.peerless2012.ass.media.AssHandler
import io.github.peerless2012.ass.media.extractor.AssMatroskaExtractor

/**
 * The Matroska extractor of libass (fonts and dialogue for ASS subtitles)
 * that also hands on the block additions of Dolby Vision tracks. A dual layer
 * MKV can keep the enhancement layer and the RPU there instead of in the
 * video samples, and Media3 drops them; [blockAdditions] gets them instead.
 */
@OptIn(UnstableApi::class)
internal class DolbyVisionMatroskaExtractor(
    subtitleParsers: SubtitleParser.Factory,
    ass: AssHandler,
) : AssMatroskaExtractor(subtitleParsers, ass), BlockAdditionSource {

    override var blockAdditions: ((BlockAddition) -> Unit)? = null

    private val addition = BlockAddition()

    override fun handleBlockAdditionalData(track: Track, blockAdditionalId: Int, input: ExtractorInput, contentSize: Int) {
        val listener = blockAdditions
        // Media3 marks the field as never null, but it is null for every track without a Dolby Vision configuration.
        val dolbyVision: ByteArray? = track.dolbyVisionConfigBytes
        if (listener == null || track.type != C.TRACK_TYPE_VIDEO || dolbyVision == null) {
            super.handleBlockAdditionalData(track, blockAdditionalId, input, contentSize)
            return
        }
        if (addition.data.size < contentSize) addition.data = ByteArray(contentSize)
        input.readFully(addition.data, 0, contentSize)
        addition.trackNumber = track.number
        addition.lengthSize = track.nalUnitLengthFieldLength
        addition.size = contentSize
        listener(addition)
    }
}

/** An extractor that hands on the block additions of Dolby Vision tracks. */
internal interface BlockAdditionSource {
    /**
     * Takes a Dolby Vision track's block addition: NAL units, each after a
     * length of [BlockAddition.lengthSize] bytes, as in the track's samples.
     * The buffer is used again for the next one.
     */
    var blockAdditions: ((BlockAddition) -> Unit)?
}

/** One block addition; see [BlockAdditionSource]. */
internal class BlockAddition {
    /** The track's number, which is also the id its output was created with. */
    var trackNumber = 0
    var lengthSize = 0
    var data = ByteArray(0)
    var size = 0
}

/**
 * These extractors with [DolbyVisionMatroskaExtractor] for Matroska, in the same place among them. The seek points of each
 * Matroska file are remembered ([SyncPoints]), so a later open of the same file can start where it does not have to decode
 * and discard anything.
 */
@OptIn(UnstableApi::class)
internal fun ExtractorsFactory.withDolbyVisionMkvSupport(subtitleParsers: SubtitleParser.Factory, ass: AssHandler): ExtractorsFactory {
    val inner = this
    return object : ExtractorsFactory {
        override fun createExtractors(): Array<Extractor> = wrap(inner.createExtractors(), null)

        override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
            wrap(inner.createExtractors(uri, responseHeaders), SyncPoints.keyOf(uri.toString()))

        private fun wrap(extractors: Array<Extractor>, key: String?): Array<Extractor> =
            extractors.map {
                if (it !is MatroskaExtractor) return@map it
                val dolbyVision = DolbyVisionMatroskaExtractor(subtitleParsers, ass)
                if (key == null) dolbyVision else SeekMapReporter(dolbyVision, key)
            }.toTypedArray()
    }
}

/** An extractor that hands the seek map of its file to [SyncPoints] as well as to the player. */
@OptIn(UnstableApi::class)
private class SeekMapReporter(private val inner: Extractor, private val key: String) : Extractor by inner {
    override fun init(output: ExtractorOutput) {
        inner.init(object : ExtractorOutput by output {
            override fun seekMap(seekMap: SeekMap) {
                SyncPoints.remember(key, seekMap)
                output.seekMap(seekMap)
            }
        })
    }

    // The code that looks for block additions asks the extractor behind this one.
    override fun getUnderlyingImplementation(): Extractor = inner.underlyingImplementation
}
