package io.github.glacier_jellyfin.androidtv.core.data.media

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

class SpotlightPoolTest {

    private fun pool(started: List<String>, other: List<List<String>>, count: Int, seed: Int = 1) =
        spotlightPool(started, other, count, Random(seed)) { it }

    @Test
    fun `continue watching comes first in its own order`() {
        val result = pool(listOf("c1", "c2"), listOf(listOf("r1", "r2"), listOf("f1")), count = 5)
        assertEquals(listOf("c1", "c2"), result.take(2))
        assertEquals(setOf("r1", "r2", "f1"), result.drop(2).toSet())
    }

    @Test
    fun `a title in several sources shows once`() {
        val result = pool(listOf("a"), listOf(listOf("a", "b"), listOf("b", "c")), count = 10)
        assertEquals("a", result.first())
        assertEquals(listOf("b", "c"), result.drop(1).sorted())
    }

    @Test
    fun `the other sources are mixed together`() {
        val other = listOf((1..10).map { "r$it" }, (1..10).map { "f$it" })
        val orders = (1..20).map { pool(emptyList(), other, count = 20, seed = it) }.toSet()
        assertEquals(true, orders.size > 1)
        orders.forEach { assertEquals(other.flatten().toSet(), it.toSet()) }
    }

    @Test
    fun `the pool is cut to the count, continue watching kept first`() {
        val result = pool(listOf("c1", "c2", "c3"), listOf(listOf("r1", "r2")), count = 3)
        assertEquals(listOf("c1", "c2", "c3"), result)
    }
}
