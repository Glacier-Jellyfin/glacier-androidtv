package io.github.glacier_jellyfin.androidtv.core.data.media

import java.util.UUID

/** How a track is encoded, for the "FLAC · 24 Bit/96 kHz · Stereo" badges. */
data class AudioFormat(
    val codec: String?,
    val sampleRate: Int?,
    val bitDepth: Int?,
    val channels: Int?,
)

/** One song, as album pages, playlists and the music player need it. */
data class MusicTrack(
    val id: UUID,
    val title: String,
    /** The track's artists, else the album artist. */
    val artist: String?,
    val album: String?,
    val albumId: UUID?,
    val year: Int?,
    val number: Int?,
    val disc: Int?,
    val durationMs: Long,
    /** Square cover: the album's, else the track's own. */
    val coverUrl: String?,
    /** A larger cover for the player. */
    val largeCoverUrl: String?,
    val format: AudioFormat?,
    val isFavorite: Boolean,
)

/**
 * A song's lyrics; [synced] when the lines carry start times. An
 * [instrumental] song has none, as its lyrics file says ("[au: instrumental]").
 */
data class Lyrics(val lines: List<LyricLine>, val synced: Boolean, val instrumental: Boolean = false)

data class LyricLine(val text: String, val startMs: Long?)
