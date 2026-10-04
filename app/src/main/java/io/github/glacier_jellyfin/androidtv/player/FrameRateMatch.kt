package io.github.glacier_jellyfin.androidtv.player

import android.view.Display
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.core.content.ContextCompat
import io.github.glacier_jellyfin.androidtv.core.log.Log
import kotlin.math.abs

/** One picture mode of the TV, as far as matching cares. */
internal data class DisplayModeSpec(val id: Int, val width: Int, val height: Int, val refreshRate: Float)

/**
 * The mode to switch to for [fps], or null to stay on [current]. Only modes of the
 * current resolution count. Among those that show every frame equally often the
 * fastest wins (50 Hz over 25 Hz). Failing that, one within 0.5 % (24 Hz for a
 * 23.976 film) still beats the 3:2 judder of 60 Hz.
 */
internal fun pickDisplayMode(modes: List<DisplayModeSpec>, current: DisplayModeSpec, fps: Float): DisplayModeSpec? {
    if (fps <= 0) return null
    if (frameRateMatches(current.refreshRate, fps)) return null
    val sameSize = modes.filter { it.width == current.width && it.height == current.height }
    sameSize.filter { frameRateMatches(it.refreshRate, fps) }.maxByOrNull { it.refreshRate }?.let { return it }
    if (nearlyMatches(current.refreshRate, fps)) return null
    return sameSize.filter { nearlyMatches(it.refreshRate, fps) }.maxByOrNull { it.refreshRate }
}

private fun nearlyMatches(refreshHz: Float, fps: Float): Boolean {
    val ratio = refreshHz / fps
    val repeats = Math.round(ratio)
    return repeats >= 1 && abs(ratio - repeats) < 0.005f * repeats
}

private fun Display.Mode.spec() = DisplayModeSpec(modeId, physicalWidth, physicalHeight, refreshRate)

/**
 * Asks the TV for a picture mode that suits [fps] while this is shown; leaving
 * hands the choice back to the system. A null [fps] (not known yet) or
 * [enabled] false keeps the current mode.
 */
@Composable
internal fun MatchFrameRate(fps: Float?, enabled: Boolean) {
    val activity = LocalActivity.current ?: return
    DisposableEffect(activity, fps, enabled) {
        val window = activity.window
        if (enabled && fps != null) {
            val display = ContextCompat.getDisplayOrDefault(activity)
            val current = display.mode.spec()
            val mode = pickDisplayMode(display.supportedModes.map { it.spec() }, current, fps)
            if (mode != null) {
                Log.i(TAG, "Switching to ${mode.width}x${mode.height} ${refreshText(mode.refreshRate)} for $fps fps")
                window.attributes = window.attributes.apply { preferredDisplayModeId = mode.id }
            }
        }
        onDispose {
            if (window.attributes.preferredDisplayModeId != 0) {
                window.attributes = window.attributes.apply { preferredDisplayModeId = 0 }
            }
        }
    }
}

private const val TAG = "FrameRate"
