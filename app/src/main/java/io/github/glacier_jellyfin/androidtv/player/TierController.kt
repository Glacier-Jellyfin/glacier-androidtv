package io.github.glacier_jellyfin.androidtv.player

/**
 * Keeps the tier of a ladder (see `PlaybackSource.ladder`), the size of the picture (4K, 1080p, 720p), within what the link
 * carries. A move is expensive: the server starts a new transcode for the tier, and its first segment comes about 16 s
 * later, longer than the buffer lasts, so the picture stops. The server also hands out its segments about as fast as they
 * play, so the buffer is always small and says nothing about the link. The only reason to move down is therefore a
 * bandwidth test that no longer carries the tier ([limitTo]); the stream starts at the biggest tier the link carries, and a
 * better link is used by the next title.
 *
 * [bitrates] are the tiers as the player lists them, smallest first; [expected] what each asks of the link; the stream
 * starts at [start].
 */
internal class TierController(val bitrates: List<Int>, val expected: List<Int>, start: Int) {

    var index = start.coerceIn(0, bitrates.lastIndex)
        private set

    val bitrate: Int get() = bitrates[index]

    private var changedAtMs = Long.MIN_VALUE / 2

    /** Why the last move happened, for the log. */
    var reason = ""
        private set

    /**
     * A bandwidth test said streams may use [budget]: the tier to move to when the current one asks more than that, or null.
     * One step at a time, and not before the last move had time to start its transcode.
     */
    fun limitTo(budget: Int, nowMs: Long): Int? {
        val ceiling = startFor(expected, budget)
        if (index <= ceiling || nowMs - changedAtMs < MOVE_PAUSE_MS) return null
        reason = "the link carries ${budget / 1_000_000} Mbit/s, the tier asks ${expected[index] / 1_000_000}"
        index--
        changedAtMs = nowMs
        return bitrates[index]
    }

    companion object {
        /** A move down waits this long after the last move: the new transcode needs time to start. */
        const val MOVE_PAUSE_MS = 30_000L

        /** The biggest tier whose [expected] bitrate the [budget] carries, or the smallest. */
        fun startFor(expected: List<Int>, budget: Int): Int = expected.indexOfLast { it <= budget }.coerceAtLeast(0)
    }
}
