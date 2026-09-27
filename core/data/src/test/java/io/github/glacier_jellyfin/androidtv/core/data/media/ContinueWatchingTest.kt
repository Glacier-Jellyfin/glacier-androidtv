package io.github.glacier_jellyfin.androidtv.core.data.media

import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

class ContinueWatchingTest {

    private fun id(n: Int) = UUID(0, n.toLong())
    private fun movie(n: Int) = BaseItemDto(id = id(n), type = BaseItemKind.MOVIE, name = "movie$n")
    private fun episode(n: Int, series: Int) =
        BaseItemDto(id = id(n), type = BaseItemKind.EPISODE, name = "ep$n", seriesId = id(1000 + series))

    @Test
    fun `resume items come first, then next episodes`() {
        val merged = mergeContinueWatching(
            resume = listOf(movie(1), episode(2, series = 1)),
            nextUp = listOf(episode(3, series = 2)),
        )
        assertEquals(listOf("movie1", "ep2", "ep3"), merged.map { it.name })
    }

    @Test
    fun `a show that is being resumed does not appear twice`() {
        val merged = mergeContinueWatching(
            resume = listOf(episode(2, series = 1)),
            nextUp = listOf(episode(5, series = 1), episode(7, series = 3)),
        )
        assertEquals(listOf("ep2", "ep7"), merged.map { it.name })
    }

    @Test
    fun `the same item is never listed twice`() {
        val merged = mergeContinueWatching(resume = listOf(episode(2, series = 1)), nextUp = listOf(episode(2, series = 1)))
        assertEquals(listOf("ep2"), merged.map { it.name })
    }
}
