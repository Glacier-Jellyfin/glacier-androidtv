package io.github.glacier_jellyfin.androidtv.player

/**
 * Counts the times playback ran dry. One stall can be a hiccup; several within [windowMs] mean the
 * stream asks more of the link than it carries.
 */
internal class StallTracker(private val windowMs: Long = 90_000, private val limit: Int = 2) {
    private val times = ArrayDeque<Long>()

    /** Notes a stall at [nowMs]; true when there are enough of them to act. */
    fun record(nowMs: Long): Boolean {
        times.addLast(nowMs)
        while (times.first() < nowMs - windowMs) times.removeFirst()
        return times.size >= limit
    }

    fun clear() = times.clear()
}
