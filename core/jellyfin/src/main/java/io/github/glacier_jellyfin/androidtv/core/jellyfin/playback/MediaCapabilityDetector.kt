package io.github.glacier_jellyfin.androidtv.core.jellyfin.playback

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.display.DisplayManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import android.os.Build
import android.view.Display
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Reads decoder, passthrough and display capabilities from the platform. */
@Singleton
class MediaCapabilityDetector @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val softwareAudio: SoftwareAudioCodecs,
) {

    val capabilities: DeviceCapabilities by lazy { detect() }

    // Newer profile constants are plain numbers compiled in; older devices simply never report them.
    @SuppressLint("InlinedApi")
    private fun detect(): DeviceCapabilities {
        val decoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filterNot { it.isEncoder }
        fun decodersFor(mime: String) = decoders.filter { info -> info.supportedTypes.any { it.equals(mime, ignoreCase = true) } }
        fun has(mime: String) = decodersFor(mime).isNotEmpty()

        val displayHdr = displayHdrTypes()
        val hevcProfiles = profiles(decodersFor(MIME_HEVC), MIME_HEVC)
        val av1Profiles = profiles(decodersFor(MIME_AV1), MIME_AV1)
        val hdr = buildSet {
            val hdr10Decoder = CodecProfileLevel.HEVCProfileMain10HDR10 in hevcProfiles ||
                CodecProfileLevel.AV1ProfileMain10HDR10 in av1Profiles
            val hdr10PlusDecoder = CodecProfileLevel.HEVCProfileMain10HDR10Plus in hevcProfiles ||
                CodecProfileLevel.AV1ProfileMain10HDR10Plus in av1Profiles
            if (hdr10Decoder && Display.HdrCapabilities.HDR_TYPE_HDR10 in displayHdr) add(HdrType.Hdr10)
            if (hdr10PlusDecoder && Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS in displayHdr) add(HdrType.Hdr10Plus)
            // HLG uses the plain Main 10 profile.
            if (CodecProfileLevel.HEVCProfileMain10 in hevcProfiles && Display.HdrCapabilities.HDR_TYPE_HLG in displayHdr) add(HdrType.Hlg)
            if (has(MIME_DOLBY_VISION) && Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION in displayHdr) add(HdrType.DolbyVision)
        }

        val audio = buildSet {
            AUDIO_DECODERS.forEach { (mime, codecs) -> if (has(mime)) addAll(codecs) }
            passthroughCodecs().forEach(::add)
            addAll(softwareAudio.codecs())
        }

        return DeviceCapabilities(
            h264 = videoSupport(decodersFor(MIME_H264), MIME_H264, ::avcLevel, tenBit = CodecProfileLevel.AVCProfileHigh10),
            hevc = videoSupport(decodersFor(MIME_HEVC), MIME_HEVC, ::hevcLevel, tenBit = CodecProfileLevel.HEVCProfileMain10),
            av1 = videoSupport(decodersFor(MIME_AV1), MIME_AV1, { null }, tenBit = CodecProfileLevel.AV1ProfileMain10),
            vp9 = videoSupport(decodersFor(MIME_VP9), MIME_VP9, { null }, tenBit = CodecProfileLevel.VP9Profile2),
            otherVideoCodecs = buildSet {
                if (has("video/x-vnd.on2.vp8")) add("vp8")
                if (has("video/mpeg2")) add("mpeg2video")
                if (has("video/wvc1") || has("video/x-ms-wmv")) add("vc1")
                if (has("video/mp4v-es")) add("mpeg4")
            },
            audioCodecs = audio,
            maxAudioChannels = maxAudioChannels(),
            hdr = hdr,
        )
    }

    private fun videoSupport(
        codecs: List<MediaCodecInfo>,
        mime: String,
        level: (Int) -> Int?,
        tenBit: Int,
    ): VideoCodecSupport? {
        if (codecs.isEmpty()) return null
        val caps = codecs.map { it.getCapabilitiesForType(mime) }
        val widths = caps.mapNotNull { it.videoCapabilities?.supportedWidths?.upper }
        val heights = caps.mapNotNull { it.videoCapabilities?.supportedHeights?.upper }
        val levels = caps.flatMap { c -> c.profileLevels.mapNotNull { level(it.level) } }
        return VideoCodecSupport(
            maxWidth = widths.maxOrNull() ?: 1920,
            maxHeight = heights.maxOrNull() ?: 1080,
            maxLevel = levels.maxOrNull(),
            tenBit = caps.any { c -> c.profileLevels.any { it.profile == tenBit } },
        )
    }

    private fun profiles(codecs: List<MediaCodecInfo>, mime: String): Set<Int> =
        codecs.flatMapTo(HashSet()) { info -> info.getCapabilitiesForType(mime).profileLevels.map { it.profile } }

    private fun displayHdrTypes(): Set<Int> {
        val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY) ?: return emptySet()
        val types = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            display.mode.supportedHdrTypes
        } else {
            @Suppress("DEPRECATION")
            display.hdrCapabilities?.supportedHdrTypes
        }
        return types?.toSet().orEmpty()
    }

    /** Formats the connected receiver or TV accepts as a bitstream. */
    private fun passthroughCodecs(): List<String> {
        val audioManager = context.getSystemService(AudioManager::class.java) ?: return emptyList()
        val encodings = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).flatMap { it.encodings.toList() }.toSet()
        return buildList {
            if (AudioFormat.ENCODING_AC3 in encodings) add("ac3")
            if (AudioFormat.ENCODING_E_AC3 in encodings || AudioFormat.ENCODING_E_AC3_JOC in encodings) add("eac3")
            if (AudioFormat.ENCODING_DTS in encodings || AudioFormat.ENCODING_DTS_HD in encodings) { add("dts"); add("dca") }
            if (AudioFormat.ENCODING_DOLBY_TRUEHD in encodings) add("truehd")
        }
    }

    private fun maxAudioChannels(): Int {
        val audioManager = context.getSystemService(AudioManager::class.java) ?: return 2
        val counts = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).flatMap { it.channelCounts.toList() }
        // Devices that report nothing (common for HDMI before a sink is known) get the usual 5.1 for TVs.
        return counts.maxOrNull()?.coerceAtMost(8) ?: 6
    }

    private companion object {
        const val MIME_H264 = "video/avc"
        const val MIME_HEVC = "video/hevc"
        const val MIME_AV1 = "video/av01"
        const val MIME_VP9 = "video/x-vnd.on2.vp9"
        const val MIME_DOLBY_VISION = "video/dolby-vision"

        /** Platform decoders Media3 uses, and the Jellyfin codec names they cover. */
        val AUDIO_DECODERS = listOf(
            "audio/mp4a-latm" to listOf("aac"),
            "audio/mpeg" to listOf("mp3"),
            "audio/mpeg-L2" to listOf("mp2"),
            "audio/flac" to listOf("flac"),
            "audio/opus" to listOf("opus"),
            "audio/vorbis" to listOf("vorbis"),
            "audio/raw" to listOf("pcm_s16le", "pcm_s24le"),
            "audio/alac" to listOf("alac"),
            "audio/ac3" to listOf("ac3"),
            "audio/eac3" to listOf("eac3"),
            "audio/vnd.dts" to listOf("dts", "dca"),
            "audio/true-hd" to listOf("truehd"),
        )

        /** Android AVC level constant → Jellyfin level (10 × level). */
        fun avcLevel(level: Int): Int? = when (level) {
            CodecProfileLevel.AVCLevel1, CodecProfileLevel.AVCLevel1b -> 10
            CodecProfileLevel.AVCLevel11 -> 11
            CodecProfileLevel.AVCLevel12 -> 12
            CodecProfileLevel.AVCLevel13 -> 13
            CodecProfileLevel.AVCLevel2 -> 20
            CodecProfileLevel.AVCLevel21 -> 21
            CodecProfileLevel.AVCLevel22 -> 22
            CodecProfileLevel.AVCLevel3 -> 30
            CodecProfileLevel.AVCLevel31 -> 31
            CodecProfileLevel.AVCLevel32 -> 32
            CodecProfileLevel.AVCLevel4 -> 40
            CodecProfileLevel.AVCLevel41 -> 41
            CodecProfileLevel.AVCLevel42 -> 42
            CodecProfileLevel.AVCLevel5 -> 50
            CodecProfileLevel.AVCLevel51 -> 51
            CodecProfileLevel.AVCLevel52 -> 52
            CodecProfileLevel.AVCLevel6 -> 60
            CodecProfileLevel.AVCLevel61 -> 61
            CodecProfileLevel.AVCLevel62 -> 62
            else -> null
        }

        /** Android HEVC level constant (either tier) → Jellyfin level (30 × level). */
        fun hevcLevel(level: Int): Int? = when (level) {
            CodecProfileLevel.HEVCMainTierLevel1, CodecProfileLevel.HEVCHighTierLevel1 -> 30
            CodecProfileLevel.HEVCMainTierLevel2, CodecProfileLevel.HEVCHighTierLevel2 -> 60
            CodecProfileLevel.HEVCMainTierLevel21, CodecProfileLevel.HEVCHighTierLevel21 -> 63
            CodecProfileLevel.HEVCMainTierLevel3, CodecProfileLevel.HEVCHighTierLevel3 -> 90
            CodecProfileLevel.HEVCMainTierLevel31, CodecProfileLevel.HEVCHighTierLevel31 -> 93
            CodecProfileLevel.HEVCMainTierLevel4, CodecProfileLevel.HEVCHighTierLevel4 -> 120
            CodecProfileLevel.HEVCMainTierLevel41, CodecProfileLevel.HEVCHighTierLevel41 -> 123
            CodecProfileLevel.HEVCMainTierLevel5, CodecProfileLevel.HEVCHighTierLevel5 -> 150
            CodecProfileLevel.HEVCMainTierLevel51, CodecProfileLevel.HEVCHighTierLevel51 -> 153
            CodecProfileLevel.HEVCMainTierLevel52, CodecProfileLevel.HEVCHighTierLevel52 -> 156
            CodecProfileLevel.HEVCMainTierLevel6, CodecProfileLevel.HEVCHighTierLevel6 -> 180
            CodecProfileLevel.HEVCMainTierLevel61, CodecProfileLevel.HEVCHighTierLevel61 -> 183
            CodecProfileLevel.HEVCMainTierLevel62, CodecProfileLevel.HEVCHighTierLevel62 -> 186
            else -> null
        }
    }
}
