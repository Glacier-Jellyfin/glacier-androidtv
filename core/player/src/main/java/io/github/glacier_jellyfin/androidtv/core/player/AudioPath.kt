package io.github.glacier_jellyfin.androidtv.core.player

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util

/**
 * The audio of a title as the player needs to know it to keep one output path across the streams of the title. [codec] and
 * [profile] are the file's chosen audio track as the server names them. [decoded] is known once the title has played its file
 * (or was predicted): true when the file's sound is decoded on this device and leaves as PCM; null while it is not known.
 *
 * Only given for a title that may alternate between the file and a transcode (see [AudioPath.decodeOtherStreams]).
 */
data class TitleAudio(
    val codec: String?,
    val profile: String?,
    val decoded: Boolean? = null,
)

/**
 * The receiver decodes a bitstream (AC-3 from a transcode) with its own gain and in its own mixer, while the PCM that the
 * device decodes goes through the device's mixer; the same title is then noticeably louder or softer after a switch, and the
 * HDMI format flips with every one (some receivers mute for a moment on that). So within a title the sound of every stream
 * leaves the same way: if the file's sound is decoded here, the compressed sound of a transcode is decoded here too, which
 * loses nothing because the receiver would decode the same data.
 */
@UnstableApi
internal object AudioPath {

    /** The formats a sink may pass on as a bitstream; everything else is decoded to PCM in any case. */
    val COMPRESSED = setOf(
        MimeTypes.AUDIO_AC3,
        MimeTypes.AUDIO_E_AC3,
        MimeTypes.AUDIO_E_AC3_JOC,
        MimeTypes.AUDIO_AC4,
        MimeTypes.AUDIO_TRUEHD,
        MimeTypes.AUDIO_DTS,
        MimeTypes.AUDIO_DTS_HD,
        MimeTypes.AUDIO_DTS_EXPRESS,
    )

    /** Formats that Glacier packs as IEC 61937 itself when [PlaybackSetup.iec61937] is on. */
    private val PACKED = setOf(MimeTypes.AUDIO_TRUEHD, MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD)

    /**
     * Whether the sound of other streams of the title is decoded here: only for a title that [alternates] (the file and a
     * transcode take turns, never music or Live TV) and whose file's sound is [fileDecoded] on this device. A bitstream file
     * leaves things as they are (the transcode's sound may pass through, too); an unknown way leaves them as they are.
     */
    fun decodeOtherStreams(alternates: Boolean, fileDecoded: Boolean?): Boolean = alternates && fileDecoded == true

    /**
     * Whether a platform decoder may take [mime]. While [decodeCompressed] is set, compressed sound that FFmpeg
     * ([ffmpegDecodes]) can decode is left to FFmpeg: it keeps all the channels and applies no processing of its own, like
     * the file's PCM path, while a platform decoder (e.g. OMX.dolby.ac3.decoder) would downmix to stereo on its own terms.
     */
    fun platformMayDecode(mime: String?, decodeCompressed: Boolean, ffmpegDecodes: Boolean): Boolean =
        !(decodeCompressed && mime != null && mime in COMPRESSED && ffmpegDecodes)

    /** What the audio track reports: any linear PCM is decoded sound, a compressed encoding is a bitstream. */
    fun isDecoded(encoding: Int): Boolean = Util.isEncodingLinearPcm(encoding)

    /** The MIME type of the file's audio from the server's codec name and profile, null when unknown. */
    fun mimeOf(codec: String?, profile: String?): String? = when (codec?.lowercase()) {
        null, "" -> null
        "ac3" -> MimeTypes.AUDIO_AC3
        "eac3" -> if (profile?.contains("atmos", ignoreCase = true) == true) MimeTypes.AUDIO_E_AC3_JOC else MimeTypes.AUDIO_E_AC3
        "truehd" -> MimeTypes.AUDIO_TRUEHD
        "ac4" -> MimeTypes.AUDIO_AC4
        "dts", "dca" -> if (profile?.contains("DTS-HD", ignoreCase = true) == true) MimeTypes.AUDIO_DTS_HD else MimeTypes.AUDIO_DTS
        // AAC, FLAC, PCM and the rest are never passed through: they are always decoded here.
        else -> MimeTypes.AUDIO_RAW
    }

    /**
     * Predicts from the file's audio ([mime], see [mimeOf]) whether it is decoded on this device, for a title that starts as a
     * transcode and so has not played its file yet. [passthrough]: the sink's output takes [mime] as it is; [iec61937]: Glacier
     * packs TrueHD and DTS for HDMI itself. Null when the file's audio is not known.
     */
    fun predictDecoded(mime: String?, passthrough: Boolean, iec61937: Boolean): Boolean? = when {
        mime == null -> null
        mime !in COMPRESSED -> true
        iec61937 && mime in PACKED -> false
        else -> !passthrough
    }
}
