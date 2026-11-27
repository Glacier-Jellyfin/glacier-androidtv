package io.github.glacier_jellyfin.androidtv.activity

import io.github.glacier_jellyfin.androidtv.core.data.media.ActivityUser
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WatchersSummaryTest {

    private fun people(vararg names: String) = names.mapIndexed { i, n -> ActivityUser(UUID(0, i.toLong()), n, null) }

    @Test
    fun `nobody gives no line`() = assertNull(watchersSummary(emptyList(), series = false))

    @Test
    fun `one person is named alone`() {
        val summary = watchersSummary(people("Alex"), series = false)
        assertEquals(WatchersSummary(SummaryKind.Watched, SummaryForm.One, listOf("Alex"), 0), summary)
    }

    @Test
    fun `two people are both named`() {
        val summary = watchersSummary(people("Alex", "Nora"), series = true)
        assertEquals(WatchersSummary(SummaryKind.Watching, SummaryForm.Two, listOf("Alex", "Nora"), 0), summary)
    }

    @Test
    fun `three or more name the first two and count the rest`() {
        val summary = watchersSummary(people("Alex", "Nora", "Sam", "Kim"), series = false)
        assertEquals(WatchersSummary(SummaryKind.Watched, SummaryForm.Many, listOf("Alex", "Nora"), 2), summary)
        assertEquals(1, watchersSummary(people("A", "B", "C"), series = true)?.others)
    }
}
