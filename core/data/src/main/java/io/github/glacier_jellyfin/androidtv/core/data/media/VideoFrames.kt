package io.github.glacier_jellyfin.androidtv.core.data.media

import org.jellyfin.sdk.model.api.BaseItemKind

/**
 * A still frame of a video, grabbed on the device when the server has no picture for it. It travels as a
 * string in the place of an image URL: `glacier-frame:<positionMs>:<stream url>`. The stream URL carries no
 * credentials; the image loader adds them when it grabs the frame.
 */
object VideoFrames {
    const val SCHEME = "glacier-frame:"

    private const val FRAME_FRACTION = 0.1
    private const val MAX_POSITION_MS = 600_000L

    /** A frame to grab: the video's stream and where in it. */
    data class Frame(val streamUrl: String, val positionMs: Long)

    /** Only a video (a home video) without a primary image of its own needs a frame; the rest has art. */
    fun needsFrame(type: BaseItemKind?, primaryTag: String?): Boolean =
        type == BaseItemKind.VIDEO && primaryTag.isNullOrBlank()

    /** Where to grab, like the server's own screen grabber: a tenth into the video; the start when its length is unknown. */
    fun positionMs(runtimeMs: Long?): Long {
        if (runtimeMs == null || runtimeMs <= 0) return 0
        return (runtimeMs * FRAME_FRACTION).toLong().coerceIn(0, MAX_POSITION_MS)
    }

    fun encode(streamUrl: String, positionMs: Long): String = "$SCHEME${positionMs.coerceAtLeast(0)}:$streamUrl"

    fun isFrame(value: String?): Boolean = value != null && value.startsWith(SCHEME)

    /** The frame a string names; null for anything else (a plain image URL) or a malformed one. */
    fun decode(value: String?): Frame? {
        if (!isFrame(value)) return null
        val rest = value!!.substring(SCHEME.length)
        val split = rest.indexOf(':')
        if (split <= 0) return null
        val position = rest.substring(0, split).toLongOrNull()?.takeIf { it >= 0 } ?: return null
        val url = rest.substring(split + 1)
        return if (url.isEmpty()) null else Frame(url, position)
    }
}
