package io.github.glacier_jellyfin.androidtv.core.jellyfin.playback

/**
 * What this device can decode or pass through, as far as the device profile
 * sent to the server needs it. Detected once by [MediaCapabilityDetector];
 * kept as plain data so the profile can be tested without a device.
 */
data class DeviceCapabilities(
    val h264: VideoCodecSupport?,
    val hevc: VideoCodecSupport?,
    val av1: VideoCodecSupport?,
    val vp9: VideoCodecSupport?,
    /** Other video codecs with a decoder (Jellyfin codec names: vp8, mpeg2video, vc1, mpeg4). */
    val otherVideoCodecs: Set<String>,
    /** Audio codecs with a decoder or passthrough to the receiver (Jellyfin codec names). */
    val audioCodecs: Set<String>,
    val maxAudioChannels: Int,
    /** HDR formats both a decoder and the display can show. */
    val hdr: Set<HdrType>,
)

data class VideoCodecSupport(
    val maxWidth: Int,
    val maxHeight: Int,
    /** Highest level in Jellyfin's numbering (H.264: 51 = 5.1, HEVC: 153 = 5.1); null when unknown. */
    val maxLevel: Int?,
    /** 10-bit profile (H.264 High 10, HEVC Main 10, AV1 Main 10, VP9 profile 2). */
    val tenBit: Boolean,
)

enum class HdrType { Hdr10, Hdr10Plus, Hlg, DolbyVision }
