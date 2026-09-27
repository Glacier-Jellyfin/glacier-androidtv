package io.github.glacier_jellyfin.androidtv.core.data.media

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchMergeTest {

    @Test
    fun `groups keep their order and repeats are dropped`() {
        val merged = mergeResults(listOf("a", "b"), listOf("b", "c"), listOf("a", "d"), limit = 10) { it }
        assertEquals(listOf("a", "b", "c", "d"), merged)
    }

    @Test
    fun `stops at the limit`() {
        val merged = mergeResults(listOf("a", "b"), listOf("c", "d"), limit = 3) { it }
        assertEquals(listOf("a", "b", "c"), merged)
    }

    @Test
    fun `empty groups give an empty result`() {
        assertEquals(emptyList<String>(), mergeResults(emptyList<String>(), emptyList(), limit = 5) { it })
    }
}
