package io.github.glacier_jellyfin.androidtv.player

/**
 * Tells that the link loads less than the stream plays, faster than the buffer's trend: while the player has been loading
 * without a pause for [loadingMs], the rate it loaded at is under [factor] of the stream's, and the buffer is not full
 * (a full buffer is why a player does not load). Called every few seconds; fires once per [cooldownMs] at most.
 */
internal class ThroughputWatch(
    private val loadingMs: Long = LOADING_MS,
    private val factor: Double = FACTOR,
    private val cooldownMs: Long = COOLDOWN_MS,
) {
    private var loadingSince = NONE
    private var lastAlarm = NONE

    /**
     * One check. [rateBps]: bit/s loaded over the last [LOADING_MS] or less (the caller's window must not reach back before
     * the loading began); [streamBps]: what the stream asks; [full]: the buffer is as full as it gets.
     */
    fun check(
        nowMs: Long,
        loading: Boolean,
        rateBps: Long,
        streamBps: Long,
        full: Boolean,
    ): Boolean {
        if (!loading || full || streamBps <= 0) {
            loadingSince = NONE
            return false
        }
        if (loadingSince == NONE) loadingSince = nowMs
        if (nowMs - loadingSince < loadingMs) return false
        if (rateBps >= streamBps * factor) return false
        if (lastAlarm != NONE && nowMs - lastAlarm < cooldownMs) return false
        lastAlarm = nowMs
        loadingSince = NONE
        return true
    }

    fun clear() {
        loadingSince = NONE
    }

    companion object {
        const val LOADING_MS = 10_000L
        const val FACTOR = 0.95
        const val COOLDOWN_MS = 30_000L

        /** The window of the rate: inside the span of loading the alarm waits for. */
        const val WINDOW_MS = 8_000L

        /** A buffer within this of its target counts as full. */
        const val FULL_MARGIN_MS = 5_000L
        private const val NONE = Long.MIN_VALUE
    }
}
