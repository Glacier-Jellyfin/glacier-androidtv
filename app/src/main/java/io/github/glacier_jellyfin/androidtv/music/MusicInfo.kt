package io.github.glacier_jellyfin.androidtv.music

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.data.media.AudioFormat
import io.github.glacier_jellyfin.androidtv.core.data.media.ArtistInfo
import io.github.glacier_jellyfin.androidtv.core.data.media.MusicTrack
import io.github.glacier_jellyfin.androidtv.core.data.media.SongDetails
import io.github.glacier_jellyfin.androidtv.core.data.playback.PlaybackMethod
import io.github.glacier_jellyfin.androidtv.core.data.playback.TranscodeStatus
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierIcons
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierShapes
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.glacier_jellyfin.androidtv.core.designsystem.LocalAccent
import io.github.glacier_jellyfin.androidtv.core.designsystem.PillShape
import io.github.glacier_jellyfin.androidtv.detail.CastPortrait
import io.github.glacier_jellyfin.androidtv.player.DeliveryHeader
import io.github.glacier_jellyfin.androidtv.player.InfoCard
import io.github.glacier_jellyfin.androidtv.player.InfoPanelFrame
import io.github.glacier_jellyfin.androidtv.player.FactRow
import io.github.glacier_jellyfin.androidtv.player.InfoSurface
import io.github.glacier_jellyfin.androidtv.player.BufferRow
import io.github.glacier_jellyfin.androidtv.player.OsdSecondary
import io.github.glacier_jellyfin.androidtv.player.StreamStats
import io.github.glacier_jellyfin.androidtv.player.StreamRow
import io.github.glacier_jellyfin.androidtv.player.StreamRowsTable
import io.github.glacier_jellyfin.androidtv.player.Tone
import io.github.glacier_jellyfin.androidtv.player.TranscodeCard
import io.github.glacier_jellyfin.androidtv.player.audioDetails
import io.github.glacier_jellyfin.androidtv.player.audioHeadline
import io.github.glacier_jellyfin.androidtv.player.bitrateText
import io.github.glacier_jellyfin.androidtv.player.streamStats
import io.github.glacier_jellyfin.androidtv.ui.Artwork
import io.github.glacier_jellyfin.androidtv.ui.audioFormatText
import kotlinx.coroutines.delay
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Lossless as it reaches the TV: a transcode turns it into AAC. */
internal fun MusicUiState.playsLossless(track: MusicTrack): Boolean =
    track.format?.lossless == true && method != PlaybackMethod.Transcode

/**
 * The music player's info button, built like the video player's: the song, what
 * this device plays and what the server sends. Left/Right switch tabs, OK or Back closes.
 */
@Composable
internal fun MusicInfoPanel(
    state: MusicUiState,
    player: ExoPlayer?,
    transcodeStatus: suspend () -> TranscodeStatus?,
    songDetails: suspend (MusicTrack) -> SongDetails?,
    onDismiss: () -> Unit,
) {
    val track = state.current ?: return
    val context = LocalContext.current
    val stats by produceState<StreamStats?>(null, player) {
        while (player != null) {
            value = player.streamStats(context)
            delay(1_000)
        }
    }
    InfoPanelFrame(
        tabs = listOf(
            stringResource(R.string.music_info_song),
            stringResource(R.string.player_info_technical),
            stringResource(R.string.player_info_server),
        ),
        onDismiss = onDismiss,
    ) { tab ->
        when (tab) {
            0 -> SongTab(track, state.playsLossless(track), songDetails)
            1 -> TechnicalTab(state, track, stats)
            else -> ServerTab(state, track, stats, transcodeStatus)
        }
    }
}

@Composable
private fun SongTab(track: MusicTrack, lossless: Boolean, songDetails: suspend (MusicTrack) -> SongDetails?) {
    // Fetched in the background: the panel is there at once and the extra lines follow.
    val details by produceState<SongDetails?>(null, track.id) {
        value = null
        value = songDetails(track)
    }
    val locale = LocalConfiguration.current.locales[0]
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(32.dp)) {
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(52.dp)) {
            val shape = RoundedCornerShape(GlacierShapes.RadiusMd)
            Artwork(track.largeCoverUrl, Modifier.size(COVER.dp).clip(shape).border(1.dp, GlacierColors.GlassBorder, shape))
            // At least as tall as the cover, so the pill at the bottom ends level with it.
            Column(Modifier.weight(1f).heightIn(min = COVER.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(track.title, style = GlacierText.display(44), color = GlacierColors.Ice, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    track.artist?.let { Text(it, style = GlacierText.body(24), color = GlacierColors.Ice, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    if (lossless) LosslessBadge(Modifier.padding(top = 2.dp))
                    val trackValue = track.number?.let { n ->
                        val base = details?.albumTrackCount?.takeIf { it >= n }
                            ?.let { stringResource(R.string.music_info_track_of_value, n, it) }
                            ?: n.toString()
                        val disc = track.disc?.takeIf { it > 1 }?.let { stringResource(R.string.music_info_disc, it) }
                        listOfNotNull(base, disc).joinToString(" · ")
                    }
                    val genres = details?.genres?.takeIf { it.isNotEmpty() }?.joinToString(", ")
                    val facts = listOfNotNull(
                        track.album?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.music_album) to it },
                        track.year?.let { stringResource(R.string.music_info_year) to it.toString() },
                        trackValue?.let { stringResource(R.string.music_info_track) to it },
                        genres?.let { stringResource(R.string.music_info_genre) to it },
                    )
                    if (facts.isNotEmpty()) {
                        Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            facts.forEach { (label, value) -> FactRow(label, value, labelWidth = 190) }
                        }
                    }
                }
                val plays = details?.playCount?.takeIf { it > 0 }
                if (plays != null) {
                    val last = details?.lastPlayed?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
                    val text = listOfNotNull(
                        pluralStringResource(R.plurals.music_info_played, plays, plays),
                        last?.let { stringResource(R.string.music_info_last_played, it) },
                    ).joinToString(" · ")
                    PlayCountPill(text, Modifier.padding(top = 14.dp))
                }
            }
        }
        details?.artist?.let { ArtistCard(it) }
    }
}

