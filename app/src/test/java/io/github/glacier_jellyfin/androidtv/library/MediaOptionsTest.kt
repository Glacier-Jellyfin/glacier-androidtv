package io.github.glacier_jellyfin.androidtv.library

import io.github.glacier_jellyfin.androidtv.core.data.media.FolderContent
import io.github.glacier_jellyfin.androidtv.core.data.media.FolderMedia
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaPhoto
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaOptionsTest {
    private fun photo(canDelete: Boolean, id: UUID = UUID.randomUUID()) =
        FolderMedia.Photo(MediaPhoto(id, "p", "thumb", "full"), date = null, canDelete = canDelete)

    @Test
    fun aPhotoOpensStartsTheSlideshowAndDeletesWhenAllowed() {
        assertEquals(
            listOf(MediaAction.Open, MediaAction.Slideshow, MediaAction.Delete),
            mediaActions(photo(canDelete = true)),
        )
    }

    @Test
    fun aPhotoWithoutDeleteRightsKeepsTwoActions() {
        assertEquals(listOf(MediaAction.Open, MediaAction.Slideshow), mediaActions(photo(canDelete = false)))
    }

    @Test
    fun withoutMediaDropsOnlyThatItem() {
        val keep = photo(canDelete = true)
        val drop = photo(canDelete = true)
        val content = FolderContent(null, emptyList(), listOf(keep, drop))
        assertEquals(listOf<FolderMedia>(keep), content.withoutMedia(drop.id).media)
    }
}
