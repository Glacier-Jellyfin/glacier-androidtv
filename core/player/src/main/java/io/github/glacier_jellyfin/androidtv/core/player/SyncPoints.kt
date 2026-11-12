package io.github.glacier_jellyfin.androidtv.core.player

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.SeekMap
import io.github.glacier_jellyfin.androidtv.core.log.Log

/**
 * Where the files of Matroska can be entered. Only the cue points of a Matroska file are seekable, and they are 20 to 40 s
 * apart while the keyframes are 3 to 10 s apart: a stream opened between two cue points starts at the one before, decodes up
 * to the wanted position and discards it, which took 4.6 s on a Fire TV. A stream that is handed over to can start at a cue
 * point instead and has no such pre-roll, if the cue points are known: the player has read them when it played the file, or
 * any open of the same file before, and keeps them here.
 */
@OptIn(UnstableApi::class)
object SyncPoints {
    private const val CAPACITY = 4

    // Access order, so the file that was looked at last stays.
    private val known = object : LinkedHashMap<String, SeekMap>(CAPACITY + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SeekMap>) = size > CAPACITY
    }

    /**
     * What identifies the file of a stream URL: its path and the media source. The session, the token and the other
     * parameters differ from one open to the next, the file does not. Null for what is no stream of the server's files.
     */
    fun keyOf(url: String): String? {
        val withoutFragment = url.substringBefore('#')
        val path = withoutFragment.substringBefore('?')
        val sourceId = withoutFragment.substringAfter('?', "").split('&')
            .firstOrNull { it.substringBefore('=').equals("mediaSourceId", ignoreCase = true) }
            ?.substringAfter('=', "")
            ?.takeIf { it.isNotEmpty() }
        return if (sourceId == null) null else "$path?mediaSourceId=${sourceId.lowercase()}"
    }

    private val waiting = HashMap<String, (SeekMap) -> Unit>()

    /** The seek points of the file with [key]; only a map with more than one point is worth keeping. */
    fun remember(key: String, seekMap: SeekMap) {
        if (!hasSeveralPoints(seekMap)) return
        val action = synchronized(known) {
            known[key] = seekMap
            waiting.remove(key)
        }
        action?.invoke(seekMap)
        val mid = seekMap.durationUs.takeIf { it > 0 }?.div(2) ?: 0
        val points = seekMap.getSeekPoints(mid)
        Log.i("Player", "Seek points of $key: known, around ${mid / 1_000_000} s ${points.first.timeUs / 1000} to ${points.second.timeUs / 1000} ms")
    }

    /**
     * Once the file with [key] has its seek points known, [action] is called with them, once and on the thread that loads
     * them. A later call for the same key replaces the earlier one.
     */
    fun whenKnown(key: String, action: (SeekMap) -> Unit) {
        synchronized(known) { waiting[key] = action }
    }

    /** The last seek point of the file of [url] at or before [atMs], if it is known and no more than [maxShiftMs] earlier. */
    fun atOrBefore(url: String, atMs: Long, maxShiftMs: Long): Long? {
        val key = keyOf(url) ?: return null
        val seekMap = synchronized(known) { known[key] } ?: return null
        return lastAtOrBefore(seekMap, atMs, maxShiftMs)
    }

    /** The first seek point of the file of [url] at or after [atMs], if it is known and no more than [maxShiftMs] later. */
    fun atOrAfter(url: String, atMs: Long, maxShiftMs: Long): Long? {
        val key = keyOf(url) ?: return null
        val seekMap = synchronized(known) { known[key] } ?: return null
        return firstAtOrAfter(seekMap, atMs, maxShiftMs)
    }

    internal fun clear() = synchronized(known) {
        known.clear()
        waiting.clear()
    }

    /** Whether the map offers more than the start of the file: a seekable one with seek points that differ. */
    internal fun hasSeveralPoints(seekMap: SeekMap): Boolean {
        if (!seekMap.isSeekable) return false
        val duration = seekMap.durationUs
        // A few places along the file, off the round numbers a cue point may sit on; a single point gives the same answer everywhere.
        val probes = if (duration > 0) (1..9).map { duration / 10 * it + 1_000 } else listOf(1_000_000L, 60_000_000L, 600_000_000L)
        return probes.any { seekMap.getSeekPoints(it).let { points -> points.first != points.second } }
    }

    /** The first of the two seek points around [atMs] that is not before it, unless it is more than [maxShiftMs] later. */
    internal fun firstAtOrAfter(seekMap: SeekMap, atMs: Long, maxShiftMs: Long): Long? {
        val points = seekMap.getSeekPoints(atMs * 1000)
        val pointMs = listOf(points.first, points.second).map { it.timeUs / 1000 }.firstOrNull { it >= atMs } ?: return null
        return pointMs.takeIf { it - atMs <= maxShiftMs }
    }

    /** The last of the two seek points around [atMs] that is not after it, unless it is more than [maxShiftMs] earlier. */
    internal fun lastAtOrBefore(seekMap: SeekMap, atMs: Long, maxShiftMs: Long): Long? {
        val points = seekMap.getSeekPoints(atMs * 1000)
        val pointMs = listOf(points.second, points.first).map { it.timeUs / 1000 }.firstOrNull { it <= atMs } ?: return null
        return pointMs.takeIf { atMs - it <= maxShiftMs }
    }

    /** The most a start moves back to a seek point: the viewer waits for the picture either way, and 45 s of replay is no harm. */
    const val START_SNAP_MAX_MS = 45_000L

    /**
     * Whether a start at [startMs] moves back to [pointMs], the seek point at or before it. Only a start that comes from outside
     * the player ([external]: the detail page, a resume) does; a seek of the viewer, a hand over or a reopen after a stall
     * must stay where it is. A point further back than [START_SNAP_MAX_MS] is not taken.
     */
    fun snapsStart(external: Boolean, startMs: Long, pointMs: Long?): Boolean =
        external && pointMs != null && pointMs < startMs && startMs - pointMs <= START_SNAP_MAX_MS
}