private const val COVER = 340

/** How often and when the song was last played: one quiet pill, level with the cover's bottom edge. */
@Composable
private fun PlayCountPill(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(PillShape)
            .background(GlacierColors.GlassFill2)
            .padding(horizontal = 18.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(GlacierIcons.Headphones, contentDescription = null, tint = GlacierColors.Mist, modifier = Modifier.size(20.dp))
        Text(text, style = GlacierText.body(18), color = GlacierColors.Mist, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The artist of the song: round photo, name and the start of the biography. */
@Composable
private fun ArtistCard(artist: ArtistInfo) {
    InfoSurface(Modifier.fillMaxWidth(), padding = 20) {
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
            CastPortrait(artist.name, artist.imageUrl, 112)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(artist.name, style = GlacierText.body(24, FontWeight.SemiBold), color = GlacierColors.Ice, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val bio = artist.overview
                if (bio != null) Text(bio, style = GlacierText.body(18), color = GlacierColors.Mist, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun TechnicalTab(state: MusicUiState, track: MusicTrack, stats: StreamStats?) {
    val format = track.format
    val lossless = state.playsLossless(track)
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            // What this device decodes: the file itself, or the server's AAC.
            InfoCard(
                label = stringResource(R.string.player_audio),
                status = if (lossless) stringResource(R.string.music_lossless) to Tone.Ok else null,
                headline = stats?.audio?.let(::audioHeadline),
                details = stats?.audio?.let(::audioDetails)?.ifEmpty { null },
                modifier = Modifier.weight(1f).fillMaxHeight(),
            ) {
                if (!lossless && format?.lossless == true && state.method == PlaybackMethod.Transcode) {
                    Text(stringResource(R.string.music_info_not_lossless), style = GlacierText.body(18), color = OsdSecondary)
                }
            }
            InfoCard(
                label = stringResource(R.string.music_info_file),
                status = format?.takeIf { it.lossless }?.let { stringResource(R.string.music_lossless) to Tone.Ok },
                headline = audioFormatText(format),
                details = format?.let { listOfNotNull(containerText(it), it.bitrate?.let(::bitrateText)).joinToString(" · ").ifEmpty { null } },
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
        BufferRow(stats)
    }
}

/** Like the video player's: the file on the server against what reaches this device. */
@OptIn(UnstableApi::class)
@Composable
private fun ServerTab(state: MusicUiState, track: MusicTrack, stats: StreamStats?, transcodeStatus: suspend () -> TranscodeStatus?) {
    val method = state.method ?: return
    val direct = method == PlaybackMethod.DirectPlay
    val status by produceState<TranscodeStatus?>(null, method, track.id) {
        while (!direct) {
            value = transcodeStatus()
            delay(2_000)
        }
    }
    // The app asks for AAC only when this device cannot play the song's codec.
    val reasons = status?.reasons?.ifEmpty { null } ?: listOf("AudioCodecNotSupported").takeIf { !direct }.orEmpty()
    val format = track.format
    val streamed = stats?.audio
    val audioCopied = status?.audioDirect == true
    val directLabel = stringResource(R.string.player_info_direct)
    val rows = buildList {
        add(
            StreamRow(
                stringResource(R.string.player_info_container),
                format?.let(::containerText) ?: "–",
                if (direct) directLabel else stringResource(R.string.player_info_remuxed),
                if (direct) Tone.Ok else Tone.Plain,
                if (direct) stringResource(R.string.player_info_as_is) else "HLS · MP4",
            ),
        )
        add(
            StreamRow(
                stringResource(R.string.player_audio),
                audioFormatText(format) ?: "–",
                if (direct) directLabel else stringResource(if (audioCopied) R.string.player_info_copied else R.string.player_info_converted),
                if (direct || audioCopied) Tone.Ok else Tone.Warn,
                when {
                    direct -> null
                    audioCopied -> stringResource(R.string.player_info_unchanged)
                    else -> streamed?.let { listOfNotNull(audioHeadline(it), bitrateText(it.bitrate)).joinToString(" · ") }
                },
            ),
        )
        format?.bitrate?.let(::bitrateText)?.let { source ->
            add(StreamRow(stringResource(R.string.player_info_bitrate), source, null, Tone.Plain, streamed?.bitrate?.takeIf { !direct }?.let(::bitrateText)))
        }
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        DeliveryHeader(method, reasons)
        StreamRowsTable(rows, direct)
        status?.let { TranscodeCard(it, sourceFrameRate = null) }
    }
}

/** The server lists every name a container goes by ("mov,mp4,m4a,…"); songs in it are M4A files. */
private fun containerText(format: AudioFormat): String? {
    val names = format.container?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
    val name = if (names.size > 1 && "m4a" in names) "m4a" else names.firstOrNull()
    return name?.uppercase()
}

/** "Lossless" next to the song in the player and on its info panel. */
@Composable
internal fun LosslessBadge(modifier: Modifier = Modifier) {
    val accent = LocalAccent.current
    val shape = RoundedCornerShape(GlacierShapes.RadiusSm)
    Text(
        stringResource(R.string.music_lossless).uppercase(),
        style = GlacierText.label(15, 0.09),
        color = accent.main,
        modifier = modifier.border(1.dp, accent.deep, shape).padding(horizontal = 10.dp, vertical = 4.dp),
    )
}
