package io.github.glacier_jellyfin.androidtv.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.data.media.ActivityUser
import io.github.glacier_jellyfin.androidtv.core.designsystem.AvatarStack
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.glacier_jellyfin.androidtv.core.designsystem.PillShape

/** How many names a summary spells out before it counts the rest. */
private const val NAMED = 2

/** The sentence under the facts of a detail page: [Watched] for a movie or episode, [Watching] for a show. */
enum class SummaryKind { Watched, Watching }

/** How many names the sentence has: one, two, or two and a count of the others. */
enum class SummaryForm { One, Two, Many }

/** Which sentence to say and with what: the [names] it spells out and [others] it only counts. */
data class WatchersSummary(
    val kind: SummaryKind,
    val form: SummaryForm,
    val names: List<String>,
    val others: Int,
)

/**
 * The summary of [people], newest first. For a show ([series]) it reads "are watching", else "watched by".
 * Null when nobody is left.
 */
fun watchersSummary(people: List<ActivityUser>, series: Boolean): WatchersSummary? {
    if (people.isEmpty()) return null
    val kind = if (series) SummaryKind.Watching else SummaryKind.Watched
    val form = when (people.size) {
        1 -> SummaryForm.One
        NAMED -> SummaryForm.Two
        else -> SummaryForm.Many
    }
    return WatchersSummary(
        kind = kind,
        form = form,
        names = people.take(NAMED).map { it.name },
        others = (people.size - NAMED).coerceAtLeast(0),
    )
}

@Composable
private fun WatchersSummary.text(): String {
    val a = names.getOrElse(0) { "" }
    val b = names.getOrElse(1) { "" }
    return when (kind to form) {
        SummaryKind.Watched to SummaryForm.One -> stringResource(R.string.activity_watched_by_one, a)

        SummaryKind.Watched to SummaryForm.Two -> stringResource(R.string.activity_watched_by_two, a, b)

        SummaryKind.Watched to SummaryForm.Many ->
            pluralStringResource(R.plurals.activity_watched_by_many, others, a, b, others)

        SummaryKind.Watching to SummaryForm.One -> stringResource(R.string.activity_watching_one, a)

        SummaryKind.Watching to SummaryForm.Two -> stringResource(R.string.activity_watching_two, a, b)

        else -> pluralStringResource(R.plurals.activity_watching_many, others, a, b, others)
    }
}

/**
 * A quiet pill under the facts line of a detail page: the avatars of the people who watched, and who. Display only, it
 * never takes focus. Nothing is drawn without [people].
 */
@Composable
fun WatchedByLine(
    people: List<ActivityUser>,
    series: Boolean,
    modifier: Modifier = Modifier,
) {
    val summary = watchersSummary(people, series) ?: return
    Row(
        modifier
            .clip(PillShape)
            .background(GlacierColors.GlassFill)
            .padding(start = 10.dp, end = 20.dp, top = 10.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AvatarStack(people.map { it.toAvatar() }, size = 38.dp)
        Text(
            summary.text(),
            style = GlacierText.body(20),
            color = GlacierColors.Ice,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
