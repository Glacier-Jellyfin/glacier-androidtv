package io.github.glacier_jellyfin.androidtv.livetv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
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
import io.github.glacier_jellyfin.androidtv.core.designsystem.LeftToRight
import io.github.glacier_jellyfin.androidtv.core.designsystem.LocalAccent
import io.github.glacier_jellyfin.androidtv.core.player.GlacierPlayback
import io.github.glacier_jellyfin.androidtv.player.AudioCard
import io.github.glacier_jellyfin.androidtv.player.AudioOutput
import io.github.glacier_jellyfin.androidtv.player.BufferRow
import io.github.glacier_jellyfin.androidtv.player.DeliveryHeader
import io.github.glacier_jellyfin.androidtv.player.InfoPanelFrame
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
private data class LiveStats(
    val stream: StreamStats?,
    val videoDecoder: String?,
    val audioDecoder: String?,
    val output: AudioOutput?,
)

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
    stream = player.streamStats(context, maxBufferMs),
    videoDecoder = videoDecoder,
    audioDecoder = audioDecoder,
    output = audioEncoding?.let { audioOutput(it, audioChannelMask) },
)

@Composable
private fun ProgramTab(state: LiveTvPlayerState) {
    val channel = state.channel ?: return
    val now = state.now?.takeIf { it.airsAt(state.nowMs) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val total = maxWidth.value
        val plan = timelinePlan(state.programs, now, state.nowMs, total)
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
                            Text(
                                it,
                                style = GlacierText.body(22),
                                color = GlacierColors.Ice,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            listOf(
                                timeRange(now),
                                runtimeText(((now.endMs - now.startMs) / 60_000).toInt()),
                            ).joinToString(" · "),
                            style = GlacierText.body(18),
                            color = GlacierColors.Mist,
                            maxLines = 1,
                        )
                        now.overview?.takeIf { it.isNotBlank() }?.let {
                            Text(
                                it,
                                style = GlacierText.body(20),
                                color = GlacierColors.Ice,
                                maxLines = if (plan?.withPictures == true) 3 else 6,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 4.dp).weight(1f, fill = false),
                            )
                        }
                    }
                }
            }
            if (plan != null) ProgramTimeline(plan, state.nowMs, total)
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
private fun ServerTab(
    source: PlaybackSource?,
    stats: StreamStats?,
    transcodeStatus: suspend () -> TranscodeStatus?,
) {
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

private const val MINUTE_MS = 60_000L
private const val LOOKBACK_MS = 30 * MINUTE_MS
private val LaneHeight = 88.dp
private val ThumbWidth = 112.dp
private val ThumbHeight = 63.dp
private val ThumbSpace = ThumbHeight + 8.dp
private val RailArea = 24.dp
private const val THUMB_MIN_LABEL = 150f
private const val TIME_ONLY_BELOW = 80f
private const val NO_LABEL_BELOW = 52f

/** What the timeline shows for the width it gets. */
private class TimelinePlan(
    val start: Long,
    val windowMs: Long,
    val visible: List<LiveProgram>,
    val stations: List<TimelineStation>,
    val withPictures: Boolean,
)

/** Null without programmes. */
private fun timelinePlan(
    programs: List<LiveProgram>,
    current: LiveProgram?,
    nowMs: Long,
    total: Float,
): TimelinePlan? {
    val start = (current?.startMs ?: nowMs).coerceAtLeast(nowMs - LOOKBACK_MS)
    val ahead = programs.filter { it.endMs > start }.sortedBy { it.startMs }
    if (ahead.isEmpty()) return null
    val end = timelineWindowEnd(start, ahead.map { it.startMs }, ahead.maxOf { it.endMs })
    val visible = ahead.filter { it.startMs < end }
    if (visible.isEmpty()) return null
    val windowMs = end - start
    val stations = timelineStations(visible.map { it.startMs }, start, windowMs, total)
    val withPictures = visible.indices.any {
        visible[it].imageUrl != null && stations[it].labelWidth >= THUMB_MIN_LABEL
    }
    return TimelinePlan(start, windowMs, visible, stations, withPictures)
}

/**
 * The channel's programmes as a line with stations, display only: from the current programme's start (at most
 * 30 minutes back) for [timelineWindowEnd], a station per programme start, the elapsed part of the current
 * programme drawn thicker up to a tick at the current time. Labels alternate above and below the line and are
 * bounded by [timelineStations], so they never overlap. Time runs left to right whatever the layout direction.
 */
@Composable
private fun ProgramTimeline(
    plan: TimelinePlan,
    nowMs: Long,
    total: Float,
) {
    val start = plan.start
    val windowMs = plan.windowMs
    val visible = plan.visible
    val stations = plan.stations
    val withPictures = plan.withPictures
    val accent = LocalAccent.current.main
    LeftToRight {
        Box(Modifier.fillMaxWidth()) {
            val lane = if (withPictures) LaneHeight + ThumbSpace else LaneHeight
            val centre = lane + RailArea / 2
            val nowX = (
                TIMELINE_INSET + (total - 2 * TIMELINE_INSET) * (
                    (nowMs - start).coerceIn(
                        0L,
                        windowMs,
                    ).toFloat() / windowMs
                    )
                ).dp
            Box(Modifier.fillMaxWidth().height(lane * 2 + RailArea)) {
                Box(
                    Modifier.offset(
                        y = centre - 1.dp,
                    ).fillMaxWidth().height(2.dp).background(GlacierColors.GlassBorder2),
                )
                val currentIndex = visible.indexOfFirst { it.airsAt(nowMs) }
                if (currentIndex >= 0) {
                    val from = stations[currentIndex].x.dp
                    if (nowX > from) {
                        Box(
                            Modifier.offset(x = from, y = centre - 3.dp).width(nowX - from).height(6.dp)
                                .clip(RoundedCornerShape(3.dp)).background(accent),
                        )
                    }
                }
                visible.forEachIndexed { i, program ->
                    val station = stations[i]
                    val airing = i == currentIndex
                    val past = program.endMs <= nowMs
                    val dot = if (airing) {
                        14.dp
                    } else if (past) {
                        10.dp
                    } else {
                        12.dp
                    }
                    Box(
                        Modifier.offset(
                            x = station.x.dp - dot / 2,
                            y = centre - dot / 2,
                        ).size(dot).clip(CircleShape).then(
                            when {
                                airing -> Modifier.background(accent)

                                past -> Modifier.background(GlacierColors.Mist.copy(alpha = 0.6f))

                                else -> Modifier.background(
                                    GlacierColors.Deep,
                                ).border(2.dp, GlacierColors.Mist, CircleShape)
                            },
                        ),
                    )
                    StationLabel(
                        program = program,
                        station = station,
                        airing = airing,
                        past = past,
                        nowMs = nowMs,
                        reserveThumb = withPictures,
                        modifier = Modifier.offset(
                            x = station.labelLeft.dp,
                            y = if (station.above) 0.dp else lane + RailArea,
                        ),
                        laneHeight = lane,
                    )
                }
                Box(
                    Modifier.offset(
                        x = nowX - 1.dp,
                        y = centre - 9.dp,
                    ).width(2.dp).height(18.dp).background(GlacierColors.Ice),
                )
            }
        }
    }
}

/** A station's label, time nearest the rail and the title beyond it, within the station's width bound. */
@Composable
private fun StationLabel(
    program: LiveProgram,
    station: TimelineStation,
    airing: Boolean,
    past: Boolean,
    nowMs: Long,
    reserveThumb: Boolean,
    laneHeight: Dp,
    modifier: Modifier,
) {
    val width = station.labelWidth
    if (width < NO_LABEL_BELOW) return
    val accent = LocalAccent.current.main
    val timeColor = GlacierColors.Mist
    val left = if (airing && width >= TIME_ONLY_BELOW) minutesLeft(program, nowMs) else null
    val time = buildAnnotatedString {
        append(clockText(program.startMs))
        if (left != null) {
            append(" · ")
            withStyle(SpanStyle(color = accent)) { append(left) }
        }
    }
    val showThumb = reserveThumb && program.imageUrl != null && width >= THUMB_MIN_LABEL
    val thumb: @Composable () -> Unit = {
        if (showThumb) {
            val shape = RoundedCornerShape(GlacierShapes.RadiusSm)
            Artwork(
                program.imageUrl,
                Modifier.width(
                    ThumbWidth,
                ).height(ThumbHeight).clip(shape).border(1.dp, GlacierColors.GlassBorder, shape)
                    .alpha(if (past) 0.6f else 1f),
            )
        }
    }
    val text: @Composable () -> Unit = {
        Column {
            Text(
                time,
                style = GlacierText.body(17).copy(fontFeatureSettings = "tnum"),
                color = timeColor,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
            if (width >= TIME_ONLY_BELOW) {
                Text(
                    program.title,
                    style = GlacierText.body(21, FontWeight.SemiBold),
                    color = if (past) GlacierColors.Mist else GlacierColors.Ice,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    Column(modifier.width(width.dp).height(laneHeight)) {
        if (station.above) {
            if (showThumb) {
                thumb()
                Spacer(Modifier.height(8.dp))
            }
            Spacer(Modifier.weight(1f))
            text()
        } else {
            text()
            Spacer(Modifier.weight(1f))
            if (showThumb) {
                Spacer(Modifier.height(8.dp))
                thumb()
            }
        }
    }
}
