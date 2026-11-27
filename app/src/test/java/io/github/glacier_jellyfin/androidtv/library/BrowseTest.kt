package io.github.glacier_jellyfin.androidtv.library

import org.junit.Assert.assertEquals
import org.junit.Test

class BrowseTest {

    private val abc = Browse(listOf("a", "b", "c"), 1)

    @Test
    fun `deleting the current item stays at the same place, the next one`() {
        assertEquals(Browse(listOf("a", "c"), 1), abc.without(1))
    }

    @Test
    fun `deleting the last item moves to the previous one`() {
        assertEquals(Browse(listOf("a", "b"), 1), Browse(listOf("a", "b", "c"), 2).without(2))
    }

    @Test
    fun `deleting the only item leaves an empty list`() {
        assertEquals(Browse(emptyList<String>(), 0), Browse(listOf("a"), 0).without(0))
    }

    @Test
    fun `deleting an item before the current one keeps the shown item`() {
        assertEquals(Browse(listOf("b", "c"), 0), abc.without(0))
    }

    @Test
    fun `a position outside the list changes nothing`() {
        assertEquals(abc, abc.without(5))
    }

    @Test
    fun `restoring puts the item back and keeps the shown one`() {
        val deleted = abc.without(1)
        assertEquals(Browse(listOf("a", "b", "c"), 2), deleted.with("b", 1))
    }

    @Test
    fun `restoring after browsing on keeps the shown item`() {
        val deleted = Browse(listOf("a", "b", "c"), 2).without(1)
        assertEquals(Browse(listOf("a", "b", "c"), 2), deleted.with("b", 1))
    }

    @Test
    fun `restoring behind the shown item leaves the position`() {
        assertEquals(Browse(listOf("a", "b", "c"), 0), Browse(listOf("a", "b"), 0).with("c", 2))
    }

    @Test
    fun `restoring into an empty list shows it`() {
        assertEquals(Browse(listOf("a"), 0), Browse(emptyList<String>(), 0).with("a", 0))
    }

    @Test
    fun `a position past the end puts it last`() {
        assertEquals(Browse(listOf("a", "b", "z"), 0), Browse(listOf("a", "b"), 0).with("z", 9))
    }
}
