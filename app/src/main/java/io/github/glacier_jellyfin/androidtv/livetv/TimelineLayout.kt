package io.github.glacier_jellyfin.androidtv.livetv

/** One station of the programme line: its dot, the lane of its label and the label's hard bounds. All values in dp. */
internal data class TimelineStation(
    /** Dot centre, measured from the component's start edge. */
    val x: Float,
    /** Even stations put their label above the rail, odd ones below. */
    val above: Boolean,
    /** Where the label's start edge sits, measured from the component's start edge. */
    val labelLeft: Float,
    /** The most width the label may use, 0 or less meaning there is no room. */
    val labelWidth: Float,
)

internal const val TIMELINE_INSET = 8f
internal const val TIMELINE_DOT_SHIFT = 6f
internal const val TIMELINE_GAP = 16f
internal const val TIMELINE_MAX_LABEL = 300f

/**
 * Places a station for every start time. The labels of one lane are every second station, and each one ends
 * [TIMELINE_GAP] before the start of the next label in its lane (the last two end at the component's edge),
 * so labels of a lane cannot overlap whatever the programmes' lengths.
 */
internal fun timelineStations(
    startsMs: List<Long>,
    windowStartMs: Long,
    windowMs: Long,
    totalWidth: Float,
): List<TimelineStation> {
    val railWidth = (totalWidth - 2 * TIMELINE_INSET).coerceAtLeast(0f)
    val xs = startsMs.map {
        TIMELINE_INSET + railWidth * (
            (it - windowStartMs).coerceIn(
                0L,
                windowMs,
            ).toFloat() / windowMs
            )
    }
    return xs.indices.map { i ->
        val left = xs[i] - TIMELINE_DOT_SHIFT
        val limit = if (i + 2 < xs.size) xs[i + 2] - TIMELINE_DOT_SHIFT - TIMELINE_GAP else totalWidth
        TimelineStation(
            xs[i],
            above = i % 2 == 0,
            labelLeft = left,
            labelWidth = minOf(limit - left, TIMELINE_MAX_LABEL),
        )
    }
}

private const val MINUTE_MS = 60_000L
internal const val TIMELINE_BASE_MS = 180 * MINUTE_MS
internal const val TIMELINE_MAX_MS = 360 * MINUTE_MS
internal const val TIMELINE_MIN_MS = 60 * MINUTE_MS
internal const val TIMELINE_STATIONS_WANTED = 6
internal const val TIMELINE_TAIL_MS = 30 * MINUTE_MS

/**
 * The end of the window that starts at [windowStartMs]: three hours, or further when that shows fewer than
 * [TIMELINE_STATIONS_WANTED] stations (the sixth one's start plus 30 minutes), never more than six hours and never
 * past [lastEndMs], the end of the last known programme, though at least an hour.
 * [startsMs] are the starts of the programmes that end after the window start, in order.
 */
internal fun timelineWindowEnd(
    windowStartMs: Long,
    startsMs: List<Long>,
    lastEndMs: Long,
): Long {
    var end = windowStartMs + TIMELINE_BASE_MS
    startsMs.getOrNull(TIMELINE_STATIONS_WANTED - 1)?.let { end = maxOf(end, it + TIMELINE_TAIL_MS) }
    end = minOf(end, windowStartMs + TIMELINE_MAX_MS)
    end = minOf(end, lastEndMs)
    return maxOf(end, windowStartMs + TIMELINE_MIN_MS)
}
