package io.github.glacier_jellyfin.androidtv.player

import io.github.glacier_jellyfin.androidtv.core.data.playback.StreamBudget

/** What set off a step to another stream. */
internal enum class HandoverTrigger {
    /** A measurement says the link does not carry the stream. */
    Link,

    /** The buffer fell while the player was loading: the link is slower than the stream. */
    Draining,

    /** Playback ran dry (once for long, or twice). */
    Stalls,

    /** The link loads less per second than the stream plays (measured on the bytes loaded). */
    Throughput,
}

internal sealed interface HandoverPlan {
    /** Hand the title over to a new stream at [atMs], [leadMs] after the position. */
    data class At(val atMs: Long, val leadMs: Long) : HandoverPlan

    /** Too near the end of the title for a hand over, and nothing has stalled: leave it as it is. */
    data object Wait : HandoverPlan

    /** Open the title again, as before. */
    data object Reopen : HandoverPlan
}

/**
 * Decides whether a switch between the file and a converted stream (see `GlacierPlayback.handOver`) can run in the playing
 * player, and where. The new stream needs time: the server takes 16 to 34 s to start a transcode, which the playing stream
 * has to bridge from its buffer. Without that much buffer the title is opened again, as it was before hand overs.
 */
internal object HandoverPlanner {
    /**
     * The new stream takes over at least this long after the position. The server needs 16 to 34 s to start a transcode, so a
     * short lead lets the player wait at the hand over; that is still shorter than a stall plus opening the title again.
     */
    const val MIN_LEAD_MS = 8_000L
    const val MAX_LEAD_MS = 90_000L

    /** What stays buffered beyond the point of the hand over. */
    const val RESERVE_MS = 5_000L

    /** Back to the file: it starts fast, so a short lead is enough, but the buffer must be worth it. */
    const val RETURN_MIN_LEAD_MS = 15_000L
    const val RETURN_MIN_BUFFER_MS = 30_000L

    /** Within this of the end of the title nothing is handed over. */
    const val TAIL_MS = 20_000L

    /**
     * After opening the new stream, this much of the lead must be left. Little is enough going down: discarding the stream
     * would end in a stall and opening the title again, worse than a wait at the hand over.
     */
    const val MIN_REMAINING_DOWN_MS = 3_000L
    const val MIN_REMAINING_BACK_MS = 8_000L

    /**
     * A step down to a smaller stream. [aheadMs] is what is buffered. The lead is that minus the reserve; from [MIN_LEAD_MS]
     * on the stream is handed over, else the title is opened again (waiting would only end in a stall and that). Near the
     * end of the title the title is opened again after a stall, else left as it is.
     */
    fun lower(trigger: HandoverTrigger, aheadMs: Long, positionMs: Long, durationMs: Long): HandoverPlan {
        val lead = aheadMs - RESERVE_MS
        val boundedLead = lead.coerceIn(0, MAX_LEAD_MS)
        if (nearEnd(positionMs + boundedLead, durationMs)) {
            return if (trigger == HandoverTrigger.Stalls) HandoverPlan.Reopen else HandoverPlan.Wait
        }
        if (lead < MIN_LEAD_MS) return HandoverPlan.Reopen
        return HandoverPlan.At(positionMs + boundedLead, boundedLead)
    }

    /** The way back from a reduced stream to the file: only with a buffer of [RETURN_MIN_BUFFER_MS]. */
    fun back(aheadMs: Long, positionMs: Long, durationMs: Long): HandoverPlan {
        if (aheadMs < RETURN_MIN_BUFFER_MS) return HandoverPlan.Wait
        val lead = (aheadMs - RESERVE_MS).coerceIn(RETURN_MIN_LEAD_MS, MAX_LEAD_MS)
        if (nearEnd(positionMs + lead, durationMs)) return HandoverPlan.Wait
        return HandoverPlan.At(positionMs + lead, lead)
    }

    /**
     * Whether a ladder, moved down for the link, goes back to the file rather than up a tier. Only a title that plays as the
     * file without a limit ([fileFits]) has a file to go back to, and only when the link carries [fileBitrate] with room to
     * spare ([budget]), the buffer is worth the hand over, no other hand over runs and the returns are not used up. A ladder
     * that merely raised its tier would stay a transcode however fast the link is.
     */
    fun backToFile(
        fileFits: Boolean,
        fileBitrate: Int?,
        budget: Int,
        aheadMs: Long,
        handoverActive: Boolean,
        returns: Int,
        maxReturns: Int,
    ): Boolean = fileFits && fileBitrate != null && !handoverActive && returns < maxReturns &&
        aheadMs >= RETURN_MIN_BUFFER_MS && StreamBudget.returnsTo(fileBitrate, budget)

    /** The most the way back to the file moves its hand over point to where the file can be entered without a pre-roll. */
    const val MAX_SYNC_SHIFT_MS = 30_000L

    /**
     * How far the hand over point may move on to a sync point of the file, or a negative number for not at all. The playing
     * stream has [bufferedBeyondMs] beyond the planned point, and must keep [RESERVE_MS] of it past the new point while the
     * next stream loads ahead ([loadAheadMs], what the playing stream still loads meanwhile) so the move does not run dry.
     */
    fun syncShiftLimit(bufferedBeyondMs: Long, loadAheadMs: Long): Long =
        minOf(MAX_SYNC_SHIFT_MS, bufferedBeyondMs - RESERVE_MS - loadAheadMs)

    /** True when the new stream is open and enough of the lead is left for it to start before the hand over. */
    fun stillWorks(lowering: Boolean, atMs: Long, positionMs: Long): Boolean =
        atMs - positionMs >= if (lowering) MIN_REMAINING_DOWN_MS else MIN_REMAINING_BACK_MS

    private fun nearEnd(atMs: Long, durationMs: Long) = durationMs > 0 && atMs > durationMs - TAIL_MS
}

/**
 * Tells that the buffer is draining while the player loads: over [spanMs] it fell by [dropMs] or more. The player loads at
 * most half as fast as it plays then, so the link is slower than the stream, seen long before the buffer is empty.
 */
internal class BufferTrend(
    private val spanMs: Long = 30_000,
    private val dropMs: Long = 15_000,
    private val keepMs: Long = 120_000,
) {
    private val samples = ArrayDeque<Pair<Long, Long>>()

    /** One sample; true when the buffer has been draining. Not loading (a full buffer, a pause) starts the count again. */
    fun record(nowMs: Long, aheadMs: Long, loading: Boolean): Boolean {
        if (!loading) {
            samples.clear()
            return false
        }
        samples.addLast(nowMs to aheadMs)
        while (samples.first().first < nowMs - keepMs) samples.removeFirst()
        val draining = samples.any { (at, ahead) -> nowMs - at >= spanMs && ahead - aheadMs >= dropMs }
        if (draining) samples.clear()
        return draining
    }

    fun clear() = samples.clear()
}
