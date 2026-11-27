package io.github.glacier_jellyfin.androidtv.activity

import io.github.glacier_jellyfin.androidtv.core.data.media.ActivityEntry
import io.github.glacier_jellyfin.androidtv.core.data.media.ActivityType
import io.github.glacier_jellyfin.androidtv.core.data.media.ActivityUser
import io.github.glacier_jellyfin.androidtv.core.data.media.ItemKind
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaItem
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonActivityTest {

    private val alex = ActivityUser(UUID(0, 1), "Alex", null)
    private val sam = ActivityUser(UUID(0, 2), "Sam", null)
    private val movie = item(1)
    private val other = item(2)

    private fun item(n: Long) = MediaItem(
        id = UUID(1, n),
        kind = ItemKind.Movie,
        title = "T",
        sortName = null,
        year = null,
        communityRating = null,
        officialRating = null,
        runtimeMinutes = null,
        genres = emptyList(),
        overview = null,
        parentTitle = null,
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

    private fun entry(
        who: ActivityUser,
        what: MediaItem,
        ms: Long,
        type: ActivityType = ActivityType.Watched,
        progress: Float = 0.45f,
        completed: Boolean = false,
    ) = ActivityEntry(type, who, ms, what, completed, progress)

    @Test
    fun `people are listed newest first and only for the title`() {
        val list = personActivities(
            listOf(entry(alex, movie, 10), entry(sam, movie, 20), entry(sam, other, 30)),
            movie.id,
        )
        assertEquals(listOf(sam, alex), list.map { it.user })
    }

    @Test
    fun `watching and favoriting of one person fold into one card`() {
        val card = personActivities(
            listOf(entry(alex, movie, 10), entry(alex, movie, 30, ActivityType.Favorited, progress = 0f)),
            movie.id,
        ).single()
        assertTrue(card.favorite)
        assertEquals(30L, card.timestampMs)
        assertEquals(0.45f, card.progress)
    }

    @Test
    fun `a partly watched title shows the percent`() {
        val activity = personActivities(listOf(entry(alex, movie, 10, progress = 0.456f)), movie.id).single()
        assertEquals(PersonStatus.Percent(45), statusOf(activity))
        assertEquals(0.456f, barOf(activity))
        assertFalse(activity.completed)
    }

    @Test
    fun `a completed title reads watched with a full bar`() {
        val activity = personActivities(listOf(entry(alex, movie, 10, completed = true)), movie.id).single()
        assertEquals(PersonStatus.Watched, statusOf(activity))
        assertEquals(1f, barOf(activity))
    }

    @Test
    fun `a favorite that was never watched has no bar`() {
        val activity = personActivities(
            listOf(entry(alex, movie, 10, ActivityType.Favorited, progress = 0f)),
            movie.id,
        ).single()
        assertEquals(PersonStatus.Favorite, statusOf(activity))
        assertNull(barOf(activity))
    }
}
