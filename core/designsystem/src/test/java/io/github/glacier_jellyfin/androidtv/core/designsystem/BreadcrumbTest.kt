package io.github.glacier_jellyfin.androidtv.core.designsystem

import org.junit.Assert.assertEquals
import org.junit.Test

class BreadcrumbTest {
    private val widths = listOf(100, 100, 100, 100)

    private fun start(available: Int, keepFrom: Int = 3): Int =
        breadcrumbStart(widths, keepFrom, lead = 20, dot = 10, ellipsis = 20, gap = 5, available = available)

    @Test
    fun `everything is shown when it fits`() {
        // 20 + 4 * 100 + 3 * 10 = 450 and 7 gaps of 5 = 485.
        assertEquals(0, start(available = 485))
    }

    @Test
    fun `the oldest levels give way first`() {
        assertEquals(1, start(available = 484))
        // Dropping one level swaps it for the ellipsis and a dot: 20 + 20 + 10 + 3 * 100 + 2 * 10 = 370 and 7 gaps.
        assertEquals(1, start(available = 405))
        assertEquals(2, start(available = 404))
    }

    @Test
    fun `the kept level stays even when it does not fit`() {
        assertEquals(3, start(available = 10))
        assertEquals(2, start(available = 10, keepFrom = 2))
    }
}
