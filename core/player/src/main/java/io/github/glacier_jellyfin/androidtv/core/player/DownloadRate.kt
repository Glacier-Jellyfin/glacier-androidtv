package io.github.glacier_jellyfin.androidtv.core.player

import android.os.SystemClock

/**
 * What the player's connections loaded in the last seconds. Media3's own estimate (`DefaultBandwidthMeter`) takes a sample
 * only when a transfer ends (`onTransferEnd`), and a file played as it is is one long transfer: the estimate stays where it
 * was until the title is over. So the bytes are counted as they are read (see [ThrottledDataSource]).
 */
class DownloadRate(private val clock: () -> Long = { SystemClock.elapsedRealtime() }) {
    private val times = ArrayDeque<Long>()
    private val sizes = ArrayDeque<Long>()

    /** [bytes] were read just now; reads within one bucket are added together. */
    @Synchronized
    fun record(bytes: Int) {
        if (bytes <= 0) return
        val now = clock()
        if (times.isNotEmpty() && now - times.last() < BUCKET_MS) {
            sizes[sizes.lastIndex] = sizes.last() + bytes
        } else {
            times.addLast(now)
            sizes.addLast(bytes.toLong())
        }
        prune(now)
    }

    /** Bit/s loaded over the last [windowMs]; 0 when nothing was. */
    @Synchronized
    fun bitsPerSecond(windowMs: Long): Long {
        val now = clock()
        prune(now)
        var total = 0L
        for (i in times.indices) if (now - times[i] <= windowMs) total += sizes[i]
        return total * 8_000 / windowMs
    }

    private fun prune(now: Long) {
        while (times.isNotEmpty() && now - times.first() > KEEP_MS) {
            times.removeFirst()
            sizes.removeFirst()
        }
    }

    internal companion object {
        const val BUCKET_MS = 100L
        const val KEEP_MS = 30_000L

        /** Fed by every connection of the player. */
        val shared = DownloadRate()
    }
}
