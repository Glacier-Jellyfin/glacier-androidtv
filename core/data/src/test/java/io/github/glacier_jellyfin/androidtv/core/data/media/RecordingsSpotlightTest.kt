package io.github.glacier_jellyfin.androidtv.core.data.media

import io.github.glacier_jellyfin.androidtv.core.data.settings.SpotlightType
import java.time.LocalDateTime
import java.util.UUID
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.UserItemDataDto
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingsSpotlightTest {

    private fun dto(
        name: String,
        type: BaseItemKind,
        day: Int,
        played: Boolean = false,
    ) = BaseItemDto(
        id = UUID.nameUUIDFromBytes(name.toByteArray()),
        type = type,
        name = name,
        dateCreated = LocalDateTime.of(2026, 11, day, 20, 0),
        userData = UserItemDataDto(
            playbackPositionTicks = 0,
            playCount = 0,
            isFavorite = false,
            played = played,
            key = name,
            itemId = UUID.nameUUIDFromBytes(name.toByteArray()),
        ),
    )

    private val all = listOf(
        dto("old movie", BaseItemKind.MOVIE, 1),
        dto("new episode", BaseItemKind.EPISODE, 20),
        dto("seen movie", BaseItemKind.MOVIE, 10, played = true),
        dto("show", BaseItemKind.SERIES, 5),
        dto("clip", BaseItemKind.VIDEO, 30),
    )

    private fun List<BaseItemDto>.names() = map { it.name }

    @Test
    fun `the newest recordings come first and are cut to the limit`() {
        assertEquals(listOf("clip", "new episode"), all.newestRecordings(2).names())
    }

    @Test
    fun `movies means recorded movies`() {
        assertEquals(
            listOf("seen movie", "old movie"),
            all.spotlightRecordings(SpotlightType.Movies, unwatched = false).names(),
        )
    }

    @Test
    fun `shows means recorded episodes and shows`() {
        assertEquals(
            listOf("new episode", "show"),
            all.spotlightRecordings(SpotlightType.Shows, unwatched = false).names(),
        )
    }

    @Test
    fun `all means movies and shows, nothing else`() {
        assertEquals(
            listOf("new episode", "seen movie", "show", "old movie"),
            all.spotlightRecordings(SpotlightType.All, unwatched = false).names(),
        )
    }

    @Test
    fun `unwatched leaves out the watched ones`() {
        assertEquals(
            listOf("new episode", "show", "old movie"),
            all.spotlightRecordings(SpotlightType.All, unwatched = true).names(),
        )
    }
}
