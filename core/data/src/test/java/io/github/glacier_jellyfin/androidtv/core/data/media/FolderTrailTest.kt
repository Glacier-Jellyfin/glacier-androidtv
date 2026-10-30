package io.github.glacier_jellyfin.androidtv.core.data.media

import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

class FolderTrailTest {

    private fun item(n: Int, type: BaseItemKind, name: String) = BaseItemDto(id = UUID(0, n.toLong()), type = type, name = name)

    /** Ancestors of "Summer holiday" as the server sends them: parent first, up to the root. */
    private val ancestors = listOf(
        item(1, BaseItemKind.FOLDER, "2024"),
        item(2, BaseItemKind.COLLECTION_FOLDER, "Family"),
        item(3, BaseItemKind.AGGREGATE_FOLDER, "root"),
    )

    @Test
    fun `the path runs from the top down and leaves out the server's root`() {
        assertEquals(listOf("Family", "2024"), trail(ancestors, severalLibraries = true))
    }

    @Test
    fun `the only library is left out`() {
        assertEquals(listOf("2024"), trail(ancestors, severalLibraries = false))
    }

    @Test
    fun `a folder right in the only library has no path`() {
        assertEquals(emptyList<String>(), trail(ancestors.drop(1), severalLibraries = false))
    }

    @Test
    fun `no library among the ancestors gives no path`() {
        assertEquals(emptyList<String>(), trail(listOf(item(3, BaseItemKind.AGGREGATE_FOLDER, "root")), severalLibraries = true))
    }
}
