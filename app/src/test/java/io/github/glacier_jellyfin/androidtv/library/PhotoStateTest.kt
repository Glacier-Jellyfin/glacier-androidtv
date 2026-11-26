package io.github.glacier_jellyfin.androidtv.library

import io.github.glacier_jellyfin.androidtv.core.data.media.FolderMedia
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaPhoto
import java.time.LocalDateTime
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoStateTest {
    private val first = photo(1)
    private val second = photo(2)
    private val third = photo(3)

    @Test
    fun `deleting a photo shows the next item and clears the confirmation`() {
        val state = PhotoState(
            media = listOf(first, second, third),
            index = 1,
            deleteTarget = second.photo,
            deleting = true,
        )

        val result = state.afterPhotoDeleted(second.id)

        assertEquals(listOf(first, third), result.media)
        assertEquals(third, result.current)
        assertTrue(result.forward)
        assertNull(result.deleteTarget)
        assertFalse(result.deleting)
    }

    @Test
    fun `deleting the last photo shows the previous item`() {
        val state = PhotoState(media = listOf(first, second), index = 1)

        val result = state.afterPhotoDeleted(second.id)

        assertEquals(first, result.current)
        assertFalse(result.forward)
    }

    @Test
    fun `deleting the only photo leaves an empty viewer`() {
        val result = PhotoState(media = listOf(first)).afterPhotoDeleted(first.id)

        assertTrue(result.media.isEmpty())
        assertEquals(0, result.index)
        assertNull(result.current)
    }

    @Test
    fun `the capture date takes precedence over file timestamps`() {
        val capture = LocalDateTime.of(2024, 6, 15, 9, 0)
        val photo = first.copy(photo = first.photo.copy(takenAt = capture), date = capture.minusDays(2))
        val state = PhotoState(media = listOf(photo), modifiedPhoto = photo.id to capture.minusDays(1))

        assertEquals(capture, state.captionDate)
    }

    @Test
    fun `without a capture date the earliest available file timestamp is shown`() {
        val created = LocalDateTime.of(2024, 6, 15, 9, 0)
        val photo = first.copy(date = created)
        val state = PhotoState(media = listOf(photo))
        val earlier = created.minusDays(1)

        assertEquals(earlier, state.copy(modifiedPhoto = photo.id to earlier).captionDate)
        assertEquals(created, state.copy(modifiedPhoto = photo.id to created.plusDays(1)).captionDate)
        assertEquals(created, state.captionDate)
        assertEquals(earlier, PhotoState(media = listOf(first), modifiedPhoto = first.id to earlier).captionDate)
    }

    @Test
    fun `the previous photo's modification date is ignored`() {
        val modified = LocalDateTime.of(2024, 6, 15, 9, 0)
        val state = PhotoState(media = listOf(first, second), index = 1, modifiedPhoto = first.id to modified)

        assertNull(state.captionDate)
    }

    private fun photo(id: Long) = FolderMedia.Photo(MediaPhoto(UUID(0, id), "Photo $id", "thumb", "full"), null)
}
