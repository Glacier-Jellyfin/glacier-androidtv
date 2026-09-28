package io.github.glacier_jellyfin.androidtv.core.jellyfin.playback

import org.jellyfin.sdk.model.api.CodecType
import org.jellyfin.sdk.model.api.DeviceProfile
import org.jellyfin.sdk.model.api.DlnaProfileType
import org.jellyfin.sdk.model.api.EncodingContext
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import org.jellyfin.sdk.model.api.ProfileConditionValue
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod
import org.jellyfin.sdk.model.api.VideoRangeType
import org.jellyfin.sdk.model.deviceprofile.DeviceProfileBuilder
import org.jellyfin.sdk.model.deviceprofile.buildDeviceProfile

/**
 * Builds the device profile the server uses to choose between direct play,
 * remuxing and transcoding. Everything the device can decode (or pass through)
 * is offered for direct play; the rest is transcoded to HLS.
 */
object DeviceProfiles {

    /** Containers Media3 extracts itself. */
    private val Containers = listOf("mkv", "webm", "mp4", "m4v", "mov", "ts", "mpegts", "m2ts", "avi", "flv", "ogg", "ogv", "mpg", "mpeg", "vob")

    /** Text subtitles Media3 renders from a separate file or from inside the container. */
    private val TextSubtitles = listOf("srt", "subrip", "vtt", "webvtt", "ttml", "ass", "ssa")

    /** Picture subtitles Media3 renders only from inside the container. */
    private val PictureSubtitles = listOf("pgs", "pgssub", "dvdsub", "vobsub", "dvbsub")

    fun build(capabilities: DeviceCapabilities, maxBitrate: Int): DeviceProfile = buildDeviceProfile {
        name = "Glacier"
        maxStreamingBitrate = maxBitrate
        maxStaticBitrate = maxBitrate
        musicStreamingTranscodingBitrate = MUSIC_BITRATE

        val videoCodecs = buildList {
            if (capabilities.h264 != null) add("h264")
            if (capabilities.hevc != null) add("hevc")
            if (capabilities.av1 != null) add("av1")
            if (capabilities.vp9 != null) add("vp9")
            addAll(capabilities.otherVideoCodecs)
        }
        val audioCodecs = capabilities.audioCodecs.sorted()

        directPlayProfile {
            type = DlnaProfileType.VIDEO
            container(*Containers.toTypedArray())
            videoCodec(*videoCodecs.toTypedArray())
            audioCodec(*audioCodecs.toTypedArray())
        }
        directPlayProfile {
            type = DlnaProfileType.AUDIO
            audioCodec(*audioCodecs.toTypedArray())
        }

        // Transcoding target: fragmented MP4 over HLS, HEVC when the device decodes it.
        val hlsAudio = listOf("aac", "ac3", "eac3", "mp3", "flac", "opus").filter { it in capabilities.audioCodecs }.ifEmpty { listOf("aac") }
        transcodingProfile {
            type = DlnaProfileType.VIDEO
            context = EncodingContext.STREAMING
            container = "mp4"
            protocol = MediaStreamProtocol.HLS
            videoCodec(*listOfNotNull("hevc".takeIf { capabilities.hevc != null }, "h264").toTypedArray())
            audioCodec(*hlsAudio.toTypedArray())
            maxAudioChannels = capabilities.maxAudioChannels.toString()
            copyTimestamps = false
        }
        transcodingProfile {
            type = DlnaProfileType.AUDIO
            context = EncodingContext.STREAMING
            container = "mp4"
            protocol = MediaStreamProtocol.HLS
            audioCodec("aac")
        }

        capabilities.h264?.let { videoLimits("h264", it, profiles = listOfNotNull("high", "main", "baseline", "constrained baseline", "high 10".takeIf { _ -> it.tenBit })) }
        capabilities.hevc?.let { videoLimits("hevc", it, profiles = listOfNotNull("main", "main 10".takeIf { _ -> it.tenBit })) }
        capabilities.av1?.let { videoLimits("av1", it, profiles = listOfNotNull("main", "main 10".takeIf { _ -> it.tenBit })) }
        capabilities.vp9?.let { videoLimits("vp9", it, profiles = null) }

        // HDR the device cannot show is tone-mapped by the server.
        val ranges = supportedRanges(capabilities.hdr).map { it.serialName }
        listOfNotNull("hevc".takeIf { capabilities.hevc != null }, "av1".takeIf { capabilities.av1 != null }).forEach { codec ->
            codecProfile {
                type = CodecType.VIDEO
                this.codec = codec
                conditions { ProfileConditionValue.VIDEO_RANGE_TYPE inCollection ranges }
            }
        }

        codecProfile {
            type = CodecType.VIDEO_AUDIO
            conditions { ProfileConditionValue.AUDIO_CHANNELS lowerThanOrEquals capabilities.maxAudioChannels }
        }

        // Text subtitles stay separate files; anything else is burned in only when the device cannot show it.
        TextSubtitles.forEach {
            subtitleProfile(it, SubtitleDeliveryMethod.EMBED)
            subtitleProfile(it, SubtitleDeliveryMethod.EXTERNAL)
        }
        PictureSubtitles.forEach {
            subtitleProfile(it, SubtitleDeliveryMethod.EMBED)
            subtitleProfile(it, SubtitleDeliveryMethod.ENCODE)
        }
    }

    /**
     * Range types to play directly. SDR always; Dolby Vision streams with an
     * HDR10, HLG or SDR base layer play that layer when there is no Dolby
     * Vision decoder, which Media3 does by falling back to HEVC.
     */
    internal fun supportedRanges(hdr: Set<HdrType>): List<VideoRangeType> = buildList {
        add(VideoRangeType.SDR)
        add(VideoRangeType.UNKNOWN)
        add(VideoRangeType.DOVI_WITH_SDR)
        if (HdrType.Hdr10 in hdr) addAll(listOf(VideoRangeType.HDR10, VideoRangeType.DOVI_WITH_HDR10))
        if (HdrType.Hdr10Plus in hdr) addAll(listOf(VideoRangeType.HDR10_PLUS, VideoRangeType.DOVI_WITH_HDR10_PLUS))
        if (HdrType.Hdr10 in hdr && HdrType.Hdr10Plus !in hdr) add(VideoRangeType.DOVI_WITH_HDR10_PLUS)
        if (HdrType.Hlg in hdr) addAll(listOf(VideoRangeType.HLG, VideoRangeType.DOVI_WITH_HLG))
        if (HdrType.DolbyVision in hdr) addAll(listOf(VideoRangeType.DOVI, VideoRangeType.DOVI_WITH_EL, VideoRangeType.DOVI_WITH_ELHDR10_PLUS))
    }.distinct()

    private fun DeviceProfileBuilder.videoLimits(codec: String, support: VideoCodecSupport, profiles: List<String>?) {
        codecProfile {
            type = CodecType.VIDEO
            this.codec = codec
            conditions {
                ProfileConditionValue.WIDTH lowerThanOrEquals support.maxWidth
                ProfileConditionValue.HEIGHT lowerThanOrEquals support.maxHeight
                support.maxLevel?.let { ProfileConditionValue.VIDEO_LEVEL lowerThanOrEquals it }
                profiles?.let { ProfileConditionValue.VIDEO_PROFILE inCollection it }
                if (!support.tenBit) ProfileConditionValue.VIDEO_BIT_DEPTH lowerThanOrEquals 8
            }
        }
    }

    private const val MUSIC_BITRATE = 320_000
}
