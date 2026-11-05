package io.github.glacier_jellyfin.androidtv.core.player

/**
 * Decides when a bitstream output (AC-3, E-AC-3, DTS, TrueHD, DTS-HD to the
 * receiver) needs help, from what the audio sink sees. Two cases:
 *
 * - **A silent track.** Its position stands still although the sink plays and
 *   still holds data. Some TVs never resume a paused passthrough track.
 *   [onPosition] then asks for a new track, and after the flush that follows,
 *   [holdWrites] gives the old one a moment to close: a track opened while
 *   the last one still closes can come up just as silent.
 * - **A short HDMI drop.** The track dies (a dead AudioTrack) while the TV or
 *   receiver switches modes. [onDeadTrack] starts a hold in which the sink
 *   waits for the link to come back instead of failing, so the player does
 *   not switch to a decoder for a moment's drop.
 *
 * Nothing here acts on decoded sound or on a track that plays. The caller
 * passes the time in milliseconds, so tests drive the clock.
 */
internal class BitstreamGuard(
    /** How long a played track may stand still before it counts as silent. */
    private val stallMs: Long = 1_000,
    /** The same before a track has played at all: some outputs take this long to start a bitstream. */
    private val startupStallMs: Long = 4_000,
    private val recoveryIntervalMs: Long = 5_000,
    /** New tracks in a row that never played, after which the guard gives up. */
    private val maxRecoveries: Int = 3,
    /** The pause before a new track, longer after each one that did not play. */
    private val settleMs: LongArray = longArrayOf(300, 600, 1_000),
    /** The longest wait for the HDMI link, counted from its first drop. */
    private val routeHoldMs: Long = 5_000,
) {
    private var bitstream = false
    private var playing = false
    private var lastPositionUs = NO_POSITION
    private var lastMoveMs = 0L
    /** The position has moved since the track was set up. */
    private var moved = false
    private var recoveries = 0
    private var lastRecoveryMs = -recoveryIntervalMs
    /** A new track was asked for, and the flush that brings it has not come yet. */
    private var recovering = false
    private var settleUntilMs = 0L

    /** Waiting for the HDMI link after a dead track; the sink keeps its own capability changes back meanwhile. */
    var holdingRoute = false
        private set
    private var routeHoldStartMs = 0L
    private var routeDeadlineMs = NO_DEADLINE

    fun onConfigure(bitstream: Boolean, nowMs: Long) {
        this.bitstream = bitstream
        moved = false
        resetBaseline(nowMs)
    }

    fun onPlay(nowMs: Long) {
        playing = true
        resetBaseline(nowMs)
    }

    fun onPause() {
        playing = false
    }

    fun onFlush(nowMs: Long) {
        if (recovering) {
            recovering = false
            settleUntilMs = nowMs + settleMs[(recoveries - 1).coerceIn(0, settleMs.size - 1)]
        }
        moved = false
        resetBaseline(nowMs)
    }

    fun onReset(nowMs: Long) {
        bitstream = false
        recovering = false
        recoveries = 0
        settleUntilMs = 0
        holdingRoute = false
        routeDeadlineMs = NO_DEADLINE
        moved = false
        resetBaseline(nowMs)
    }

    /** While true the sink takes no data, so the old track closes before the next write opens a new one. */
    fun holdWrites(nowMs: Long): Boolean = nowMs < settleUntilMs

    /**
     * Every position the sink reports ([positionUs], [NO_POSITION] while it
     * has none). True once the track counts as silent: the caller then has
     * the player flush the sink, which opens a new track.
     */
    fun onPosition(positionUs: Long, pendingData: Boolean, nowMs: Long): Boolean {
        if (positionUs == NO_POSITION) return false
        if (positionUs != lastPositionUs) {
            // The first position after a reset only sets the baseline; a frozen new track cannot pass as moving.
            val seeded = lastPositionUs != NO_POSITION
            lastPositionUs = positionUs
            lastMoveMs = nowMs
            if (seeded) {
                moved = true
                recoveries = 0
            }
            return false
        }
        if (!bitstream || !playing || !pendingData || recovering || holdingRoute) return false
        if (nowMs - lastMoveMs < if (moved) stallMs else startupStallMs) return false
        if (recoveries >= maxRecoveries || nowMs - lastRecoveryMs < recoveryIntervalMs) return false
        recovering = true
        recoveries++
        lastRecoveryMs = nowMs
        return true
    }

    /**
     * The bitstream track died. True if the sink should wait for the link
     * ([routeDecision]); false if it should fail as usual: decoded sound, or
     * the link has had its time.
     */
    fun onDeadTrack(nowMs: Long): Boolean {
        if (!bitstream) return false
        // A drop soon after the last one belongs to the same switch and shares its time.
        if (routeDeadlineMs == NO_DEADLINE || nowMs - routeHoldStartMs > 2 * routeHoldMs) {
            routeHoldStartMs = nowMs
            routeDeadlineMs = nowMs + routeHoldMs
        }
        holdingRoute = nowMs < routeDeadlineMs
        return holdingRoute
    }

    /** What the sink does with the next write while [holdingRoute]; [routeBack]: the output takes the format again. */
    fun routeDecision(routeBack: Boolean, nowMs: Long): RouteDecision = when {
        !holdingRoute -> RouteDecision.Write
        nowMs >= routeDeadlineMs -> {
            holdingRoute = false
            RouteDecision.GiveUp
        }
        routeBack -> RouteDecision.Write
        else -> RouteDecision.Wait
    }

    /** A write went through: the link is back. */
    fun onWritten() {
        holdingRoute = false
    }

    private fun resetBaseline(nowMs: Long) {
        lastPositionUs = NO_POSITION
        lastMoveMs = nowMs
    }

    enum class RouteDecision {
        /** Write as usual; during a hold this opens the track again. */
        Write,

        /** Refuse the write, the renderer offers it again. */
        Wait,

        /** The link did not come back: fail with the dead track's error. */
        GiveUp,
    }

    companion object {
        /** AudioSink.CURRENT_POSITION_NOT_SET. */
        const val NO_POSITION = Long.MIN_VALUE
        private const val NO_DEADLINE = Long.MIN_VALUE
    }
}
