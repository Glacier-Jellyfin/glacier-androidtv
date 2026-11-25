package io.github.glacier_jellyfin.androidtv.core.player

/** Decides whether the app has to turn a video itself, see [StreamRequest.videoRotation]. */
object VideoRotation {
    private const val QUARTER_TURN = 90
    private const val FULL_TURN = 360

    /** The rotation as 0, 90, 180 or 270; anything that is not a multiple of 90 counts as upright. */
    fun normalized(degrees: Int?): Int {
        if (degrees == null || degrees % QUARTER_TURN != 0) return 0
        return ((degrees % FULL_TURN) + FULL_TURN) % FULL_TURN
    }

    /** True for a turned picture, which the player rotates in its effects pipeline. */
    fun appliedByApp(degrees: Int?): Boolean = normalized(degrees) != 0
}
