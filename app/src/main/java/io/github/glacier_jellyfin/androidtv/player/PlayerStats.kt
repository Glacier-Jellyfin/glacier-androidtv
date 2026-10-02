package io.github.glacier_jellyfin.androidtv.player

import android.content.Context
import android.os.Build
import android.view.Display
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import io.github.glacier_jellyfin.androidtv.core.data.media.channelLayout
import io.github.glacier_jellyfin.androidtv.core.data.playback.SourceAudio
import io.github.glacier_jellyfin.androidtv.core.data.playback.SourceVideo
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** What the player decodes right now, for the info sheet. */
data class StreamStats(
    val video: Format?,
    val audio: Format?,
    /** Buffered ahead of the position, in seconds. */
    val bufferSeconds: Long,
    val droppedFrames: Int?,
    /** Media3's estimate of the network speed, bits per second. */
    val bandwidth: Long?,
)

@OptIn(UnstableApi::class)
internal fun ExoPlayer.streamStats(context: Context) = StreamStats(
    video = videoFormat,
    audio = audioFormat,
    bufferSeconds = ((bufferedPosition - currentPosition) / 1000).coerceAtLeast(0),
    droppedFrames = videoDecoderCounters?.droppedBufferCount,
    // The meter ExoPlayer.Builder uses unless told otherwise.
    bandwidth = DefaultBandwidthMeter.getSingletonInstance(context).bitrateEstimate.takeIf { it > 0 },
)

/** How much Media3 buffers at most (DefaultLoadControl), the scale of the buffer bar. */
internal const val MAX_BUFFER_SECONDS = 50

/** "HEVC · 3840×2160". */
@OptIn(UnstableApi::class)
internal fun videoHeadline(format: Format): String = listOfNotNull(
    codecText(format.sampleMimeType, format.codecs),
    if (format.width > 0 && format.height > 0) "${format.width}×${format.height}" else null,
).joinToString(" · ")

/** "23.976 fps · HDR10 · 18.2 Mbit/s". */
@OptIn(UnstableApi::class)
internal fun videoDetails(format: Format): String = listOfNotNull(
    format.frameRate.takeIf { it > 0 }?.let { fpsText(it) },
    when (format.colorInfo?.colorTransfer) {
        C.COLOR_TRANSFER_ST2084 -> if (format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION) "Dolby Vision" else "HDR10"
        C.COLOR_TRANSFER_HLG -> "HLG"
        else -> "SDR".takeIf { format.colorInfo != null }
    },
    bitrateText(format.bitrate),
).joinToString(" · ")

/** "EAC3 5.1". */
@OptIn(UnstableApi::class)
internal fun audioHeadline(format: Format): String = listOfNotNull(
    codecText(format.sampleMimeType, format.codecs),
    channelLayout(format.channelCount.takeIf { it != Format.NO_VALUE }),
).joinToString(" ")

/** "48 kHz · 640 kbit/s". */
@OptIn(UnstableApi::class)
internal fun audioDetails(format: Format): String = listOfNotNull(
    format.sampleRate.takeIf { it > 0 }?.let { String.format(Locale.ROOT, "%.1f", it / 1000.0).removeSuffix(".0") + " kHz" },
    bitrateText(format.bitrate),
).joinToString(" · ")

internal fun fpsText(fps: Float): String = String.format(Locale.ROOT, "%.3f", fps).trimEnd('0').trimEnd('.') + " fps"

/** Codec names as people know them; unknown types fall back to the MIME subtype. */
@OptIn(UnstableApi::class)
internal fun codecText(mime: String?, codecs: String?): String? = when (mime) {
    null -> codecs?.substringBefore('.')?.uppercase()
    MimeTypes.VIDEO_H265 -> "HEVC"
    MimeTypes.VIDEO_H264 -> "H.264"
    MimeTypes.VIDEO_AV1 -> "AV1"
    MimeTypes.VIDEO_VP9 -> "VP9"
    MimeTypes.VIDEO_VP8 -> "VP8"
    MimeTypes.VIDEO_DOLBY_VISION -> "Dolby Vision"
    MimeTypes.VIDEO_MPEG2 -> "MPEG-2"
    MimeTypes.VIDEO_MPEG -> "MPEG-1"
    MimeTypes.AUDIO_AAC -> "AAC"
    MimeTypes.AUDIO_AC3 -> "AC3"
    MimeTypes.AUDIO_E_AC3 -> "EAC3"
    MimeTypes.AUDIO_E_AC3_JOC -> "EAC3 Atmos"
    MimeTypes.AUDIO_AC4 -> "AC4"
    MimeTypes.AUDIO_TRUEHD -> "TrueHD"
    MimeTypes.AUDIO_DTS -> "DTS"
    MimeTypes.AUDIO_DTS_HD -> "DTS-HD"
    MimeTypes.AUDIO_DTS_EXPRESS -> "DTS Express"
    MimeTypes.AUDIO_MPEG -> "MP3"
    MimeTypes.AUDIO_OPUS -> "Opus"
    MimeTypes.AUDIO_VORBIS -> "Vorbis"
    MimeTypes.AUDIO_FLAC -> "FLAC"
    MimeTypes.AUDIO_ALAC -> "ALAC"
    MimeTypes.AUDIO_RAW -> "PCM"
    else -> mime.substringAfter('/').removePrefix("x-").uppercase()
}

