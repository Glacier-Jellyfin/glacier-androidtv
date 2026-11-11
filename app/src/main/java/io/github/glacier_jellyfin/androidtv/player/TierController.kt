package io.github.glacier_jellyfin.androidtv.player

/**
 * Keeps the tier of a ladder (see `PlaybackSource.ladder`), the size of the picture (4K, 1080p, 720p), within what the link
 * carries. A move is expensive: the server starts a new transcode for the tier, and its first segment comes about 16 s
 * later. With a buffer of up to two minutes that run-up is covered, so a move is cheap as long as the buffer is full. The
 * server also hands out its segments about as fast as they play, so a small buffer says nothing about the link. The reason
 * to move down is therefore a bandwidth test that no longer carries the tier ([limitTo]). A move up ([raiseTo]) needs a
 * full buffer, a link with clear room for the bigger tier, and a long time since the last move; it may go up to the top of the
 * ladder, which is already capped to the display and the source.
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

    /**
     * A bandwidth test said streams may use [budget] while [bufferedMs] of the picture is loaded: the tier to move up to, or
     * null. All must hold: the buffer carries the run-up of the new transcode, the link has [UP_MARGIN] times what the bigger
     * tier asks, the last move (either way) is [UP_PAUSE_MS] ago, and the tier is not already the top of the ladder.
     */
    fun raiseTo(budget: Int, bufferedMs: Long, nowMs: Long): Int? {
        if (index >= bitrates.lastIndex || bufferedMs < UP_BUFFER_MS || nowMs - changedAtMs < UP_PAUSE_MS) return null
        if (budget < expected[index + 1] * UP_MARGIN) return null
        reason = "the link carries ${budget / 1_000_000} Mbit/s, the next tier asks ${expected[index + 1] / 1_000_000}, ${bufferedMs / 1000} s buffered"
        index++
        changedAtMs = nowMs
        return bitrates[index]
    }

    companion object {
        /** A move up needs this much loaded: it covers the 16 to 29 s the new transcode needs to start. */
        const val UP_BUFFER_MS = 60_000L

        /** A move up needs the budget to be this many times what the bigger tier asks. */
        const val UP_MARGIN = 1.5

        /** A move up waits this long after the last move, in either direction. */
        const val UP_PAUSE_MS = 600_000L

        /** A move down waits this long after the last move: the new transcode needs time to start. */
        const val MOVE_PAUSE_MS = 30_000L

        /** The biggest tier whose [expected] bitrate the [budget] carries, or the smallest. */
        fun startFor(expected: List<Int>, budget: Int): Int = expected.indexOfLast { it <= budget }.coerceAtLeast(0)
    }
}

/**
 * Tells when the tiers load too slowly: two media segments in a row that took longer to load than they play. That is a link
 * (or a server) that no longer keeps up, seen about 30 s before the next scheduled measurement. At most one alarm per
 * [PAUSE_MS], so a bad stretch is measured, not hammered.
 */
internal class SlowLoads {
    private var inARow = 0
    private var alarmAtMs = Long.MIN_VALUE / 2

    /** One loaded segment; true when the link should be measured now. */
    fun record(loadMs: Long, playMs: Long, nowMs: Long): Boolean {
        if (loadMs <= playMs) {
            inARow = 0
            return false
        }
        if (++inARow < 2 || nowMs - alarmAtMs < PAUSE_MS) return false
        inARow = 0
        alarmAtMs = nowMs
        return true
    }

    companion object {
        const val PAUSE_MS = 30_000L
    }
}
