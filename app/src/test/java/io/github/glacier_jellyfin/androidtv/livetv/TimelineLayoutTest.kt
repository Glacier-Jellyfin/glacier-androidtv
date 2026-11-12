package io.github.glacier_jellyfin.androidtv.livetv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineLayoutTest {
    private val minute = 60_000L
    private val window = 180 * minute
    private val width = 1344f

    private fun assertNoOverlap(stations: List<TimelineStation>) {
        for (lane in listOf(true, false)) {
            val labels = stations.filter { it.above == lane && it.labelWidth > 0 }
            labels.zipWithNext { a, b ->
                assertTrue("$a overlaps $b", a.labelLeft + a.labelWidth <= b.labelLeft)
            }
        }
        stations.forEach { assertTrue(it.labelLeft + it.labelWidth.coerceAtLeast(0f) <= width) }
    }

    @Test
    fun `dense short programmes never overlap`() {
        val starts = (0 until 36).map { it * 5 * minute }
        val stations = timelineStations(starts, 0, window, width)
        assertNoOverlap(stations)
        assertTrue(stations.zipWithNext().all { (a, b) -> a.above != b.above })
    }

    @Test
    fun `one long programme gets the capped width`() {
        val stations = timelineStations(listOf(0L), 0, window, width)
        assertEquals(1, stations.size)
        assertEquals(300f, stations[0].labelWidth, 0.01f)
        assertNoOverlap(stations)
    }

    @Test
    fun `a programme started before the window sits at its start`() {
        val stations = timelineStations(listOf(-20 * minute, 30 * minute, 60 * minute, 120 * minute), 0, window, width)
        assertEquals(TIMELINE_INSET, stations[0].x, 0.01f)
        assertNoOverlap(stations)
    }

    @Test
    fun `uneven programmes never overlap`() {
        val starts = listOf(0L, 3, 10, 11, 60, 61, 62, 150, 170).map { it * minute }
        assertNoOverlap(timelineStations(starts, 0, window, width))
    }

    @Test
    fun `short programmes keep three hours`() {
        val starts = (0 until 8).map { it * 20 * minute }
        assertEquals(180 * minute, timelineWindowEnd(0, starts, 24 * 60 * minute))
    }

    @Test
    fun `long programmes extend to reach six stations`() {
        val starts = (0 until 8).map { it * 45 * minute }
        assertEquals(5 * 45 * minute + 30 * minute, timelineWindowEnd(0, starts, 24 * 60 * minute))
    }

    @Test
    fun `window is capped at six hours`() {
        val starts = (0 until 8).map { it * 90 * minute }
        assertEquals(360 * minute, timelineWindowEnd(0, starts, 24 * 60 * minute))
    }

    @Test
    fun `window ends with the guide data but lasts an hour at least`() {
        assertEquals(150 * minute, timelineWindowEnd(0, listOf(0L, 60 * minute), 150 * minute))
        assertEquals(60 * minute, timelineWindowEnd(0, listOf(0L), 20 * minute))
    }
}
