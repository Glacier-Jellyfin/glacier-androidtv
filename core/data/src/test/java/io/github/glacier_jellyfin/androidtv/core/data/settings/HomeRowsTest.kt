package io.github.glacier_jellyfin.androidtv.core.data.settings

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.ByteArrayInputStream

class HomeRowsTest {

    private val libraries = listOf("movies", "shows")

    private fun HomeSettings.order(libraries: List<String> = this@HomeRowsTest.libraries) = homeRows(libraries).map { it.id }

    @Test
    fun `new in is one row per library, at the place of the general entry`() {
        assertEquals(
            listOf("ContinueWatching", "Favorites", "Latest:movies", "Latest:shows", "RecentAlbums", "FavoriteSongs", "Libraries"),
            HomeSettings().order(),
        )
    }

    @Test
    fun `rows the saved list does not know come last`() {
        val settings = HomeSettings(rows = listOf(HomeRowChoice(HomeRow.Libraries), HomeRowChoice(HomeRow.Favorites, shown = false)))
        assertEquals(
            listOf("Libraries", "Favorites", "ContinueWatching", "Latest:movies", "Latest:shows", "RecentAlbums", "FavoriteSongs"),
            settings.order(),
        )
        assertFalse(settings.homeRows(libraries).first { it.row == HomeRow.Favorites }.shown)
    }

    @Test
    fun `moving swaps with the neighbour and stops at the ends`() {
        val settings = HomeSettings()
        assertEquals(
            listOf("ContinueWatching", "Favorites", "Latest:shows", "Latest:movies", "RecentAlbums", "FavoriteSongs", "Libraries"),
            settings.withRowMoved("Latest:shows", -1, libraries).order(),
        )
        assertEquals(settings.order(), settings.withRowMoved("ContinueWatching", -1, libraries).order())
        assertEquals(settings.order(), settings.withRowMoved("Libraries", 1, libraries).order())
    }

    @Test
    fun `moving skips rows hidden in the settings`() {
        val noMusic = { choice: HomeRowChoice -> choice.row != HomeRow.RecentAlbums && choice.row != HomeRow.FavoriteSongs }
        assertEquals(
            listOf("ContinueWatching", "Favorites", "Latest:movies", "Libraries", "RecentAlbums", "FavoriteSongs", "Latest:shows"),
            HomeSettings().withRowMoved("Libraries", -1, libraries, noMusic).order(),
        )
    }

    @Test
    fun `a library added later joins the other new in rows`() {
        val moved = HomeSettings().withRowMoved("Latest:shows", -1, libraries).withRowShown("Latest:movies", false, libraries)
        val withMusic = moved.homeRows(libraries + "music")
        assertEquals(
            listOf("ContinueWatching", "Favorites", "Latest:shows", "Latest:movies", "Latest:music", "RecentAlbums", "FavoriteSongs", "Libraries"),
            withMusic.map { it.id },
        )
        assertFalse(withMusic.first { it.id == "Latest:movies" }.shown)
    }

    @Test
    fun `a row of a later version is dropped`() = runTest {
        // Settings written by a newer version with a row this one does not know.
        val newer = """{"profiles":{"s|u":{"home":{"rows":[{"row":"Future"},{"row":"Libraries","shown":false}]}}}}"""
        val home = SettingsSerializer.readFrom(ByteArrayInputStream(newer.toByteArray())).profiles.values.single().home
        val rows = home.homeRows(libraries)
        assertEquals("Libraries", rows.first().id)
        assertFalse(rows.first().shown)
        assertEquals(HomeRow.entries.size - 1 + libraries.size, rows.size)
    }
}
