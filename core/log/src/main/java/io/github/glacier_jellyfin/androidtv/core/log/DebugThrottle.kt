package io.github.glacier_jellyfin.androidtv.core.log

/**
 * A cap on the speed of streams and of the bandwidth test, to try the app on a link that is slower
 * than the real one. Only debug builds set it (see `GlacierApplication`), so it stays at 0: no cap.
 */
object DebugThrottle {

    /** Bits per second all streams share; 0 for no cap. */
    @Volatile
    var bitsPerSecond: Long = 0
        set(value) {
            field = value
            synchronized(this) { nextFreeNs = 0 }
            if (value > 0) Log.w("Throttle", "Streams limited to ${value / 1000} kbit/s") else Log.w("Throttle", "Streams not limited")
        }

    private var nextFreeNs = 0L

    /**
     * Nanoseconds the caller has to wait before it may have moved [bytes] more: the time
     * they take at the cap, after what moved before. 0 without a cap.
     */
    fun waitNs(bytes: Int): Long {
        val limit = bitsPerSecond
        if (limit <= 0 || bytes <= 0) return 0
        synchronized(this) {
            val now = System.nanoTime()
            // A link that stood idle does not save up speed.
            if (nextFreeNs < now) nextFreeNs = now
            nextFreeNs += bytes * 8L * 1_000_000_000L / limit
            return nextFreeNs - now
        }
    }
}
