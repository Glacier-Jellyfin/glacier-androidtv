package io.github.glacier_jellyfin.androidtv.core.data.media

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackdropRefsTest {

    private val item = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val parent = UUID.fromString("00000000-0000-0000-0000-000000000002")

    @Test
    fun `keeps server order with the list position as index`() {
        val refs = backdropRefs(item, listOf("a", "b", "c"), null, null)
        assertEquals(listOf(BackdropRef(item, "a", 0), BackdropRef(item, "b", 1), BackdropRef(item, "c", 2)), refs)
    }

    @Test
    fun `skips blank tags without shifting the indices`() {
        val refs = backdropRefs(item, listOf("a", " ", null, "d"), null, null)
        assertEquals(listOf(BackdropRef(item, "a", 0), BackdropRef(item, "d", 3)), refs)
    }

    @Test
    fun `falls back to the parent's backdrops`() {
        val refs = backdropRefs(item, listOf("", null), parent, listOf("p0", "p1"))
        assertEquals(listOf(BackdropRef(parent, "p0", 0), BackdropRef(parent, "p1", 1)), refs)
    }

    @Test
    fun `own backdrops win over the parent's`() {
        val refs = backdropRefs(item, listOf("a"), parent, listOf("p0"))
        assertEquals(listOf(BackdropRef(item, "a", 0)), refs)
    }

    @Test
    fun `no backdrops gives an empty list`() {
        assertTrue(backdropRefs(item, null, null, null).isEmpty())
        assertTrue(backdropRefs(item, emptyList(), parent, null).isEmpty())
        assertTrue(backdropRefs(item, emptyList(), null, listOf("p0")).isEmpty())
    }
}
