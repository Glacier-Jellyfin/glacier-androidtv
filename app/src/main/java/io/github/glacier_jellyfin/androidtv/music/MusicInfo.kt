package io.github.glacier_jellyfin.androidtv.music

import androidx.annotation.OptIn
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.tv.material3.Text
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.data.media.AudioFormat
import io.github.glacier_jellyfin.androidtv.core.data.media.MusicTrack
import io.github.glacier_jellyfin.androidtv.core.data.playback.PlaybackMethod
import io.github.glacier_jellyfin.androidtv.core.data.playback.TranscodeStatus
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierShapes
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.glacier_jellyfin.androidtv.core.designsystem.LocalAccent
import io.github.glacier_jellyfin.androidtv.player.DeliveryHeader
import io.github.glacier_jellyfin.androidtv.player.FactRow
import io.github.glacier_jellyfin.androidtv.player.InfoBar
import io.github.glacier_jellyfin.androidtv.player.InfoCard
import io.github.glacier_jellyfin.androidtv.player.InfoPanelFrame
import io.github.glacier_jellyfin.androidtv.player.InfoSurface
import io.github.glacier_jellyfin.androidtv.player.MAX_BUFFER_SECONDS
import io.github.glacier_jellyfin.androidtv.player.OsdSecondary
import io.github.glacier_jellyfin.androidtv.player.SectionLabel
import io.github.glacier_jellyfin.androidtv.player.StreamStats
import io.github.glacier_jellyfin.androidtv.player.StreamRow
import io.github.glacier_jellyfin.androidtv.player.StreamRowsTable
import io.github.glacier_jellyfin.androidtv.player.Tone
import io.github.glacier_jellyfin.androidtv.player.TranscodeCard
import io.github.glacier_jellyfin.androidtv.player.audioDetails
import io.github.glacier_jellyfin.androidtv.player.audioHeadline
import io.github.glacier_jellyfin.androidtv.player.bitrateText
import io.github.glacier_jellyfin.androidtv.player.formatTime
import io.github.glacier_jellyfin.androidtv.player.streamStats
import io.github.glacier_jellyfin.androidtv.ui.Artwork
import io.github.glacier_jellyfin.androidtv.ui.audioFormatText
import io.github.glacier_jellyfin.androidtv.ui.runtimeText
import kotlinx.coroutines.delay

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
    progress: MusicProgress,
    player: ExoPlayer?,
    transcodeStatus: suspend () -> TranscodeStatus?,
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
            0 -> SongTab(state, track, progress)
            1 -> TechnicalTab(state, track, stats)
            else -> ServerTab(state, track, stats, transcodeStatus)
        }
    }
}

@Composable
private fun SongTab(state: MusicUiState, track: MusicTrack, progress: MusicProgress) {
    val next = state.queue.getOrNull(state.index + 1)?.track
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(28.dp)) {
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(52.dp)) {
            val shape = RoundedCornerShape(GlacierShapes.RadiusMd)
            Artwork(track.largeCoverUrl, Modifier.size(340.dp).clip(shape).border(1.dp, GlacierColors.GlassBorder, shape))
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                val crumb = listOfNotNull(track.album, track.year?.toString()).joinToString(" · ")
                if (crumb.isNotEmpty()) Text(crumb, style = GlacierText.body(18), color = OsdSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(track.title, style = GlacierText.display(44), color = GlacierColors.Ice, maxLines = 2, overflow = TextOverflow.Ellipsis)
                track.artist?.let { Text(it, style = GlacierText.body(24), color = GlacierColors.Ice, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                if (state.playsLossless(track)) LosslessBadge(Modifier.padding(top = 2.dp))
                val number = track.number?.let { n -> track.disc?.takeIf { it > 0 }?.let { stringResource(R.string.music_info_track_disc, n, it) } ?: n.toString() }
                val length = (progress.durationMs.takeIf { it > 0 } ?: track.durationMs).takeIf { it > 0 }
                val facts = listOfNotNull(
                    track.artist?.let { stringResource(R.string.music_artist) to it },
                    track.album?.let { stringResource(R.string.music_album) to it },
                    number?.let { stringResource(R.string.music_info_track) to it },
                    length?.let { stringResource(R.string.sort_runtime) to formatTime(it) },
                )
                Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    facts.forEach { (label, value) -> FactRow(label, value, labelWidth = 190) }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            if (next != null) SongCard(stringResource(R.string.player_info_up_next), next, Modifier.weight(1f)) else Spacer(Modifier.weight(1f))
            QueueCard(state, progress, Modifier.weight(1f))
        }
    }
}

@Composable
private fun SongCard(label: String, track: MusicTrack, modifier: Modifier) {
    InfoSurface(modifier.height(122.dp), padding = 16) {
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(track.coverUrl, Modifier.size(90.dp).clip(RoundedCornerShape(GlacierShapes.RadiusSm)))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionLabel(label)
                Text(track.title, style = GlacierText.body(20, FontWeight.SemiBold), color = GlacierColors.Ice, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(track.artist, formatTime(track.durationMs)).joinToString(" · "),
                    style = GlacierText.body(15),
                    color = GlacierColors.Mist,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun QueueCard(state: MusicUiState, progress: MusicProgress, modifier: Modifier) {
    val total = state.queue.sumOf { it.track.durationMs }
    val before = state.queue.take(state.index).sumOf { it.track.durationMs }
    val left = total - before - progress.positionMs
    InfoSurface(modifier.height(122.dp), padding = 16) {
        Column(Modifier.fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically)) {
            SectionLabel(stringResource(R.string.music_queue))
            Text(
                stringResource(R.string.music_track_of, state.index + 1, state.queue.size),
                style = GlacierText.body(20, FontWeight.SemiBold),
                color = GlacierColors.Ice,
                maxLines = 1,
            )
            InfoBar(if (total > 0) (before + progress.positionMs).toFloat() / total else 0f, LocalAccent.current.main)
            Text(
                stringResource(R.string.music_queue_left, runtimeText((left / 60_000).toInt().coerceAtLeast(1))),
                style = GlacierText.body(15),
                color = GlacierColors.Mist,
                maxLines = 1,
            )
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
        InfoSurface(Modifier.fillMaxWidth(), padding = 20) {
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
                SectionLabel(stringResource(R.string.player_info_buffer))
                val seconds = stats?.bufferSeconds ?: 0
                InfoBar(seconds.toFloat() / MAX_BUFFER_SECONDS, LocalAccent.current.main, Modifier.weight(1f), thickness = 8)
                Text(
                    stringResource(R.string.player_info_seconds, seconds.toInt()),
                    style = GlacierText.body(18, FontWeight.SemiBold),
                    color = GlacierColors.Ice,
                    softWrap = false,
                )
                stats?.bandwidth?.let(::bitrateText)?.let {
                    Text(stringResource(R.string.player_info_bandwidth, it), style = GlacierText.body(18), color = OsdSecondary, softWrap = false)
                }
            }
        }
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
