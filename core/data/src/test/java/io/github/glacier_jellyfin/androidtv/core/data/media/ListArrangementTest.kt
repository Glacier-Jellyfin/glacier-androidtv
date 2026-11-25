package io.github.glacier_jellyfin.androidtv.core.data.media

import java.time.LocalDate
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ListArrangementTest {

    private fun entry(
        name: String,
        year: Int? = null,
        premiere: LocalDate? = null,
        runtimeMinutes: Int? = null,
        artist: String? = null,
        album: String? = null,
        track: Int? = null,
    ) = IndexEntry(
        id = UUID.randomUUID(),
        name = name,
        year = year,
        premiereDay = premiere?.toEpochDay()?.toInt(),
        runtimeMs = runtimeMinutes?.let { it * 60_000L },
        artist = artist,
        albumId = album?.let { "id-$it" },
        album = album,
        track = track,
    )

    private fun movies(sort: LibrarySort, descending: Boolean = false) =
        LibraryQuery(LibraryKind.Movies, sort = sort, descending = descending)

    @Test
    fun `groups in order, names A to Z inside, unknown last`() {
        val entries = listOf(
            entry("Zulu", 2020),
            entry("Alpha", 2021),
            entry("Mike", 2020),
            entry("None"),
            entry("Bravo", 2020),
        )
        val list = ListArrangement.arrange(entries, movies(LibrarySort.Year))
        assertEquals(listOf("Bravo", "Mike", "Zulu", "Alpha", "None"), list.entries.map { it.name })
        assertEquals(ListGroup.Year(2020), list.groups!![0])
        assertEquals(ListGroup.Unknown, list.groups!!.last())
    }

    @Test
    fun `descending turns the groups round, not the names inside`() {
        val entries = listOf(entry("Zulu", 2020), entry("Alpha", 2021), entry("Mike", 2020), entry("None"))
        val list = ListArrangement.arrange(entries, movies(LibrarySort.Year, descending = true))
        assertEquals(listOf("Alpha", "Mike", "Zulu", "None"), list.entries.map { it.name })
    }

    @Test
    fun `titles of a year go by premiere date, oldest first`() {
        val entries = listOf(
            entry("Alpha", 2020, LocalDate.of(2020, 9, 1)),
            entry("Zulu", 2020, LocalDate.of(2020, 2, 1)),
            entry("Mike", 2020, LocalDate.of(2020, 5, 1)),
        )
        val list = ListArrangement.arrange(entries, movies(LibrarySort.Year))
        assertEquals(listOf("Zulu", "Mike", "Alpha"), list.entries.map { it.name })
    }

    @Test
    fun `descending gives the newest date first inside the year`() {
        val entries = listOf(
            entry("Alpha", 2020, LocalDate.of(2020, 2, 1)),
            entry("Zulu", 2021, LocalDate.of(2021, 1, 1)),
            entry("Mike", 2020, LocalDate.of(2020, 9, 1)),
        )
        val list = ListArrangement.arrange(entries, movies(LibrarySort.Year, descending = true))
        assertEquals(listOf("Zulu", "Mike", "Alpha"), list.entries.map { it.name })
    }

    @Test
    fun `undated titles of a year come after the dated ones, by name`() {
        val entries = listOf(
            entry("Bravo", 2020),
            entry("Zulu", 2020, LocalDate.of(2020, 8, 1)),
            entry("Alpha", 2020),
            entry("Mike", 2020, LocalDate.of(2020, 3, 1)),
        )
        val expected = listOf("Mike", "Zulu", "Alpha", "Bravo")
        assertEquals(expected, ListArrangement.arrange(entries, movies(LibrarySort.Year)).entries.map { it.name })
        val descending = ListArrangement.arrange(entries, movies(LibrarySort.Year, descending = true))
        assertEquals(listOf("Zulu", "Mike", "Alpha", "Bravo"), descending.entries.map { it.name })
    }

    @Test
    fun `by title there are no groups`() {
        val list = ListArrangement.arrange(listOf(entry("b"), entry("a")), movies(LibrarySort.Title, descending = true))
        assertEquals(listOf("b", "a"), list.entries.map { it.name })
        assertNull(list.groups)
        assertEquals('B', list.letterAt(0))
    }

    @Test
    fun `lengths go in steps that fit songs, albums and films`() {
        fun length(minutes: Int) = ListArrangement.group(entry("x", runtimeMinutes = minutes), LibrarySort.Runtime)
        assertEquals(ListGroup.Length(3, 4), length(3))
        assertEquals(ListGroup.Length(40, 50), length(45))
        assertEquals(ListGroup.Length(90, 120), length(118))
        assertEquals(ListGroup.Unknown, length(0))
    }

    @Test
    fun `songs of an album go by track, and the rail files them under the album`() {
        val query = LibraryQuery(LibraryKind.Music, scope = LibraryScope.Songs, sort = LibrarySort.Album)
        val entries = listOf(
            entry("Zed", album = "Blue", track = 1),
            entry("Abc", album = "Blue", track = 2),
            entry("Mid", album = "Avalon", track = 1),
        )
        val list = ListArrangement.arrange(entries, query)
        assertEquals(listOf("Mid", "Zed", "Abc"), list.entries.map { it.name })
        assertEquals(listOf('A', 'B', 'B'), (0 until list.size).map { list.letterAt(it) })
    }

    @Test
    fun `no rail letter without a group name`() {
        val query = LibraryQuery(LibraryKind.Music, scope = LibraryScope.Albums, sort = LibrarySort.Artist)
        val list = ListArrangement.arrange(listOf(entry("a", artist = "Zappa"), entry("b")), query)
        assertEquals(listOf('Z', null), (0 until list.size).map { list.letterAt(it) })
        assertNull(ListArrangement.arrange(listOf(entry("a", 2000)), movies(LibrarySort.Year)).letterAt(0))
    }

    @Test
    fun `rows start at each group`() {
        // Groups of 5 (2019) and 4 (2020) titles in rows of 3: rows start at 0, 3, 5, 8.
        val entries = (1..5).map { entry("a$it", 2019) } + (1..4).map { entry("b$it", 2020) }
        val list = ListArrangement.arrange(entries, movies(LibrarySort.Year))
        assertEquals(listOf(0, 0, 0, 3, 3, 5, 5, 5, 8), (0 until list.size).map { list.rowStart(it, 3) })
        val plain = ListArrangement.arrange(entries, movies(LibrarySort.Title))
        assertEquals(6, plain.rowStart(7, 3))
    }
}
