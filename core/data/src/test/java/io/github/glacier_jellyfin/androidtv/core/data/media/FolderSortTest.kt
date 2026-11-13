package io.github.glacier_jellyfin.androidtv.core.data.media

import io.github.glacier_jellyfin.androidtv.core.data.settings.FolderSettings
import io.github.glacier_jellyfin.androidtv.core.data.settings.FolderSort
import java.time.LocalDateTime
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test

class FolderSortTest {

    private var next = 0L

    private fun photo(name: String, date: LocalDateTime?): FolderMedia =
        FolderMedia.Photo(MediaPhoto(UUID(0, next++), name, "thumb", "full"), date)

    private val beach = photo("Beach", LocalDateTime.of(2024, 8, 12, 14, 30))
    private val cake = photo("cake", LocalDateTime.of(2024, 3, 2, 16, 0))
    private val album = photo("Album", LocalDateTime.of(2024, 8, 12, 14, 30))
    private val scan = photo("Scan", null)
    private val media = listOf(beach, scan, cake, album)

    private fun names(settings: FolderSettings) = media.sortedFor(settings).map { it.name }

    @Test
    fun `by date the oldest comes first, the same time by name, undated last`() {
        assertEquals(listOf("cake", "Album", "Beach", "Scan"), names(FolderSettings(sort = FolderSort.Date)))
    }

    @Test
    fun `by date turned around the newest comes first and undated stay last`() {
        assertEquals(
            listOf("Beach", "Album", "cake", "Scan"),
            names(FolderSettings(sort = FolderSort.Date, descending = true)),
        )
    }

    @Test
    fun `by name upper and lower case count the same`() {
        assertEquals(listOf("Album", "Beach", "cake", "Scan"), names(FolderSettings(sort = FolderSort.Name)))
        assertEquals(
            listOf("Scan", "cake", "Beach", "Album"),
            names(FolderSettings(sort = FolderSort.Name, descending = true)),
        )
    }

    @Test
    fun `folders follow the same order`() {
        val folders = listOf(
            MediaFolder(UUID(1, 0), "Summer", LocalDateTime.of(2023, 7, 1, 0, 0)),
            MediaFolder(UUID(1, 1), "Archive", null),
            MediaFolder(UUID(1, 2), "Norway", LocalDateTime.of(2024, 7, 12, 9, 0)),
        )
        assertEquals(
            listOf("Norway", "Summer", "Archive"),
            folders.sortedFor(FolderSettings(sort = FolderSort.Date, descending = true)).map { it.name },
        )
        assertEquals(
            listOf("Archive", "Norway", "Summer"),
            folders.sortedFor(FolderSettings(sort = FolderSort.Name)).map { it.name },
        )
    }
}
