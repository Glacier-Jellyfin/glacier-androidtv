package io.github.glacier_jellyfin.androidtv.trailer

/**
 * When a trailer counts as started or as stuck. YouTube's WebView player can miss
 * its PLAYING event, so a moving position counts as well, and any sign of life
 * (API ready, buffering, ...) gives the start more time, up to a hard cap.
 */
internal object TrailerStart {
    /** Without any sign of life for this long, the start failed. */
    const val QUIET_TIMEOUT_MS = 20_000L

    /** Signs of life extend a YouTube start, but never beyond this since it began. */
    const val HARD_CAP_MS = 45_000L

    /** The IFrame API's PLAYING state. */
    const val YT_PLAYING = 1

    /** When the start fails if nothing else happens. Local trailers: plain [QUIET_TIMEOUT_MS]. */
    fun deadline(
        startedAt: Long,
        lastSignOfLife: Long,
        isYouTube: Boolean,
    ): Long {
        if (!isYouTube) return startedAt + QUIET_TIMEOUT_MS
        return minOf(startedAt + HARD_CAP_MS, maxOf(startedAt, lastSignOfLife) + QUIET_TIMEOUT_MS)
    }

    fun timedOut(
        startedAt: Long,
        lastSignOfLife: Long,
        now: Long,
        isYouTube: Boolean,
    ): Boolean = now >= deadline(startedAt, lastSignOfLife, isYouTube)

    /** Playing by the polled state, or by a position that moved on since the previous report. */
    fun started(
        previousPositionMs: Long,
        positionMs: Long,
        polledState: Int,
    ): Boolean = polledState == YT_PLAYING || positionMs > previousPositionMs.coerceAtLeast(0)
}
