package io.github.glacier_jellyfin.androidtv.core.data.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryQueryTest {

    @Test
    fun `genres and artists ignore a descending sort`() {
        assertFalse(LibraryQuery(LibraryKind.Movies, scope = LibraryScope.Genres, descending = true).orderDescending)
        assertFalse(LibraryQuery(LibraryKind.Music, scope = LibraryScope.Artists, descending = true).orderDescending)
    }

    @Test
    fun `other scopes keep the chosen order`() {
        assertTrue(LibraryQuery(LibraryKind.Movies, scope = LibraryScope.All, descending = true).orderDescending)
        assertFalse(LibraryQuery(LibraryKind.Movies, scope = LibraryScope.All, descending = false).orderDescending)
        assertTrue(LibraryQuery(LibraryKind.Music, scope = LibraryScope.Albums, descending = true).orderDescending)
    }
}
