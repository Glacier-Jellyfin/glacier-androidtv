package io.github.glacier_jellyfin.androidtv.core.designsystem

import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RowFocusTest {
    @Test
    fun rememberedItemWinsOverEntryAndFirst() {
        assertEquals("last", pickRowTarget("last", "entry", "first"))
    }

    @Test
    fun entryWinsOverFirst() {
        assertEquals("entry", pickRowTarget(null, "entry", "first"))
    }

    @Test
    fun firstEntryLandsOnTheFirstItem() {
        assertEquals("first", pickRowTarget<String>(null, null, "first"))
    }

    @Test
    fun nothingKnownLeavesTheChoiceToCompose() {
        assertNull(pickRowTarget<String>(null, null, null))
    }

    @Test
    fun resetForgetsTheRememberedItem() {
        val focus = RowFocus().also { it.lastKey = "a" }
        focus.reset()
        assertNull(focus.lastKey)
    }

    @Test
    fun firstKeyIsTheKeyOfIndexZero() {
        val focus = RowFocus()
        FakeScope().rememberingFocus(focus).apply {
            items(3, key = { "k$it" }) { }
            item(key = "x") { }
        }
        assertEquals("k0", focus.firstKey)
    }

    @Test
    fun firstKeyComesFromALeadingItemAcrossCalls() {
        val focus = RowFocus()
        FakeScope().rememberingFocus(focus).apply {
            item(key = "head") { }
            items(2, key = { "k$it" }) { }
        }
        assertEquals("head", focus.firstKey)
    }

    @Test
    fun unkeyedItemsGetIndexKeys() {
        val focus = RowFocus()
        FakeScope().rememberingFocus(focus).apply { items(2) { } }
        assertEquals("index-0", focus.firstKey)
    }

    @Test
    fun emptyRowHasNoFirstKey() {
        val focus = RowFocus().also { it.firstKey = "old" }
        FakeScope().rememberingFocus(focus).apply { items(0, key = { it }) { } }
        assertNull(focus.firstKey)
    }

    @Test
    fun listHelpersRouteThroughTheInterceptedItems() {
        val focus = RowFocus()
        val fake = FakeScope()
        fake.rememberingFocus(focus).apply {
            items(listOf("a", "b"), key = { "id-$it" }) { }
        }
        assertEquals("id-a", focus.firstKey)
        assertEquals(1, fake.itemsCalls)
    }

    private class FakeScope : LazyListScope {
        var itemsCalls = 0

        override fun item(
            key: Any?,
            contentType: Any?,
            content: @Composable LazyItemScope.() -> Unit,
        ) = Unit

        override fun items(
            count: Int,
            key: ((index: Int) -> Any)?,
            contentType: (index: Int) -> Any?,
            itemContent: @Composable LazyItemScope.(index: Int) -> Unit,
        ) {
            itemsCalls++
        }
    }
}
