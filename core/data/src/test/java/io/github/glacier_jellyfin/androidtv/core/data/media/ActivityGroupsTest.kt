package io.github.glacier_jellyfin.androidtv.core.data.media

import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityGroupsTest {

    private val zone = ZoneId.of("Europe/Berlin")
    private val alex = user(1, "Alex Miller")
    private val sam = user(2, "Sam")
    private val me = user(3, "Me")
    private val show = UUID(0, 900)
    private val otherShow = UUID(0, 901)

    private fun user(n: Int, name: String) = ActivityUser(UUID(0, n.toLong()), name, null)

    private fun at(
        day: Int,
        hour: Int,
        minute: Int = 0,
    ): Long = ZonedDateTime.of(2025, 11, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    private fun media(
        n: Int,
        kind: ItemKind,
        seriesId: UUID? = null,
        parent: String? = null,
    ) = MediaItem(
        id = UUID(0, n.toLong()),
        kind = kind,
        title = "Title $n",
        sortName = null,
        year = null,
        communityRating = null,
        officialRating = null,
        runtimeMinutes = null,
        genres = emptyList(),
        overview = null,
        parentTitle = parent,
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

    private fun movie(n: Int) = media(n, ItemKind.Movie)

    private fun episode(n: Int, of: UUID = show) = media(n, ItemKind.Episode, of, "Show $of")

    private fun entry(
        who: ActivityUser,
        item: MediaItem,
        ms: Long,
        type: ActivityType = ActivityType.Watched,
        completed: Boolean = true,
        progress: Float = 1f,
    ) = ActivityEntry(type, who, ms, item, completed, progress)

    // watchedByOthers

    @Test
    fun `my own entries and favorites are left out`() {
        val entries = listOf(
            entry(me, movie(1), at(10, 10)),
            entry(alex, movie(2), at(10, 11), ActivityType.Favorited),
            entry(alex, movie(3), at(10, 12)),
        )
        assertEquals(listOf("item:${UUID(0, 3)}"), watchedByOthers(entries, me.id).map { it.key })
    }

    @Test
    fun `the same title of two users is one card`() {
        val entries = listOf(
            entry(alex, movie(1), at(10, 10)),
            entry(sam, movie(1), at(10, 12)),
        )
        val card = watchedByOthers(entries, me.id).single()
        assertEquals(listOf(sam, alex), card.users)
        assertEquals(1, card.episodeCount)
        assertNull(card.seriesId)
        assertEquals(at(10, 12), card.latestMs)
    }

    @Test
    fun `three episodes of one user make one show card`() {
        val entries = listOf(
            entry(alex, episode(11), at(10, 10)),
            entry(alex, episode(12), at(10, 11)),
            entry(alex, episode(13), at(10, 12)),
        )
        val card = watchedByOthers(entries, me.id).single()
        assertEquals("series:$show", card.key)
        assertEquals(show, card.seriesId)
        assertEquals("Show $show", card.seriesTitle)
        assertEquals(3, card.episodeCount)
        assertEquals(UUID(0, 13), card.item.id)
    }

    @Test
    fun `a single episode stays an episode card`() {
        val card = watchedByOthers(listOf(entry(alex, episode(11), at(10, 10))), me.id).single()
        assertEquals("item:${UUID(0, 11)}", card.key)
        assertNull(card.seriesId)
        assertEquals(1, card.episodeCount)
    }

    @Test
    fun `an episode card folds into the show card of another user`() {
        val entries = listOf(
            entry(alex, episode(11), at(10, 10)),
            entry(alex, episode(12), at(10, 11)),
            entry(sam, episode(13), at(10, 12)),
        )
        val card = watchedByOthers(entries, me.id).single()
        assertEquals("series:$show", card.key)
        assertEquals(3, card.episodeCount)
        assertEquals(listOf(sam, alex), card.users)
        assertEquals(at(10, 12), card.latestMs)
        assertEquals(UUID(0, 13), card.item.id)
    }

    @Test
    fun `show cards of two users merge with distinct episodes`() {
        val entries = listOf(
            entry(alex, episode(11), at(10, 10)),
            entry(alex, episode(12), at(10, 11)),
            entry(sam, episode(12), at(10, 12)),
            entry(sam, episode(14), at(10, 13)),
        )
        val card = watchedByOthers(entries, me.id).single()
        assertEquals(3, card.episodeCount)
        assertEquals(listOf(sam, alex), card.users)
    }

    @Test
    fun `cards are newest first and limited`() {
        val entries = listOf(
            entry(alex, movie(1), at(10, 10)),
            entry(alex, movie(2), at(10, 12)),
            entry(sam, episode(3, otherShow), at(10, 11)),
        )
        val all = watchedByOthers(entries, me.id)
        assertEquals(listOf(UUID(0, 2), UUID(0, 3), UUID(0, 1)), all.map { it.item.id })
        assertEquals(listOf(UUID(0, 2), UUID(0, 3)), watchedByOthers(entries, me.id, limit = 2).map { it.item.id })
    }

    @Test
    fun `the newest watch gives the progress of a card`() {
        val entries = listOf(
            entry(alex, movie(1), at(10, 10), completed = true, progress = 1f),
            entry(sam, movie(1), at(10, 12), completed = false, progress = 0.4f),
        )
        val card = watchedTitles(entries).single()
        assertFalse(card.completed)
        assertEquals(0.4f, card.progress, 0f)
        assertEquals(listOf(sam, alex), card.users)
    }

    @Test
    fun `my own watches make cards too`() {
        val entries = listOf(entry(me, movie(1), at(10, 10)), entry(alex, movie(2), at(10, 11)))
        assertEquals(listOf(UUID(0, 2), UUID(0, 1)), watchedTitles(entries).map { it.item.id })
    }

    // favorites

    @Test
    fun `a favorited title is one card with everyone who favorited it`() {
        val entries = listOf(
            entry(alex, movie(1), at(10, 10), ActivityType.Favorited),
            entry(sam, movie(1), at(10, 12), ActivityType.Favorited),
            entry(alex, movie(2), at(10, 11), ActivityType.Favorited),
            entry(alex, movie(3), at(10, 13)),
        )
        val cards = favoritedTitles(entries)
        assertEquals(listOf(UUID(0, 1), UUID(0, 2)), cards.map { it.item.id })
        assertEquals(listOf(sam, alex), cards[0].users)
    }

    @Test
    fun `favorited episodes never collapse`() {
        val entries = listOf(
            entry(alex, episode(11), at(10, 10), ActivityType.Favorited),
            entry(alex, episode(12), at(10, 11), ActivityType.Favorited),
        )
        assertEquals(2, favoritedTitles(entries).size)
        assertTrue(favoritedTitles(entries).all { it.seriesId == null })
    }

    // people

    private val mixed = listOf(
        entry(alex, movie(1), at(10, 10)),
        entry(sam, movie(2), at(10, 12), ActivityType.Favorited),
        entry(alex, movie(3), at(10, 9), ActivityType.Favorited),
    )

    @Test
    fun `people are distinct in order of their newest entry`() {
        assertEquals(listOf(sam, alex), people(mixed))
    }

    @Test
    fun `activity people carry their last activity`() {
        val list = activityPeople(mixed + entry(me, movie(4), at(11, 8)))
        assertEquals(listOf(me, sam, alex), list.map { it.user })
        assertEquals(listOf(at(11, 8), at(10, 12), at(10, 10)), list.map { it.lastActiveMs })
    }

    // profile

    @Test
    fun `a profile splits unfinished and finished titles and counts distinct titles`() {
        val entries = listOf(
            entry(alex, movie(1), at(10, 10), completed = false, progress = 0.5f),
            entry(alex, movie(2), at(10, 11)),
            entry(alex, movie(2), at(10, 12)),
            entry(alex, movie(3), at(10, 13), ActivityType.Favorited),
            entry(sam, movie(4), at(10, 14)),
        )
        val profile = personProfile(entries, alex.id)!!
        assertEquals(listOf(UUID(0, 1)), profile.watching.map { it.item.id })
        assertEquals(0.5f, profile.watching.single().progress, 0f)
        assertEquals(listOf(UUID(0, 2)), profile.watched.map { it.item.id })
        assertEquals(listOf(UUID(0, 3)), profile.favorites.map { it.item.id })
        assertEquals(2, profile.watchedCount)
        assertEquals(1, profile.favoriteCount)
        assertEquals(at(10, 13), profile.person.lastActiveMs)
    }

    @Test
    fun `the newest watch of a title decides whether it is still being watched`() {
        val finishedThenRestarted = listOf(
            entry(alex, movie(1), at(10, 10), completed = true),
            entry(alex, movie(1), at(10, 12), completed = false, progress = 0.2f),
        )
        val profile = personProfile(finishedThenRestarted, alex.id)!!
        assertEquals(1, profile.watching.size)
        assertTrue(profile.watched.isEmpty())
    }

    @Test
    fun `finished episodes of a show collapse in a profile`() {
        val entries = listOf(
            entry(alex, episode(11), at(10, 10)),
            entry(alex, episode(12), at(10, 11)),
            entry(alex, episode(13), at(10, 12), completed = false, progress = 0.3f),
            entry(alex, episode(14, otherShow), at(10, 9)),
        )
        val profile = personProfile(entries, alex.id)!!
        val showCard = profile.watched.first()
        assertEquals(show, showCard.seriesId)
        assertEquals(2, showCard.episodeCount)
        assertEquals(listOf(UUID(0, 13)), profile.watching.map { it.item.id })
        assertEquals(UUID(0, 14), profile.watched.last().item.id)
    }

    @Test
    fun `the watched count matches the titles the rows show`() {
        val entries = listOf(
            entry(alex, episode(11), at(10, 10)),
            entry(alex, episode(12), at(10, 11)),
            entry(alex, episode(13), at(10, 12), completed = false, progress = 0.3f),
            entry(alex, episode(14, otherShow), at(10, 9)),
            entry(alex, movie(1), at(10, 8)),
            entry(alex, movie(1), at(10, 13)),
        )
        val profile = personProfile(entries, alex.id)!!
        // The show (three episodes, one of them in "watching"), the other show and the movie.
        assertEquals(3, profile.watchedCount)
    }

    @Test
    fun `a person without entries has no profile`() {
        assertNull(personProfile(mixed, me.id))
    }

    @Test
    fun `initials come from the first and last word`() {
        assertEquals("AM", alex.initials)
        assertEquals("S", sam.initials)
        assertEquals("AC", user(9, "  anna  b  carl ").initials)
        assertEquals("?", user(9, "  ").initials)
    }
}
