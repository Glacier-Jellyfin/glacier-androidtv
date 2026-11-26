package io.github.glacier_jellyfin.androidtv.core.data.media

import java.util.UUID
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoDeletionTest {
    private val photo = BaseItemDto(id = UUID(0, 1), type = BaseItemKind.PHOTO, canDelete = true, isFolder = false)

    @Test
    fun `only a photo with explicit delete permission can be deleted`() {
        assertTrue(photo.isDeletablePhoto)
        assertFalse(photo.copy(canDelete = null).isDeletablePhoto)
        assertFalse(photo.copy(canDelete = false).isDeletablePhoto)
    }

    @Test
    fun `other item types and folders are rejected even when deletable on the server`() {
        assertFalse(photo.copy(type = BaseItemKind.VIDEO).isDeletablePhoto)
        assertFalse(photo.copy(isFolder = true).isDeletablePhoto)
    }
}
