package io.github.glacier_jellyfin.androidtv.core.data.settings

import java.io.ByteArrayInputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FavoriteRowsTest {

    private val all = FavoriteRow.entries.map { it.name }

    private suspend fun navigation(json: String): NavigationSettings =
        SettingsSerializer.readFrom(ByteArrayInputStream(json.toByteArray())).profiles.values.single().navigation

    @Test
    fun `the recordings row comes last`() {
        assertEquals(FavoriteRow.Recordings, FavoriteRow.entries.last())
    }

    @Test
    fun `rows saved before the recordings row existed get it last, shown`() = runTest {
        val old = """{"profiles":{"s|u":{"navigation":{"favoriteRows":[
            {"id":"Songs"},{"id":"Movies","shown":false},{"id":"Shows"},{"id":"Episodes"},{"id":"Albums"},{"id":"Artists"}
        ]}}}}"""
        val rows = navigation(old).favoriteRows.arranged(all)
        assertEquals(
            listOf("Songs", "Movies", "Shows", "Episodes", "Albums", "Artists", "Recordings"),
            rows.map { it.id },
        )
        assertTrue(rows.last().shown)
        assertEquals(false, rows.first { it.id == "Movies" }.shown)
    }

    @Test
    fun `a hidden recordings row stays hidden and keeps its place`() = runTest {
        val saved = """{"profiles":{"s|u":{"navigation":{"favoriteRows":[
            {"id":"Recordings","shown":false},{"id":"Movies"}
        ]}}}}"""
        val rows = navigation(saved).favoriteRows.arranged(all)
        assertEquals(listOf("Recordings", "Movies"), rows.map { it.id }.take(2))
        assertEquals(false, rows.first().shown)
    }

    @Test
    fun `a row of a later version is left out, the others stay`() = runTest {
        val saved = """{"profiles":{"s|u":{"navigation":{"favoriteRows":[
            {"id":"Podcasts"},{"id":"Songs"},{"id":"Movies"}
        ]}}}}"""
        val rows = navigation(saved).favoriteRows.arranged(all)
        assertEquals(all.size, rows.size)
        assertEquals(listOf("Songs", "Movies"), rows.map { it.id }.take(2))
        // What the favorites page does with each id must not throw.
        rows.forEach { FavoriteRow.valueOf(it.id) }
    }

    @Test
    fun `a move skips the rows not offered`() {
        val noLiveTv = { choice: Choice -> choice.id != FavoriteRow.Recordings.name }
        val moved = emptyList<Choice>().withMoved("Songs", 1, all, noLiveTv)
        assertEquals(all, moved.map { it.id })
        assertEquals(
            listOf("Movies", "Shows", "Episodes", "Albums", "Songs", "Artists", "Recordings"),
            emptyList<Choice>().withMoved("Songs", -1, all, noLiveTv).map { it.id },
        )
    }
}
