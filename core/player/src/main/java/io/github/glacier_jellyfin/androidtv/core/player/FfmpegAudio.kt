package io.github.glacier_jellyfin.androidtv.core.player

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.ffmpeg.FfmpegLibrary

/** What the bundled FFmpeg decoder can play; empty in builds without it (see core/ffmpeg/README.md). */
object FfmpegAudio {

    /** Formats FFmpeg decodes, with their Jellyfin codec names. */
    private val FORMATS = listOf(
        MimeTypes.AUDIO_AC3 to listOf("ac3"),
        MimeTypes.AUDIO_E_AC3 to listOf("eac3"),
        MimeTypes.AUDIO_TRUEHD to listOf("truehd"),
        MimeTypes.AUDIO_DTS to listOf("dts", "dca"),
        MimeTypes.AUDIO_FLAC to listOf("flac"),
        MimeTypes.AUDIO_ALAC to listOf("alac"),
        MimeTypes.AUDIO_VORBIS to listOf("vorbis"),
        MimeTypes.AUDIO_OPUS to listOf("opus"),
        MimeTypes.AUDIO_MPEG to listOf("mp3"),
        MimeTypes.AUDIO_AAC to listOf("aac"),
    )

    /** The decoder's version ("libavcodec 60.3.100"), null in builds without FFmpeg. */
    @OptIn(UnstableApi::class)
    fun version(): String? =
        if (FfmpegLibrary.isAvailable()) FfmpegLibrary.getVersion()?.replace(Regex("^Lavc"), "libavcodec ") else null

    @OptIn(UnstableApi::class)
    fun jellyfinCodecs(): Set<String> {
        if (!FfmpegLibrary.isAvailable()) {
            Log.i(TAG, "FFmpeg decoder not included in this build")
            return emptySet()
        }
        val codecs = FORMATS.filter { (mime, _) -> FfmpegLibrary.supportsFormat(mime) }.flatMapTo(HashSet()) { it.second }
        Log.i(TAG, "FFmpeg ${FfmpegLibrary.getVersion()} decodes ${codecs.sorted()}")
        return codecs
    }

    private const val TAG = "FfmpegAudio"
}
