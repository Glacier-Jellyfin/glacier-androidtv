package io.github.glacier_jellyfin.androidtv.livetv

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.data.media.LiveChannel
import io.github.glacier_jellyfin.androidtv.core.data.media.LiveProgram
import io.github.glacier_jellyfin.androidtv.core.data.playback.PlaybackMethod
import io.github.glacier_jellyfin.androidtv.core.data.playback.PlaybackSource
import io.github.glacier_jellyfin.androidtv.core.data.playback.TranscodeStatus
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierShapes
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.glacier_jellyfin.androidtv.core.player.GlacierPlayback
import io.github.glacier_jellyfin.androidtv.player.AudioCard
import io.github.glacier_jellyfin.androidtv.player.AudioOutput
import io.github.glacier_jellyfin.androidtv.player.BufferRow
import io.github.glacier_jellyfin.androidtv.player.DeliveryHeader
import io.github.glacier_jellyfin.androidtv.player.InfoPanelFrame
import io.github.glacier_jellyfin.androidtv.player.MiniCard
import io.github.glacier_jellyfin.androidtv.player.OsdSecondary
import io.github.glacier_jellyfin.androidtv.player.StreamStats
import io.github.glacier_jellyfin.androidtv.player.StreamTable
import io.github.glacier_jellyfin.androidtv.player.TranscodeCard
import io.github.glacier_jellyfin.androidtv.player.VideoCard
import io.github.glacier_jellyfin.androidtv.player.audioOutput
import io.github.glacier_jellyfin.androidtv.player.streamStats
import io.github.glacier_jellyfin.androidtv.ui.Artwork
import io.github.glacier_jellyfin.androidtv.ui.runtimeText
import kotlinx.coroutines.delay

/** What this device decodes, read from the stream's player once a second. */
private data class LiveStats(val stream: StreamStats?, val videoDecoder: String?, val audioDecoder: String?, val output: AudioOutput?)

/**
 * The Live TV player's info button, built like the video and music players':
 * the programme, what this device plays and what the server sends.
 * Left/Right switch tabs, OK or Back closes.
 */
@Composable
internal fun LiveTvInfoPanel(
    state: LiveTvPlayerState,
    transcodeStatus: suspend () -> TranscodeStatus?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val playback = state.playback
    val stats by produceState<LiveStats?>(null, playback) {
        while (playback != null) {
            value = playback.liveStats(context)
            delay(1_000)
        }
        value = null
    }
    InfoPanelFrame(
        tabs = listOf(
            stringResource(R.string.livetv_info_program),
            stringResource(R.string.player_info_technical),
            stringResource(R.string.player_info_server),
        ),
        onDismiss = onDismiss,
    ) { tab ->
        when (tab) {
            0 -> ProgramTab(state)
            1 -> TechnicalTab(state.source, stats)
            else -> ServerTab(state.source, stats?.stream, transcodeStatus)
        }
    }
}

private fun GlacierPlayback.liveStats(context: android.content.Context) = LiveStats(
    stream = player.streamStats(context),
    videoDecoder = videoDecoder,
    audioDecoder = audioDecoder,
    output = audioEncoding?.let { audioOutput(it, audioChannelMask) },
)

@Composable
private fun ProgramTab(state: LiveTvPlayerState) {
    val channel = state.channel ?: return
    val now = state.now?.takeIf { it.airsAt(state.nowMs) }
    val next = state.next ?: state.programs.firstOrNull { now != null && it.startMs >= now.endMs }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(28.dp)) {
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(52.dp)) {
            ProgramPicture(channel, now)
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    listOfNotNull(channel.number, channel.name).joinToString(" · "),
                    style = GlacierText.body(18),
                    color = OsdSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    now?.title ?: stringResource(R.string.livetv_no_guide),
                    style = GlacierText.display(44),
                    color = GlacierColors.Ice,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (now != null) {
                    now.episodeTitle?.let {
                        Text(it, style = GlacierText.body(22), color = GlacierColors.Ice, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(
                        listOf(timeRange(now), runtimeText(((now.endMs - now.startMs) / 60_000).toInt())).joinToString(" · "),
                        style = GlacierText.body(18),
                        color = GlacierColors.Mist,
                        maxLines = 1,
                    )
                    now.overview?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            it,
                            style = GlacierText.body(20),
                            color = GlacierColors.Ice,
                            maxLines = 6,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp).weight(1f, fill = false),
                        )
                    }
                }
            }
        }
        if (now != null || next != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                if (now != null) {
                    MiniCard(
                        imageUrl = now.imageUrl,
                        label = stringResource(R.string.home_live_now),
                        title = now.title,
                        sub = timeRange(now) + " · " + minutesLeft(now, state.nowMs),
                        fraction = now.progressAt(state.nowMs),
                        modifier = Modifier.weight(1f),
                        fallback = { ChannelLogo(channel, it) },
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                if (next != null) {
                    MiniCard(
                        imageUrl = next.imageUrl,
                        label = stringResource(R.string.livetv_next),
                        title = listOfNotNull(next.title, next.episodeTitle).joinToString(" · "),
                        sub = timeRange(next),
                        fraction = null,
                        modifier = Modifier.weight(1f),
                        fallback = { ChannelLogo(channel, it) },
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/** The programme's picture, 16:9 like an episode's still; the channel's logo when it has none. */
@Composable
private fun ProgramPicture(channel: LiveChannel, program: LiveProgram?) {
    val shape = RoundedCornerShape(GlacierShapes.RadiusMd)
    val size = Modifier.width(320.dp).height(180.dp)
    val url = program?.imageUrl
    if (url != null) {
        Artwork(url, size.clip(shape).border(1.dp, GlacierColors.GlassBorder, shape))
    } else {
        ChannelLogo(channel, size, textSize = 48)
    }
}

@Composable
private fun TechnicalTab(source: PlaybackSource?, stats: LiveStats?) {
    val language = source?.audioTracks?.firstOrNull { it.index == source.audioIndex }?.language
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            VideoCard(stats?.videoDecoder, stats?.stream, Modifier.weight(1f).fillMaxHeight())
            AudioCard(
                language = language,
                decoder = stats?.audioDecoder,
                output = stats?.output,
                stats = stats?.stream,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
        BufferRow(stats?.stream)
    }
}

/** Like the video player's: the channel as the tuner gives it against what reaches this device. */
@Composable
private fun ServerTab(source: PlaybackSource?, stats: StreamStats?, transcodeStatus: suspend () -> TranscodeStatus?) {
    val method = source?.method ?: return
    val file = source.file
    val direct = method == PlaybackMethod.DirectPlay
    val status by produceState<TranscodeStatus?>(null, source) {
        while (!direct) {
            value = transcodeStatus()
            delay(2_000)
        }
    }
    val reasons = file?.transcodeReasons?.ifEmpty { null } ?: status?.reasons.orEmpty()
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        DeliveryHeader(method, reasons)
        if (file != null) {
            StreamTable(
                file = file,
                // A channel has one sound track the server picks; without its index, the first one stands for it.
                audioIndex = source.audioIndex ?: file.audio.firstOrNull()?.index,
                audioTracks = source.audioTracks,
                subtitle = null,
                stats = stats,
                status = status,
                direct = direct,
            )
        }
        status?.let { TranscodeCard(it, file?.video?.frameRate) }
    }
}
