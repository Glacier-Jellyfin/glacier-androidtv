package io.github.glacier_jellyfin.androidtv.core.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

class GuideTest {

    private val channelId = UUID.randomUUID()

    private fun program(title: String, start: Long, end: Long) =
        LiveProgram(title, channelId, title, null, null, start, end, null, null, new = false)

    @Test
    fun `a row without guide data is one empty cell`() {
        assertEquals(listOf(GuideCell(0, 100, null)), guideCells(emptyList(), 0, 100))
    }

    @Test
    fun `gaps between programmes become empty cells`() {
        val a = program("a", 10, 40)
        val b = program("b", 60, 90)
        assertEquals(
            listOf(GuideCell(0, 10, null), GuideCell(10, 40, a), GuideCell(40, 60, null), GuideCell(60, 90, b), GuideCell(90, 100, null)),
            guideCells(listOf(b, a), 0, 100),
        )
    }

    @Test
    fun `programmes are cut to the window and overlaps dropped`() {
        val a = program("a", -50, 30)
        val b = program("b", 20, 70)
        val c = program("c", 70, 200)
        assertEquals(
            listOf(GuideCell(0, 30, a), GuideCell(30, 70, b), GuideCell(70, 100, c)),
            guideCells(listOf(a, b, c), 0, 100),
        )
    }

    @Test
    fun `the cell at a time, or the nearest end`() {
        val cells = guideCells(listOf(program("a", 0, 50), program("b", 50, 100)), 0, 100)
        assertEquals(0, cellIndexAt(cells, 10))
        assertEquals(1, cellIndexAt(cells, 50))
        assertEquals(1, cellIndexAt(cells, 500))
        assertEquals(0, cellIndexAt(cells, -5))
        assertEquals(-1, cellIndexAt(emptyList(), 5))
        assertNull(cells.firstOrNull { it.program == null })
    }

    @Test
    fun `half hours round down`() {
        assertEquals(0L, halfHourFloor(HALF_HOUR_MS - 1))
        assertEquals(HALF_HOUR_MS, halfHourFloor(HALF_HOUR_MS + 5))
    }
}