/** "18.2 Mbit/s" or "640 kbit/s"; null when unknown. */
internal fun bitrateText(bitsPerSecond: Long): String? = when {
    bitsPerSecond <= 0 -> null
    bitsPerSecond >= 1_000_000 -> String.format(Locale.ROOT, "%.1f Mbit/s", bitsPerSecond / 1_000_000.0)
    else -> "${bitsPerSecond / 1000} kbit/s"
}

internal fun bitrateText(bitsPerSecond: Int): String? = bitrateText(bitsPerSecond.toLong())

/** "14.2 GB" or "820 MB". */
internal fun sizeText(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> String.format(Locale.ROOT, "%.1f GB", bytes / 1_000_000_000.0)
    else -> "${(bytes / 1_000_000.0).roundToInt()} MB"
}

/**
 * Platform decoders of the hardware vendor versus software ones: Android's own
 * codecs (c2.android.*, OMX.google.*) and the FFmpeg/libgav1 extensions.
 */
internal fun isSoftwareDecoder(name: String): Boolean =
    name.startsWith("c2.android.") || name.startsWith("OMX.google.") || name.startsWith("lib") || name.contains("ffmpeg", ignoreCase = true)

/** What reaches the audio hardware: [passthrough] names the bitstream sent on, null for PCM. */
data class AudioOutput(val passthrough: String?, val channels: Int)

@OptIn(UnstableApi::class)
internal fun audioOutput(config: AudioSink.AudioTrackConfig) = AudioOutput(
    passthrough = if (Util.isEncodingLinearPcm(config.encoding)) {
        null
    } else {
        when (config.encoding) {
            C.ENCODING_AC3 -> "AC3"
            C.ENCODING_E_AC3 -> "EAC3"
            C.ENCODING_E_AC3_JOC -> "EAC3 Atmos"
            C.ENCODING_AC4 -> "AC4"
            C.ENCODING_DTS -> "DTS"
            C.ENCODING_DTS_HD -> "DTS-HD"
            C.ENCODING_DOLBY_TRUEHD -> "TrueHD"
            else -> "Bitstream"
        }
    },
    channels = Integer.bitCount(config.channelConfig),
)

/** The TV's current picture mode and the HDR formats it takes. */
data class DisplayInfo(val width: Int, val height: Int, val refreshRate: Float, val hdr: Set<HdrFormat>)

enum class HdrFormat(val label: String) { HDR10("HDR10"), HLG("HLG"), DolbyVision("Dolby Vision"), HDR10Plus("HDR10+") }

internal fun displayInfo(context: Context): DisplayInfo {
    val display = ContextCompat.getDisplayOrDefault(context)
    val mode = display.mode
    val types = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        mode.supportedHdrTypes
    } else {
        @Suppress("DEPRECATION")
        display.hdrCapabilities?.supportedHdrTypes ?: IntArray(0)
    }
    return DisplayInfo(
        width = mode.physicalWidth,
        height = mode.physicalHeight,
        refreshRate = mode.refreshRate,
        hdr = types.toList().mapNotNull {
            @Suppress("DEPRECATION")
            when (it) {
                Display.HdrCapabilities.HDR_TYPE_HDR10 -> HdrFormat.HDR10
                Display.HdrCapabilities.HDR_TYPE_HLG -> HdrFormat.HLG
                Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> HdrFormat.DolbyVision
                Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> HdrFormat.HDR10Plus
                else -> null
            }
        }.toSet(),
    )
}

/** "59.94 Hz", "60 Hz". */
internal fun refreshText(hz: Float): String = String.format(Locale.ROOT, "%.2f", hz).trimEnd('0').trimEnd('.') + " Hz"

/**
 * Whether [fps] plays evenly at [refreshHz]: every frame shown the same number of times.
 * 23.976 fps on 24 Hz is off by one frame every 42 s, so the tolerance is tight.
 */
