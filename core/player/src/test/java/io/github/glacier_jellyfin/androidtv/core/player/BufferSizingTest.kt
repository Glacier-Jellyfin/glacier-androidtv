package io.github.glacier_jellyfin.androidtv.core.player

import org.junit.Assert.assertEquals
import org.junit.Test

class BufferSizingTest {
    private val mb = 1024L * 1024L
    private val floor = (131_072_000 + 13_107_200)

    @Test
    fun smallHeapKeepsTheFloor() {
        assertEquals(floor, BufferSizing.targetBytes(256 * mb, floor))
    }

    @Test
    fun midHeapTakesFortyPercent() {
        assertEquals((512 * mb / 100 * 50).toInt(), BufferSizing.targetBytes(512 * mb, floor).also { assert(it > floor) })
    }

    @Test
    fun bigHeapIsCappedAt400Mb() {
        assertEquals((400 * mb).toInt(), BufferSizing.targetBytes(2048 * mb, floor))
    }
}
