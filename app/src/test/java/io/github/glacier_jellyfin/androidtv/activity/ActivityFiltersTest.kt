package io.github.glacier_jellyfin.androidtv.activity

import io.github.glacier_jellyfin.androidtv.core.data.media.ActivityEntry
import io.github.glacier_jellyfin.androidtv.core.data.media.ActivityType
import io.github.glacier_jellyfin.androidtv.core.data.media.ActivityUser
import io.github.glacier_jellyfin.androidtv.core.data.media.ItemKind
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaItem
import java.time.LocalDate
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test

class ActivityFiltersTest {
    private val me = ActivityUser(UUID(0, 1), "Me", null)
    private val anna = ActivityUser(UUID(0, 2), "Anna", null)
    private val ben = ActivityUser(UUID(0, 3), "Ben", null)

    private fun entry(user: ActivityUser) = ActivityEntry(
        type = ActivityType.Watched,
        user = user,
        timestampMs = 1L,
        item = MediaItem(
            id = UUID(1, 1),
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
        ),
        completed = true,
        progress = 1f,
    )

    @Test
    fun hiddenUsersAreDropped() {
        val entries = listOf(entry(anna), entry(ben))
        val shown = entries.withoutHidden(setOf(anna.id.toString()), me.id)
        assertEquals(listOf(ben.id), shown.map { it.user.id })
    }

    @Test
    fun theOwnEntriesStayEvenWhenListed() {
        val entries = listOf(entry(me), entry(anna))
        val shown = entries.withoutHidden(setOf(me.id.toString(), anna.id.toString()), me.id)
        assertEquals(listOf(me.id), shown.map { it.user.id })
    }

    @Test
    fun nothingHiddenKeepsEverything() {
        val entries = listOf(entry(anna), entry(ben))
        assertEquals(entries, entries.withoutHidden(emptySet(), me.id))
    }

    @Test
    fun daysAreLabelled() {
        val today = LocalDate.of(2026, 11, 27)
        assertEquals(DayLabel.Today, dayLabel(today, today))
        assertEquals(DayLabel.Yesterday, dayLabel(today.minusDays(1), today))
        assertEquals(DayLabel.Weekday, dayLabel(today.minusDays(2), today))
        assertEquals(DayLabel.Weekday, dayLabel(today.minusDays(6), today))
        assertEquals(DayLabel.Date, dayLabel(today.minusDays(7), today))
        assertEquals(DayLabel.Date, dayLabel(today.plusDays(1), today))
    }
}
