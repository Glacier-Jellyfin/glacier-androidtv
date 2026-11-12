package io.github.glacier_jellyfin.androidtv.player

/** Where a seek to a position of the title goes. */
internal sealed interface SeekTarget {
    /** In the item that plays, at [positionMs] of the player. */
    data class Here(val positionMs: Long) : SeekTarget

    /** In the stream handed over to, which is not playing yet, at [positionMs] of its item. */
    data class Next(val positionMs: Long) : SeekTarget

    /** Before where the playing stream begins: the title is opened again at [atMs]. */
    data class Reopen(val atMs: Long) : SeekTarget
}

/**
 * The one place that turns the player's positions into the title's. After a hand over the stream's item starts at
 * [offsetMs] of the title, and the player counts from there; everything that shows or reports a position, or seeks, goes
 * through this.
 */
internal class TitleTime {
    /** Where in the title the player's position 0 is. */
    var offsetMs = 0L
        private set

    private var fullMs = 0L

    fun position(playerMs: Long) = offsetMs + playerMs

    /** The length of the title: the item spans it from [offsetMs] on; the last length seen counts while the player does not know one. */
    fun duration(playerDurationMs: Long): Long {
        if (playerDurationMs > 0) fullMs = offsetMs + playerDurationMs
        return fullMs
    }

    /** A new stream plays from [offsetMs] of the title. */
    fun startAt(offsetMs: Long) {
        this.offsetMs = offsetMs
    }

    fun reset() {
        offsetMs = 0
        fullMs = 0
    }

    /** [handOverAtMs]: where the queued stream takes over, null without one. */
    fun seek(targetMs: Long, handOverAtMs: Long?): SeekTarget = when {
        targetMs < offsetMs -> SeekTarget.Reopen(targetMs)
        handOverAtMs != null && targetMs >= handOverAtMs -> SeekTarget.Next(targetMs - handOverAtMs)
        else -> SeekTarget.Here(targetMs - offsetMs)
    }
}
