package io.github.glacier_jellyfin.androidtv.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpwardTargetTest {
    private val library = LibraryRoute("Shows", "lib", title = "TV")
    private val series = DetailRoute("series")
    private val episode = DetailRoute("episode", themeArea = "series")

    @Test
    fun `a library below title pages is gone back to`() {
        assertEquals(0, upwardPopIndex(listOf(library, series, episode), LibraryRoute("Shows", "lib")))
    }

    @Test
    fun `the show is gone back to from its episode`() {
        assertEquals(1, upwardPopIndex(listOf(library, series, episode), DetailRoute("series", seasonId = "s1")))
    }

    @Test
    fun `a page not on the back stack replaces the current one`() {
        assertNull(upwardPopIndex(listOf(HomeRoute, episode), DetailRoute("series")))
        assertNull(upwardPopIndex(listOf(HomeRoute, episode), LibraryRoute("Shows", "lib")))
    }

    @Test
    fun `another page in between replaces the current one`() {
        assertNull(upwardPopIndex(listOf(series, SearchRoute(), episode), DetailRoute("series")))
        assertNull(upwardPopIndex(listOf(library, SearchRoute(), series), LibraryRoute("Shows", "lib")))
    }

    @Test
    fun `the nearest match wins`() {
        val other = DetailRoute("other")
        assertEquals(2, upwardPopIndex(listOf(series, other, series, episode), DetailRoute("series")))
    }

    @Test
    fun `a library with another id or a genre is another place`() {
        val genre = LibraryRoute("Shows", "lib", genreId = "g")
        assertNull(upwardPopIndex(listOf(library, series), LibraryRoute("Shows", "other")))
        assertNull(upwardPopIndex(listOf(library, series), LibraryRoute("Movies", "lib")))
        assertNull(upwardPopIndex(listOf(genre, series), LibraryRoute("Shows", "lib")))
    }

    @Test
    fun `the page of a whole kind counts for each of its libraries`() {
        val all = LibraryRoute("Shows")
        assertEquals(0, upwardPopIndex(listOf(all, series), LibraryRoute("Shows", "lib")))
        assertNull(upwardPopIndex(listOf(library, series), LibraryRoute("Shows")))
    }

    @Test
    fun `a library on another tab is another place`() {
        val artists = LibraryRoute("Music", "lib", scope = "Artists")
        assertEquals(0, upwardPopIndex(listOf(artists, series), LibraryRoute("Music", "lib", scope = "Artists")))
        assertNull(upwardPopIndex(listOf(artists, series), LibraryRoute("Music", "lib")))
        assertNull(
            upwardPopIndex(
                listOf(LibraryRoute("Music", "lib"), series),
                LibraryRoute("Music", "lib", scope = "Artists"),
            ),
        )
    }

    @Test
    fun `a folder is gone back to through the folders below it`() {
        val stack = listOf(FolderRoute(), FolderRoute("a", "A"), FolderRoute("b", "B"))
        assertEquals(0, upwardPopIndex(stack, FolderRoute()))
        assertEquals(1, upwardPopIndex(stack, FolderRoute("a")))
    }

    @Test
    fun `a folder that is not below the current one replaces it`() {
        assertNull(upwardPopIndex(listOf(FolderRoute("a", "A"), FolderRoute("b", "B")), FolderRoute("c")))
    }

    @Test
    fun `folders do not lie between a title and a library`() {
        assertNull(upwardPopIndex(listOf(library, FolderRoute("a", "A"), series), LibraryRoute("Shows", "lib")))
    }

    @Test
    fun `a current page that is not a title or folder replaces`() {
        assertNull(upwardPopIndex(listOf(series, SettingsRoute), DetailRoute("series")))
        assertNull(upwardPopIndex(emptyList(), DetailRoute("series")))
    }
}
