package io.github.glacier_jellyfin.androidtv.core.data.media

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test

class ActivityPeopleTest {

    private val ann = ActivityUser(UUID.fromString("00000000-0000-0000-0000-000000000001"), "Ann", "http://x/a")
    private val bob = ActivityUser(UUID.fromString("00000000-0000-0000-0000-000000000002"), "Bob", null)
    private val cy = UUID.fromString("00000000-0000-0000-0000-000000000003")
    private val di = UUID.fromString("00000000-0000-0000-0000-000000000004")

    @Test
    fun `the feed comes first, then hidden people who left it`() {
        val merged = mergeActivityPeople(
            feed = listOf(bob, ann),
            hidden = setOf(di.toString(), cy.toString(), ann.id.toString()),
            hiddenNames = mapOf(di.toString() to "Di", cy.toString() to "cy", ann.id.toString() to "Old Ann"),
        )
        assertEquals(listOf(bob, ann), merged.take(2))
        assertEquals(listOf("cy", "Di"), merged.drop(2).map { it.name })
        assertEquals(listOf(cy, di), merged.drop(2).map { it.id })
        assertEquals(listOf(null, null), merged.drop(2).map { it.imageUrl })
    }

    @Test
    fun `hidden ids without a usable name or id are left out`() {
        val merged = mergeActivityPeople(
            feed = emptyList(),
            hidden = setOf(cy.toString(), di.toString(), "not-a-uuid"),
            hiddenNames = mapOf(di.toString() to " ", "not-a-uuid" to "Eve"),
        )
        assertEquals(emptyList<ActivityUser>(), merged)
    }

    @Test
    fun `names of people who are not hidden are ignored`() {
        val merged = mergeActivityPeople(listOf(ann), emptySet(), mapOf(cy.toString() to "Cy"))
        assertEquals(listOf(ann), merged)
    }
}
