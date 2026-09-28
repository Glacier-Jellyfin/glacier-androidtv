package io.github.glacier_jellyfin.androidtv.core.data.settings

import io.github.glacier_jellyfin.androidtv.core.data.media.Languages
import io.github.glacier_jellyfin.androidtv.core.data.media.Track
import io.github.glacier_jellyfin.androidtv.core.data.media.TrackChoices

fun Track.toKey() = TrackKey(language = Languages.iso3(language) ?: language, forced = forced, hearingImpaired = hearingImpaired, title = title)

/**
 * Tracks to start with, taken over from the last title ("use the tracks of
 * the last title"). [audio] and [subtitle] are stream indices; null leaves
 * the choice to the server, [SUBTITLES_OFF] switches subtitles off.
 */
data class RememberedTracks(val audio: Int?, val subtitle: Int?) {
    companion object {
        const val SUBTITLES_OFF = -1
    }
}

fun rememberedTracks(last: LastTracks, preferences: ServerPreferences, choices: TrackChoices?): RememberedTracks {
    if (choices == null) return RememberedTracks(null, null)
    val audio = last.audio?.takeIf { preferences.rememberAudio }?.let { closestTrack(it, choices.audio) }?.index
    val subtitle = when {
        !preferences.rememberSubtitles -> null
        last.subtitlesOff -> RememberedTracks.SUBTITLES_OFF
        else -> last.subtitle?.let { closestTrack(it, choices.subtitles) }?.index
    }
    return RememberedTracks(audio, subtitle)
}

/**
 * The track closest to [key]: same language first of all (none in that
 * language, no match), then the same kind (forced, SDH), then the same name.
 * Of equal matches the first in the file wins.
 */
fun closestTrack(key: TrackKey, tracks: List<Track>): Track? {
    val language = key.language?.let { Languages.iso3(it) ?: it }
    return tracks
        .filter { (Languages.iso3(it.language) ?: it.language) == language }
        .maxByOrNull { track ->
            val t = track.toKey()
            (if (t.forced == key.forced) 4 else 0) +
                (if (t.hearingImpaired == key.hearingImpaired) 2 else 0) +
                (if (key.title != null && t.title.equals(key.title, ignoreCase = true)) 1 else 0)
        }
}
