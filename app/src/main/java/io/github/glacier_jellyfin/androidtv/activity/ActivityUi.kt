package io.github.glacier_jellyfin.androidtv.activity

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.data.media.ActivityTitle
import io.github.glacier_jellyfin.androidtv.core.data.media.ActivityUser
import io.github.glacier_jellyfin.androidtv.core.data.media.ItemKind
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaItem
import io.github.glacier_jellyfin.androidtv.core.designsystem.AvatarPerson
import io.github.glacier_jellyfin.androidtv.parental.showsLock
import io.github.glacier_jellyfin.androidtv.ui.ContinueCard
import io.github.glacier_jellyfin.androidtv.ui.yearText
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

fun ActivityUser.toAvatar() = AvatarPerson(name = name, initials = initials, imageUrl = imageUrl)

/** "S1 · E3 · Name" for an episode, else the year. */
@Composable
fun episodeSubtitle(item: MediaItem): String {
    val season = item.seasonNumber
    val episode = item.episodeNumber
    return if (item.kind == ItemKind.Episode && season != null && episode != null) {
        listOfNotNull(stringResource(R.string.episode_short, season, episode), item.title).joinToString(" · ")
    } else {
        yearText(item).orEmpty()
    }
}

/** "S2E5" for an episode with its numbers, else null. */
@Composable
fun episodeCode(item: MediaItem): String? {
    val season = item.seasonNumber
    val episode = item.episodeNumber
    return if (item.kind == ItemKind.Episode && season != null && episode != null) {
        stringResource(R.string.episode_short, season, episode)
    } else {
        null
    }
}

/** A moment as a day: today, yesterday, the short weekday within the last week, else the short date. */
@Composable
fun relativeDay(timestampMs: Long): String {
    val locale = LocalLocale.current.platformLocale
    val today = LocalDate.now()
    val date = Instant.ofEpochMilli(timestampMs).atZone(ZoneId.systemDefault()).toLocalDate()
    return when (dayLabel(date, today)) {
        DayLabel.Today -> stringResource(R.string.activity_day_today)
        DayLabel.Yesterday -> stringResource(R.string.activity_day_yesterday)
        DayLabel.Weekday -> date.format(dayFormatter("EEE", locale))
        DayLabel.Date -> date.format(dayFormatter(if (date.year == today.year) "dMMM" else "dMMMy", locale))
    }
}

private fun dayFormatter(skeleton: String, locale: Locale): DateTimeFormatter =
    DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale)

/**
 * The line under a card: the episode code or the episode count of a show card, then [names] when given,
 * then the day of the newest entry when [withDay] is on. "S2E5 · Alex · today".
 */
@Composable
fun titleSubline(
    title: ActivityTitle,
    names: String?,
    withDay: Boolean,
): String {
    val first = if (title.seriesId != null) {
        pluralStringResource(R.plurals.count_episodes, title.episodeCount, title.episodeCount)
    } else {
        episodeCode(title.item)
    }
    val day = if (withDay) relativeDay(title.latestMs) else null
    return listOfNotNull(first, names, day).joinToString(" · ")
}

/**
 * A card of the activity page and the home row "What others are watching": the 16:9 card of "Continue watching",
 * with the people who watched it in the end corner when [avatars] is on. The home row shows no bar and the
 * count or episode line; the page passes its own [subtitle] and the [progress] of an unfinished watch.
 */
@Composable
fun ActivityCard(
    title: ActivityTitle,
    avatars: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    progress: Float? = null,
) {
    val item = title.item
    val isShow = title.seriesId != null
    ContinueCard(
        title = when {
            isShow -> title.seriesTitle ?: item.parentTitle ?: item.title
            item.kind == ItemKind.Episode -> item.parentTitle ?: item.title
            else -> item.title
        },
        subtitle = subtitle ?: if (isShow) {
            pluralStringResource(R.plurals.count_episodes, title.episodeCount, title.episodeCount)
        } else {
            episodeSubtitle(item)
        },
        imageUrl = when {
            isShow -> item.showThumbUrl ?: item.posterUrl
            item.kind == ItemKind.Episode -> item.thumbUrl ?: item.showThumbUrl ?: item.posterUrl
            else -> item.thumbUrl ?: item.backdropUrl ?: item.posterUrl
        },
        progress = progress,
        onClick = onClick,
        modifier = modifier,
        locked = item.showsLock(),
        watchers = if (avatars) title.users.map { it.toAvatar() } else emptyList(),
    )
}