internal fun frameRateMatches(refreshHz: Float, fps: Float): Boolean {
    if (refreshHz <= 0 || fps <= 0) return true
    val ratio = refreshHz / fps
    val repeats = ratio.roundToInt()
    return repeats >= 1 && abs(ratio - repeats) < 0.0005f * repeats
}

/** "HEVC Main 10" from the server's codec name and profile. */
internal fun sourceVideoHeadline(video: SourceVideo): String = listOfNotNull(
    serverCodecText(video.codec),
    video.profile?.takeIf { it.isNotBlank() },
    if (video.width != null && video.height != null) "${video.width}×${video.height}" else null,
).joinToString(" · ")

/** "Dolby Vision P8 · HDR10", "HDR10+", "SDR". */
internal fun rangeText(video: SourceVideo): String? {
    val type = video.rangeType ?: return null
    val dolbyVision = "Dolby Vision" + (video.dolbyVisionProfile?.let { " P$it" } ?: "")
    return when (type) {
        "SDR" -> "SDR"
        "HDR10" -> "HDR10"
        "HDR10Plus" -> "HDR10+"
        "HLG" -> "HLG"
        "DOVI" -> dolbyVision
        "DOVIWithHDR10" -> "$dolbyVision · HDR10"
        "DOVIWithHLG" -> "$dolbyVision · HLG"
        "DOVIWithSDR" -> "$dolbyVision · SDR"
        "DOVIWithHDR10Plus" -> "$dolbyVision · HDR10+"
        "DOVIWithEL", "DOVIWithELHDR10Plus" -> "$dolbyVision (EL)"
        else -> null
    }
}

/** "DTS-HD MA 7.1": the profile names DTS and Atmos variants better than the codec. */
internal fun sourceAudioText(audio: SourceAudio): String {
    val profile = audio.profile?.takeIf { it.isNotBlank() }
    val codec = when {
        profile != null && audio.codec?.lowercase() in setOf("dts", "dca") -> profile
        profile != null && profile.contains("Atmos", ignoreCase = true) -> serverCodecText(audio.codec) + " Atmos"
        else -> serverCodecText(audio.codec)
    }
    return listOfNotNull(codec, channelLayout(audio.channels)).joinToString(" ")
}

/** The server's codec names ("hevc", "eac3", "pcm_s24le") as people know them. */
internal fun serverCodecText(codec: String?): String? = when (val c = codec?.lowercase()) {
    null, "" -> null
    "hevc", "h265" -> "HEVC"
    "h264", "avc" -> "H.264"
    "av1" -> "AV1"
    "vp9" -> "VP9"
    "vp8" -> "VP8"
    "mpeg2video" -> "MPEG-2"
    "vc1" -> "VC-1"
    "truehd" -> "TrueHD"
    "dts", "dca" -> "DTS"
    "opus", "vorbis" -> c.replaceFirstChar { it.uppercase() }
    else -> if (c.startsWith("pcm")) "PCM" else c.uppercase()
}

/** One name per codec family on both sides, so "hevc" on the server matches Media3's video/hevc. */
internal fun serverCodecFamily(codec: String?): String? = when (val c = codec?.lowercase()) {
    null, "" -> null
    "h265" -> "hevc"
    "avc" -> "h264"
    "dca" -> "dts"
    else -> if (c.startsWith("pcm")) "pcm" else c
}

@OptIn(UnstableApi::class)
internal fun mimeCodecFamily(mime: String?): String? = when (mime) {
    null -> null
    MimeTypes.VIDEO_H265, MimeTypes.VIDEO_DOLBY_VISION -> "hevc"
    MimeTypes.VIDEO_H264 -> "h264"
    MimeTypes.VIDEO_AV1 -> "av1"
    MimeTypes.VIDEO_VP9 -> "vp9"
    MimeTypes.VIDEO_VP8 -> "vp8"
    MimeTypes.VIDEO_MPEG2 -> "mpeg2video"
    MimeTypes.AUDIO_AAC -> "aac"
    MimeTypes.AUDIO_AC3 -> "ac3"
    MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> "eac3"
    MimeTypes.AUDIO_TRUEHD -> "truehd"
    MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD, MimeTypes.AUDIO_DTS_EXPRESS -> "dts"
    MimeTypes.AUDIO_MPEG -> "mp3"
    MimeTypes.AUDIO_OPUS -> "opus"
    MimeTypes.AUDIO_VORBIS -> "vorbis"
    MimeTypes.AUDIO_FLAC -> "flac"
    MimeTypes.AUDIO_ALAC -> "alac"
    MimeTypes.AUDIO_RAW -> "pcm"
    else -> mime.substringAfter('/').removePrefix("x-").lowercase()
}
