package io.github.glacier_jellyfin.androidtv.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import io.github.glacier_jellyfin.androidtv.core.data.media.channelLayout
import java.util.Locale

/** What the player decodes right now, for the info sheet. */
data class StreamStats(
    val video: String?,
    val audio: String?,
    val videoDecoder: String?,
    val audioDecoder: String?,
    /** Buffered ahead of the position, in seconds. */
    val bufferSeconds: Long,
    val droppedFrames: Int?,
)

@OptIn(UnstableApi::class)
internal fun ExoPlayer.streamStats(videoDecoder: String?, audioDecoder: String?) = StreamStats(
    video = videoFormat?.let(::videoText),
    audio = audioFormat?.let(::audioText),
    videoDecoder = videoDecoder,
    audioDecoder = audioDecoder,
    bufferSeconds = ((bufferedPosition - currentPosition) / 1000).coerceAtLeast(0),
    droppedFrames = videoDecoderCounters?.droppedBufferCount,
)

/** "HEVC · 3840×2160 · 23.976 fps · HDR10 · 18.2 Mbit/s". */
@OptIn(UnstableApi::class)
internal fun videoText(format: Format): String = listOfNotNull(
    codecText(format.sampleMimeType, format.codecs),
    if (format.width > 0 && format.height > 0) "${format.width}×${format.height}" else null,
    format.frameRate.takeIf { it > 0 }?.let { String.format(Locale.ROOT, "%.3f", it).trimEnd('0').trimEnd('.') + " fps" },
    when (format.colorInfo?.colorTransfer) {
        C.COLOR_TRANSFER_ST2084 -> "HDR10"
        C.COLOR_TRANSFER_HLG -> "HLG"
        else -> null
    },
    bitrateText(format.bitrate),
).joinToString(" · ")

/** "EAC3 · 5.1 · 48 kHz · 640 kbit/s". */
@OptIn(UnstableApi::class)
internal fun audioText(format: Format): String = listOfNotNull(
    codecText(format.sampleMimeType, format.codecs),
    channelLayout(format.channelCount.takeIf { it != Format.NO_VALUE }),
    format.sampleRate.takeIf { it > 0 }?.let { String.format(Locale.ROOT, "%.1f", it / 1000.0).removeSuffix(".0") + " kHz" },
    bitrateText(format.bitrate),
).joinToString(" · ")

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
internal fun bitrateText(bitsPerSecond: Int): String? = when {
    bitsPerSecond <= 0 -> null
    bitsPerSecond >= 1_000_000 -> String.format(Locale.ROOT, "%.1f Mbit/s", bitsPerSecond / 1_000_000.0)
    else -> "${bitsPerSecond / 1000} kbit/s"
}
