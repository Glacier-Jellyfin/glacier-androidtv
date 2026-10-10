package io.github.glacier_jellyfin.androidtv.core.data.media

/** One box of a channel's row in the programme guide: a programme, or a stretch the guide knows nothing about. */
data class GuideCell(val startMs: Long, val endMs: Long, val program: LiveProgram?) {
    fun covers(timeMs: Long): Boolean = timeMs in startMs until endMs
}

/**
 * A channel's row from [fromMs] to [toMs] without holes: its [programs]
 * (in any order, overlaps cut off) and, between them, cells without a
 * programme. A channel without guide data is one such cell.
 */
fun guideCells(programs: List<LiveProgram>, fromMs: Long, toMs: Long): List<GuideCell> {
    val cells = mutableListOf<GuideCell>()
    var at = fromMs
    for (program in programs.sortedBy { it.startMs }) {
        if (program.endMs <= at || program.startMs >= toMs) continue
        if (program.startMs > at) cells += GuideCell(at, program.startMs, null)
        val start = maxOf(program.startMs, at)
        val end = minOf(program.endMs, toMs)
        cells += GuideCell(start, end, program)
        at = end
    }
    if (at < toMs) cells += GuideCell(at, toMs, null)
    return cells
}

/** The cell at [timeMs], or the last one when the row ends before it. */
fun cellIndexAt(cells: List<GuideCell>, timeMs: Long): Int {
    if (cells.isEmpty()) return -1
    val index = cells.indexOfFirst { it.covers(timeMs) }
    return when {
        index >= 0 -> index
        timeMs < cells.first().startMs -> 0
        else -> cells.lastIndex
    }
}

/** [timeMs] rounded down to the half hour, the guide's grid. */
fun halfHourFloor(timeMs: Long): Long = timeMs - timeMs.mod(HALF_HOUR_MS)

const val HALF_HOUR_MS = 30 * 60_000L
