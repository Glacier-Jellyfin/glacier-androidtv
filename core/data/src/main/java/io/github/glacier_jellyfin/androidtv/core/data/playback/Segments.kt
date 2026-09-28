package io.github.glacier_jellyfin.androidtv.core.data.playback

import kotlinx.serialization.Serializable

/** Parts of a video the server knows about (Jellyfin media segments). */
@Serializable
enum class SegmentKind { Intro, Recap, Preview, Commercial, Outro }

data class MediaSegment(val kind: SegmentKind, val startMs: Long, val endMs: Long) {
    operator fun contains(positionMs: Long): Boolean = positionMs in startMs until endMs
}

/**
 * One segment per stretch: servers with several segment providers report the
 * same intro twice with slightly different bounds (21–112 s and 23–113 s),
 * which would bring the skip button back for a second after skipping.
 * Overlapping or touching segments of the same kind become one; sorted by start.
 */
fun mergeOverlapping(segments: List<MediaSegment>): List<MediaSegment> =
    segments.groupBy { it.kind }.values.flatMap { sameKind ->
        sameKind.sortedBy { it.startMs }.fold(mutableListOf<MediaSegment>()) { merged, next ->
            val last = merged.lastOrNull()
            if (last != null && next.startMs <= last.endMs) {
                merged[merged.lastIndex] = last.copy(endMs = maxOf(last.endMs, next.endMs))
            } else {
                merged += next
            }
            merged
        }
    }.sortedBy { it.startMs }

/** What the player does when playback enters a segment. */
@Serializable
enum class SegmentAction { None, Ask, Skip }

/** When the "Up next" card appears. */
sealed interface UpNextMode {
    data object Off : UpNextMode
    /** When the credits start; without an outro segment [FALLBACK_MS] before the end. */
    data object WithCredits : UpNextMode
    data class Before(val ms: Long) : UpNextMode

    companion object {
        const val FALLBACK_MS = 30_000L
    }
}

/**
 * Segment and "Up next" behaviour, set in Settings › Playback. The defaults
 * are the design's: ask for intro and outro, skip recaps, ignore previews
 * and ads, "Up next" with the credits.
 */
data class SegmentPolicy(
    val actions: Map<SegmentKind, SegmentAction> = DefaultActions,
    val upNext: UpNextMode = UpNextMode.WithCredits,
) {
    companion object {
        val DefaultActions: Map<SegmentKind, SegmentAction> = mapOf(
            SegmentKind.Intro to SegmentAction.Ask,
            SegmentKind.Recap to SegmentAction.Skip,
            SegmentKind.Preview to SegmentAction.None,
            SegmentKind.Commercial to SegmentAction.None,
            SegmentKind.Outro to SegmentAction.Ask,
        )
    }

    fun action(kind: SegmentKind): SegmentAction = actions[kind] ?: SegmentAction.None

    /** The segment playing at [positionMs], unless it is set to "no action". */
    fun active(segments: List<MediaSegment>, positionMs: Long): MediaSegment? =
        segments.firstOrNull { positionMs in it && action(it.kind) != SegmentAction.None }

    /** Where the "Up next" card appears; null when it is switched off or the length is unknown. */
    fun upNextAtMs(segments: List<MediaSegment>, durationMs: Long): Long? {
        if (durationMs <= 0) return null
        return when (val mode = upNext) {
            UpNextMode.Off -> null
            UpNextMode.WithCredits -> segments.firstOrNull { it.kind == SegmentKind.Outro }?.startMs
                ?: (durationMs - UpNextMode.FALLBACK_MS)
            is UpNextMode.Before -> durationMs - mode.ms
        }?.coerceAtLeast(0)
    }
}
