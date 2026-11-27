package io.github.glacier_jellyfin.androidtv.activity

import io.github.glacier_jellyfin.androidtv.core.data.media.ActivityEntry
import java.time.LocalDate
import java.util.UUID

/**
 * The entries without the users the profile excluded in Settings › Activity ([hidden], user ids).
 * The profile's own entries ([self]) always stay.
 */
fun List<ActivityEntry>.withoutHidden(hidden: Set<String>, self: UUID?): List<ActivityEntry> =
    if (hidden.isEmpty()) this else filterNot { it.user.id != self && it.user.id.toString() in hidden }

/** How a day reads next to a time: a word, the weekday within the last week, else the date. */
enum class DayLabel { Today, Yesterday, Weekday, Date }

fun dayLabel(date: LocalDate, today: LocalDate): DayLabel = when {
    date == today -> DayLabel.Today
    date == today.minusDays(1) -> DayLabel.Yesterday
    date.isBefore(today) && !date.isBefore(today.minusDays(WEEKDAY_DAYS)) -> DayLabel.Weekday
    else -> DayLabel.Date
}

/** Days back from today that still show as a weekday. */
private const val WEEKDAY_DAYS = 6L
