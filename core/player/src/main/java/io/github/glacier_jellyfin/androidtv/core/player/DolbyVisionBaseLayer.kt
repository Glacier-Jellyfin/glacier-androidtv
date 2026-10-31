package io.github.glacier_jellyfin.androidtv.core.player

import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.CodecSpecificDataUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.NalUnitUtil
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.TrackOutput

/**
 * Hands Dolby Vision profile 7 to the HEVC decoder. Its base layer is plain
 * HDR10 HEVC, and HEVC decoders skip the enhancement layer. Media3 does not do
 * this itself for profile 7, so a Dolby Vision decoder without the profile
 * (Amlogic) would get it and fail.
 */
@OptIn(UnstableApi::class)
internal class DolbyVisionBaseLayerExtractors(private val inner: ExtractorsFactory) : ExtractorsFactory by inner {

    override fun createExtractors(): Array<Extractor> = inner.createExtractors().map(::BaseLayerExtractor).toTypedArray()

    override fun createExtractors(uri: android.net.Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        inner.createExtractors(uri, responseHeaders).map(::BaseLayerExtractor).toTypedArray()

    private class BaseLayerExtractor(private val inner: Extractor) : Extractor by inner {
        override fun init(output: ExtractorOutput) = inner.init(BaseLayerOutput(output))
        override fun getUnderlyingImplementation(): Extractor = inner.underlyingImplementation
    }

    private class BaseLayerOutput(private val inner: ExtractorOutput) : ExtractorOutput by inner {
        override fun track(id: Int, type: Int): TrackOutput = BaseLayerTrack(inner.track(id, type))
    }

    private class BaseLayerTrack(private val inner: TrackOutput) : TrackOutput by inner {
        override fun format(format: Format) = inner.format(baseLayer(format))
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
                    val level = NalUnitUtil.parseH265SpsNalUnit(csd, start + 3, next, null).profileTierLevel ?: return null
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
