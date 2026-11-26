package io.github.glacier_jellyfin.androidtv.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.data.media.ItemKind
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaItem
import io.github.glacier_jellyfin.androidtv.parental.showsLock

/**
 * A recording on the home and favorites pages. An episode goes by its series, with
 * season, episode and title below, as under Live TV > Recordings. The rest looks like
 * any other poster.
 */
@Composable
fun RecordingCard(
    item: MediaItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (item.kind == ItemKind.Episode) {
        val season = item.seasonNumber
        val episode = item.episodeNumber
        val numbers = if (season != null && episode != null) {
            stringResource(R.string.episode_short, season, episode)
        } else {
            null
        }
        PosterCard(
            imageUrl = item.posterUrl,
            caption = listOfNotNull(numbers, item.title).joinToString(" · "),
            title = item.parentTitle ?: item.title,
            badge = item.unwatchedCount,
            locked = item.showsLock(),
            onClick = onClick,
            modifier = modifier,
        )
    } else {
        PosterCard(
            imageUrl = item.posterUrl,
            caption = listOfNotNull(yearText(item), item.genres.firstOrNull()).joinToString(" · "),
            title = item.title,
            badge = item.unwatchedCount,
            locked = item.showsLock(),
            onClick = onClick,
            modifier = modifier,
        )
    }
}
