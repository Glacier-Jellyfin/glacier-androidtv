package io.github.glacier_jellyfin.androidtv.player

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.activity.DayLabel
import io.github.glacier_jellyfin.androidtv.activity.PersonActivity
import io.github.glacier_jellyfin.androidtv.activity.PersonStatus
import io.github.glacier_jellyfin.androidtv.activity.barOf
import io.github.glacier_jellyfin.androidtv.activity.dayLabel
import io.github.glacier_jellyfin.androidtv.activity.statusOf
import io.github.glacier_jellyfin.androidtv.activity.toAvatar
import io.github.glacier_jellyfin.androidtv.core.designsystem.Avatar
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierIcons
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.glacier_jellyfin.androidtv.core.designsystem.LocalAccent
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date

/** The most people the tab lists. */
private const val MAX_PEOPLE = 8

/**
 * The info panel's tab "Activity": one card per person who watched or favorited this title, newest first, two to a
 * row like the other tabs. Display only.
 */
@Composable
internal fun ActivityTab(people: List<PersonActivity>) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        people.take(MAX_PEOPLE).chunked(2).forEach { pair ->
            Row(
                Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                pair.forEach { PersonCard(it, Modifier.weight(1f).fillMaxHeight()) }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        Text(
            stringResource(R.string.activity_tab_hint),
            style = GlacierText.body(17),
            color = OsdSecondary,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun PersonCard(activity: PersonActivity, modifier: Modifier) {
    val bar = barOf(activity)
    val status = statusOf(activity)
    InfoSurface(modifier, padding = 20) {
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(activity.user.toAvatar(), size = 64.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        activity.user.name,
                        style = GlacierText.body(24, FontWeight.Bold),
                        color = GlacierColors.Ice,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        // Capped instead of weighted, so the time takes the rest and sits at the end.
                        modifier = Modifier.widthIn(max = NAME_MAX_WIDTH),
                    )
                    if (activity.favorite) {
                        Icon(
                            GlacierIcons.HeartFilled,
                            contentDescription = stringResource(R.string.activity_favorite),
                            tint = LocalAccent.current.main,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Text(
                        timeLabel(activity.timestampMs),
                        style = GlacierText.body(17).copy(fontFeatureSettings = "tnum"),
                        color = OsdSecondary,
                        maxLines = 1,
                        textAlign = TextAlign.End,
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    when (status) {
                        PersonStatus.Favorite -> stringResource(R.string.activity_favorite)
                        PersonStatus.Watched -> stringResource(R.string.activity_watched)
                        is PersonStatus.Percent -> stringResource(R.string.activity_percent, status.percent)
                    },
                    style = GlacierText.body(19),
                    color = GlacierColors.Mist,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // A favorite that was never watched has no bar, but keeps the card's height.
                if (bar != null) {
                    InfoBar(bar, if (activity.completed) InfoOk else LocalAccent.current.main)
                } else {
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }
}

/** "Today, 20:14", "Yesterday" or "Mon, 23 Nov", for when something happened. */
@Composable
private fun timeLabel(timestampMs: Long): String {
    val context = LocalContext.current
    val locale = LocalLocale.current.platformLocale
    val date = remember(timestampMs) { Instant.ofEpochMilli(timestampMs).atZone(ZoneId.systemDefault()).toLocalDate() }
    return when (dayLabel(date, LocalDate.now())) {
        DayLabel.Today -> stringResource(
            R.string.activity_today_at,
            remember(timestampMs, context) { DateFormat.getTimeFormat(context).format(Date(timestampMs)) },
        )

        DayLabel.Yesterday -> stringResource(R.string.activity_yesterday)

        DayLabel.Weekday, DayLabel.Date -> remember(date, locale) {
            date.format(DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "EEEdMMM"), locale))
        }
    }
}

/** Room for a long name before the time. */
private val NAME_MAX_WIDTH = 260.dp
