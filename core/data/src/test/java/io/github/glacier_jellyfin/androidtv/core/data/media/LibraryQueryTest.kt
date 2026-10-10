package io.github.glacier_jellyfin.androidtv.core.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryQueryTest {

    @Test
    fun `genres ignore a descending sort`() {
        assertFalse(LibraryQuery(LibraryKind.Movies, scope = LibraryScope.Genres, descending = true).orderDescending)
    }

    @Test
    fun `other scopes keep the chosen order`() {
        assertTrue(LibraryQuery(LibraryKind.Movies, scope = LibraryScope.All, descending = true).orderDescending)
        assertFalse(LibraryQuery(LibraryKind.Movies, scope = LibraryScope.All, descending = false).orderDescending)
        assertTrue(LibraryQuery(LibraryKind.Music, scope = LibraryScope.Albums, descending = true).orderDescending)
        assertTrue(LibraryQuery(LibraryKind.Music, scope = LibraryScope.Artists, descending = true).orderDescending)
    }

    @Test
    fun `music has a songs tab between artists and playlists`() {
        assertEquals(
            listOf(LibraryScope.Albums, LibraryScope.Artists, LibraryScope.Songs, LibraryScope.Playlists),
            LibraryQuery.scopes(LibraryKind.Music, inGenre = false),
        )
    }

    @Test
    fun `each tab offers only the sorts that fit it`() {
        assertEquals(
            listOf(LibrarySort.DateAdded, LibrarySort.Title),
            LibraryQuery(LibraryKind.Music, scope = LibraryScope.Artists).sorts,
        )
        assertTrue(LibrarySort.Album in LibraryQuery(LibraryKind.Music, scope = LibraryScope.Songs).sorts)
        assertFalse(LibrarySort.Artist in LibraryQuery(LibraryKind.Movies).sorts)
    }

    @Test
    fun `rail letters ignore case and accents`() {
        assertEquals('E', railLetter("étoile"))
        assertEquals('B', railLetter(" bend"))
        assertEquals('#', railLetter("0001 - 0003 - Bend"))
        assertEquals('#', railLetter(""))
    }

    @Test
    fun `music tabs keep their own sort, albums under the old key`() {
        assertEquals("Music", LibraryQuery(LibraryKind.Music, scope = LibraryScope.Albums).sortKey)
        assertEquals("Music.Artists", LibraryQuery(LibraryKind.Music, scope = LibraryScope.Artists).sortKey)
        assertEquals("Movies", LibraryQuery(LibraryKind.Movies, scope = LibraryScope.Genres).sortKey)
    }
}
