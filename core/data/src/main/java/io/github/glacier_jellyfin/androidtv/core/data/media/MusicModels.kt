package io.github.glacier_jellyfin.androidtv.core.data.media

import java.util.UUID

/** How a track is encoded, for the "FLAC · 24 Bit/96 kHz · Stereo" badges. */
data class AudioFormat(
    val codec: String?,
    val sampleRate: Int?,
    val bitDepth: Int?,
    val channels: Int?,
    /** Bits per second of the file; null when the server does not know. */
    val bitrate: Int? = null,
    /** The file's container as the server names it: "flac", "mp3", "mov,mp4,m4a,…". */
    val container: String? = null,
) {
    /** Stored without loss: FLAC, ALAC, WAV and the like. */
    val lossless: Boolean get() = codec?.lowercase()?.let { it in LosslessCodecs || it.startsWith("pcm") || it.startsWith("dsd") } == true
}

private val LosslessCodecs = setOf("flac", "alac", "ape", "wavpack", "tta", "truehd", "mlp", "wav", "aiff")

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
    /** This song's entry in the playlist it was loaded from; removing it from the playlist takes this id. */
    val playlistItemId: String? = null,
)

/**
 * A song's lyrics; [synced] when the lines carry start times. An
 * [instrumental] song has none, as its lyrics file says ("[au: instrumental]").
 */
data class Lyrics(val lines: List<LyricLine>, val synced: Boolean, val instrumental: Boolean = false)

data class LyricLine(val text: String, val startMs: Long?)
