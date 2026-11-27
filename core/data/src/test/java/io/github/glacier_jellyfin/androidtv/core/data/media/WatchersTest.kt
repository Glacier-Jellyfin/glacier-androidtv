package io.github.glacier_jellyfin.androidtv.core.data.media

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchersTest {

    private val alex = ActivityUser(UUID(0, 1), "Alex", null)
    private val sam = ActivityUser(UUID(0, 2), "Sam", null)
    private val me = ActivityUser(UUID(0, 3), "Me", null)
    private val show = UUID(0, 900)

    private fun media(
        n: Long,
        kind: ItemKind,
        seriesId: UUID? = null,
    ) = MediaItem(
        id = UUID(0, n),
        kind = kind,
        title = "Title $n",
        sortName = null,
        year = null,
        communityRating = null,
        officialRating = null,
        runtimeMinutes = null,
        genres = emptyList(),
        overview = null,
        parentTitle = null,
        seriesId = seriesId,
        seasonNumber = null,
        episodeNumber = null,
        progress = null,
        remainingMinutes = null,
        unwatchedCount = null,
        isFavorite = false,
        played = false,
        childCount = null,
        quality = null,
        posterUrl = null,
        thumbUrl = null,
        backdropUrl = null,
    )

    private val movie = media(1, ItemKind.Movie)
    private val episode1 = media(2, ItemKind.Episode, show)
    private val episode2 = media(3, ItemKind.Episode, show)
    private val series = media(900, ItemKind.Series)

    private fun entry(
        who: ActivityUser,
        item: MediaItem,
        ms: Long,
        type: ActivityType = ActivityType.Watched,
    ) = ActivityEntry(type, who, ms, item, completed = true, progress = 1f)

    @Test
    fun `a movie lists who watched it, newest first`() {
        val watchers = buildWatchers(listOf(entry(alex, movie, 10), entry(sam, movie, 20)), self = me.id)
        assertEquals(listOf(sam, alex), watchers.of(movie))
    }

    @Test
    fun `an episode lists only those who watched that episode`() {
        val watchers = buildWatchers(listOf(entry(alex, episode1, 10), entry(sam, episode2, 20)), self = me.id)
        assertEquals(listOf(alex), watchers.of(episode1))
        assertEquals(listOf(sam), watchers.of(episode2))
    }

    @Test
    fun `a series lists everyone who watched any episode once`() {
        val watchers = buildWatchers(
            listOf(entry(alex, episode1, 10), entry(sam, episode2, 20), entry(alex, episode2, 30)),
            self = me.id,
        )
        assertEquals(listOf(alex, sam), watchers.of(series))
    }

    @Test
    fun `the profile itself is left out`() {
        val watchers = buildWatchers(listOf(entry(me, movie, 10), entry(alex, movie, 5)), self = me.id)
        assertEquals(listOf(alex), watchers.of(movie))
    }

    @Test
    fun `hidden people are left out`() {
        val watchers = buildWatchers(
            listOf(entry(alex, movie, 10), entry(sam, movie, 5)),
            self = me.id,
            hidden = setOf(alex.id.toString()),
        )
        assertEquals(listOf(sam), watchers.of(movie))
    }

    @Test
    fun `favorites do not count`() {
        val watchers = buildWatchers(
            listOf(entry(alex, movie, 10, ActivityType.Favorited), entry(alex, series, 20, ActivityType.Favorited)),
            self = me.id,
        )
        assertTrue(watchers.of(movie).isEmpty())
        assertTrue(watchers.of(series).isEmpty())
    }

    @Test
    fun `music and seasons get nothing`() {
        val watchers = buildWatchers(listOf(entry(alex, episode1, 10)), self = me.id)
        assertTrue(watchers.of(media(2, ItemKind.Album)).isEmpty())
        assertTrue(watchers.of(media(2, ItemKind.Season)).isEmpty())
    }
}
