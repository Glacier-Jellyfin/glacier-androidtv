package io.github.glacier_jellyfin.androidtv.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.data.media.HdrFormat
import io.github.glacier_jellyfin.androidtv.core.data.media.ItemKind
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaItem
import io.github.glacier_jellyfin.androidtv.core.data.media.VideoQuality
import java.util.Locale

/** "2 h 8 min" / "2 Std. 8 Min.", or minutes only below an hour. */
@Composable
fun runtimeText(minutes: Int): String =
    if (minutes >= 60) stringResource(R.string.runtime_hours, minutes / 60, minutes % 60)
    else stringResource(R.string.runtime_minutes, minutes)

/**
 * Age ratings as the design shows them ("12+"). German ratings arrive in
 * several spellings ("FSK-12", "DE-12", "12"); anything else is shown as the
 * server sends it ("PG-13", "TV-MA").
 */
fun ageRatingText(rating: String?): String? {
    val value = rating?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val german = Regex("""^(?:FSK|DE)?[\s-]*(\d{1,2})$""", RegexOption.IGNORE_CASE).matchEntire(value)
    return german?.let { "${it.groupValues[1]}+" } ?: value
}

/** "4K HDR", "4K Dolby Vision", "HDR10+" … */
fun qualityText(quality: VideoQuality?): String? {
    quality ?: return null
    val range = when (quality.hdr) {
        HdrFormat.Hdr10 -> "HDR"
        HdrFormat.Hdr10Plus -> "HDR10+"
        HdrFormat.Hlg -> "HLG"
        HdrFormat.DolbyVision -> "Dolby Vision"
        null -> null
    }
    return listOfNotNull("4K".takeIf { quality.uhd }, range).joinToString(" ").ifEmpty { null }
}

/** Community rating with one decimal in the display language ("8,1" in German). */
fun ratingText(rating: Float): String = String.format(Locale.getDefault(), "%.1f", rating)

/** The release year; for shows the years they ran ("2018 – 2024", "2000 – today"). */
@Composable
fun yearText(item: MediaItem): String? {
    val year = item.year ?: return null
    if (item.kind != ItemKind.Series) return year.toString()
    val end = item.endYear
    return when {
        item.ongoing -> stringResource(R.string.years_ongoing, year)
        end != null && end > year -> stringResource(R.string.years_range, year, end)
        else -> year.toString()
    }
}

/** "S2 · F4" for episodes, null otherwise. */
@Composable
fun episodeText(item: MediaItem): String? {
    val season = item.seasonNumber ?: return null
    val episode = item.episodeNumber ?: return null
    return stringResource(R.string.episode_short, season, episode)
}

/** "Season 2"; season 0 holds a show's specials and is named so. */
@Composable
fun seasonLabel(number: Int): String =
    if (number == 0) stringResource(R.string.season_specials) else stringResource(R.string.season_number, number)
