package io.github.glacier_jellyfin.androidtv.core.data.media

import java.time.LocalDateTime
import java.util.UUID
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.junit.Assert.assertEquals
import org.junit.Test

class SpecialsOrderTest {

    private fun episode(
        name: String,
        number: Int? = null,
        season: Int? = 0,
        premiere: LocalDateTime? = null,
    ) = BaseItemDto(
        id = UUID.randomUUID(),
        type = BaseItemKind.EPISODE,
        name = name,
        indexNumber = number,
        parentIndexNumber = season,
        premiereDate = premiere,
    )

    private fun names(items: List<BaseItemDto>) = specialsOrder(items).map { it.name }

    @Test
    fun `specials sorted by name come back in episode order`() {
        val items = listOf(10, 5, 7, 4, 9, 2, 3, 6).map { episode("S$it", number = it) }
        assertEquals(listOf("S2", "S3", "S4", "S5", "S6", "S7", "S9", "S10"), names(items))
    }

    @Test
    fun `specials without a number follow, by premiere date then name`() {
        val items = listOf(
            episode("b", premiere = LocalDateTime.of(2020, 1, 1, 0, 0)),
            episode("a", premiere = LocalDateTime.of(2021, 1, 1, 0, 0)),
            episode("z"),
            episode("c", premiere = LocalDateTime.of(2020, 1, 1, 0, 0)),
            episode("n1", number = 1),
        )
        assertEquals(listOf("n1", "b", "c", "a", "z"), names(items))
    }

    @Test
    fun `a regular season keeps the server order`() {
        val items = listOf(
            episode("two", number = 2, season = 1),
            episode("special", number = 1, season = 0),
            episode("one", number = 1, season = 1),
        )
        assertEquals(listOf("two", "special", "one"), names(items))
    }

    @Test
    fun `an empty list stays empty`() {
        assertEquals(emptyList<String?>(), names(emptyList()))
    }
}
