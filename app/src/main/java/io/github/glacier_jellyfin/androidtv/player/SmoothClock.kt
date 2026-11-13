package io.github.glacier_jellyfin.androidtv.player

import kotlin.math.abs

/**
 * Turns position samples that arrive in coarse, uneven ticks into a display value that
 * runs on continuously: it advances with the wall clock at the playback rate seen so
 * far and eases toward the samples instead of jumping to them. Pure, no Compose.
 */
class SmoothClock(private val snapMs: Long = SNAP_MS, private val easeMs: Long = EASE_MS) {
    var value = 0.0
        private set
    var rate = 1.0
        private set

    private var started = false
    private var lastSample = 0L
    private var lastSampleAt = 0L
    private var lastFrameAt = 0L
    private var wasPlaying = false

    /** A new [sample] (ms) arrived at wall-clock [now] (ms). */
    fun onSample(sample: Long, now: Long) {
        if (!started) {
            value = sample.toDouble()
            started = true
        } else if (wasPlaying) {
            val wall = now - lastSampleAt
            val moved = sample - lastSample
            if (abs(sample - value) > snapMs) {
                value = sample.toDouble()
            } else if (wall in MIN_WALL_MS..MAX_WALL_MS && moved >= 0) {
                rate += (moved.toDouble() / wall - rate) * RATE_SMOOTHING
                rate = rate.coerceIn(0.0, MAX_RATE)
            }
        } else {
            value = sample.toDouble()
        }
        lastSample = sample
        lastSampleAt = now
    }

    /** Advances to the frame at wall-clock [now]; returns the display value in ms. */
    fun onFrame(now: Long, playing: Boolean): Long {
        if (!started) return 0
        val dt = (now - lastFrameAt).coerceIn(0, MAX_FRAME_MS)
        if (playing && wasPlaying) {
            value += dt * rate
            val target = lastSample + (now - lastSampleAt) * rate
            val gap = target - value
            value = if (abs(gap) > snapMs) target else value + gap * (dt.toDouble() / easeMs).coerceAtMost(1.0)
        } else if (!playing) {
            // Paused: settle on the sample instead of drifting on.
            value = lastSample.toDouble()
        } else {
            // Resumed: continue from the sample the first frame counts from.
            lastSampleAt = now
        }
        wasPlaying = playing
        lastFrameAt = now
        return value.toLong()
    }

    companion object {
        const val SNAP_MS = 1_800L
        const val EASE_MS = 300L
        private const val MIN_WALL_MS = 200L
        private const val MAX_WALL_MS = 2_000L
        private const val MAX_FRAME_MS = 250L
        private const val MAX_RATE = 2.0
        private const val RATE_SMOOTHING = 0.3
    }
}
