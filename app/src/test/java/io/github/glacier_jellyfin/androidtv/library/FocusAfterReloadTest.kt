package io.github.glacier_jellyfin.androidtv.library

import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FocusAfterReloadTest {
    private val before = listOf("a", "b", "c", "d")

    @Test
    fun keepsTheWantedCardWhileItIsThere() {
        assertEquals("c", focusAfterReload("c", before, listOf("a", "c", "d")))
    }

    @Test
    fun fallsBackToTheCardAtTheSamePosition() {
        assertEquals("d", focusAfterReload("c", before, listOf("a", "b", "d")))
    }

    @Test
    fun fallsBackToTheLastCardPastTheEnd() {
        assertEquals("b", focusAfterReload("d", before, listOf("a", "b")))
    }

    @Test
    fun hasNoCardWithoutAny() {
        assertNull(focusAfterReload("b", before, emptyList()))
    }

    @Test
    fun ignoresWhatWasNeverACard() {
        assertNull(focusAfterReload("folder", before, listOf("a")))
    }

    @Test
    fun memoryHandsTheViewedItemOverOnceForItsFolder() {
        val memory = BrowseMemory(MutableStateFlow<Any?>("a"), Dispatchers.Unconfined)
        val folder = UUID.randomUUID()
        val item = UUID.randomUUID()
        memory.photoViewed(folder, item)
        assertNull(memory.takeViewed(UUID.randomUUID()))
        assertEquals(item, memory.takeViewed(folder))
        assertNull(memory.takeViewed(folder))
    }
}
