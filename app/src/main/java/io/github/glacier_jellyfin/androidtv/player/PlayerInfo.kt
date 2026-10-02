package io.github.glacier_jellyfin.androidtv.player

import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.tv.material3.Text
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.data.media.Chapter
import io.github.glacier_jellyfin.androidtv.core.data.media.ItemDetails
import io.github.glacier_jellyfin.androidtv.core.data.media.ItemKind
import io.github.glacier_jellyfin.androidtv.core.data.media.Languages
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaItem
import io.github.glacier_jellyfin.androidtv.core.data.media.channelLayout
import io.github.glacier_jellyfin.androidtv.core.data.media.subtitleFormat
import io.github.glacier_jellyfin.androidtv.core.data.playback.PlaybackMethod
import io.github.glacier_jellyfin.androidtv.core.data.playback.PlaybackSubtitle
import io.github.glacier_jellyfin.androidtv.core.data.playback.SourceFile
import io.github.glacier_jellyfin.androidtv.core.data.playback.SubtitleDelivery
import io.github.glacier_jellyfin.androidtv.core.data.playback.TranscodeStatus
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierShapes
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierTabs
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.glacier_jellyfin.androidtv.core.designsystem.LocalAccent
import io.github.glacier_jellyfin.androidtv.core.designsystem.PillShape
import io.github.glacier_jellyfin.androidtv.detail.trackLabel
import io.github.glacier_jellyfin.androidtv.ui.Artwork
import io.github.glacier_jellyfin.androidtv.ui.FactBadge
import io.github.glacier_jellyfin.androidtv.ui.FactsRow
import io.github.glacier_jellyfin.androidtv.ui.episodeText
import io.github.glacier_jellyfin.androidtv.ui.runtimeText
import kotlinx.coroutines.delay
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt

/** Good news on the sheet: hardware decoding, direct play, a track passed on as it is. */
private val InfoOk = Color(0xFF8FD6B0)

/** Something is converted or does not fit: a transcode, a frame rate the TV does not show evenly. */
private val InfoWarn = Color(0xFFE9BE74)

private enum class Tone { Ok, Warn, Plain }

private const val TAB_TITLE = 0
private const val TAB_TECHNICAL = 1
private const val TAB_SERVER = 2

/**
 * The OSD's info button: the title, what this device plays and what the server
 * sends. Left/Right switch tabs, OK or Back closes.
 */
@Composable
fun InfoPanel(
    state: PlayerUiState,
    progress: PlayerProgress,
    transcodeStatus: suspend () -> TranscodeStatus?,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    val details = state.details ?: return
    val focus = remember { FocusRequester() }
    var tab by remember { mutableIntStateOf(TAB_TITLE) }
    val context = LocalContext.current
    val player = state.player
    val stats by produceState<StreamStats?>(null, player) {
        while (player != null) {
            value = player.streamStats(context)
            delay(1_000)
        }
    }
    val shape = RoundedCornerShape(GlacierShapes.RadiusLg)
    // Focus stays in the panel: Up/Down must not reach the OSD behind it, and with nowhere to go the first
    // Back reaches the BackHandler instead of being spent on moving focus out.
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xA805090F))
            .focusProperties { onExit = { cancelFocusChange() } }
            .focusGroup(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .width(1440.dp)
                .height(780.dp)
                .clip(shape)
                .background(GlacierColors.Deep)
                .border(1.dp, GlacierColors.GlassBorder2, shape)
                .focusRequester(focus)
                .onKeyEvent { event ->
                    when (event.key) {
                        Key.DirectionCenter, Key.Enter -> {
                            if (event.type == KeyEventType.KeyUp) onDismiss()
                            true
                        }
                        Key.DirectionLeft, Key.DirectionRight -> {
                            if (event.type == KeyEventType.KeyDown) {
                                tab = (tab + if (event.key == Key.DirectionRight) 1 else -1).coerceIn(TAB_TITLE, TAB_SERVER)
                            }
                            true
                        }
                        else -> false
                    }
                }
                .focusable()
                .padding(horizontal = 48.dp, vertical = 40.dp),
            verticalArrangement = Arrangement.spacedBy(30.dp),
        ) {
            GlacierTabs(
                labels = listOf(
                    stringResource(if (details.item.kind == ItemKind.Episode) R.string.player_info_episode else R.string.player_info_movie),
                    stringResource(R.string.player_info_technical),
                    stringResource(R.string.player_info_server),
                ),
                selected = tab,
                modifier = Modifier.fillMaxWidth(),
            )
            Box(Modifier.fillMaxWidth().weight(1f)) {
                when (tab) {
                    TAB_TITLE -> TitleTab(state, details, progress)
                    TAB_TECHNICAL -> TechnicalTab(state, stats)
                    else -> ServerTab(state, stats, transcodeStatus)
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { focus.requestFocus() }
    }
}

// Title

@Composable
private fun TitleTab(state: PlayerUiState, details: ItemDetails, progress: PlayerProgress) {
    val item = details.item
    val episode = item.kind == ItemKind.Episode
    val locale = LocalConfiguration.current.locales[0]
    val chapter = currentChapter(state.chapters, progress.positionMs)
    val next = state.next
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(28.dp)) {
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(52.dp)) {
            // Episodes have a 16:9 still of their own; films and the rest a 2:3 poster.
            val art = if (episode) Modifier.width(320.dp).height(180.dp) else Modifier.width(230.dp).height(345.dp)
            Artwork(
                if (episode) item.thumbUrl else item.posterUrl,
                art.clip(RoundedCornerShape(GlacierShapes.RadiusMd)).border(1.dp, GlacierColors.GlassBorder, RoundedCornerShape(GlacierShapes.RadiusMd)),
            )
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                val crumb = if (episode) {
                    listOfNotNull(item.parentTitle, episodeText(item))
                } else {
                    item.genres.take(3)
                }.joinToString(" · ")
                if (crumb.isNotEmpty()) Text(crumb, style = GlacierText.body(18), color = OsdSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(item.title, style = GlacierText.display(44), color = GlacierColors.Ice, maxLines = 2, overflow = TextOverflow.Ellipsis)
                details.tagline?.let {
                    Text(it, style = GlacierText.body(20).copy(fontStyle = FontStyle.Italic), color = GlacierColors.Mist, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                val runtime = (progress.durationMs / 60_000).toInt().takeIf { it > 0 } ?: item.runtimeMinutes
                FactsRow(
                    item,
                    listOfNotNull(item.year?.toString(), runtime?.let { runtimeText(it) }),
                    size = 18,
                )
                item.overview?.takeIf { it.isNotBlank() }?.let {
                    // Gives way to the facts below when the panel runs out of room.
                    Text(
                        it,
                        style = GlacierText.body(20),
                        color = GlacierColors.Ice,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp).weight(1f, fill = false),
                    )
                }
                val facts = listOfNotNull(
                    details.directors.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.player_info_directors) to it.take(2).joinToString(", ") },
                    details.cast.takeIf { it.isNotEmpty() }?.let { cast -> stringResource(R.string.player_info_cast) to cast.take(4).joinToString(", ") { it.name } },
                    details.studios.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.player_info_studios) to it.take(3).joinToString(", ") },
                    details.premiereDate?.takeIf { episode }?.let {
                        stringResource(R.string.player_info_premiere) to it.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale))
                    },
                )
                if (facts.isNotEmpty()) {
                    Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        facts.forEach { (label, value) -> FactRow(label, value, labelWidth = 190) }
                    }
                }
            }
        }
        if (chapter != null || next != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                if (chapter != null) ChapterCard(state.chapters, chapter, progress, Modifier.weight(1f)) else Spacer(Modifier.weight(1f))
                if (next != null) NextCard(next, episode, Modifier.weight(1f)) else Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** Index of the chapter at [positionMs]; null without real chapters. */
private fun currentChapter(chapters: List<Chapter>, positionMs: Long): Int? {
    if (chapters.size < 2) return null
    return chapters.indexOfLast { it.startMs <= positionMs }.takeIf { it >= 0 }
}

@Composable
private fun ChapterCard(chapters: List<Chapter>, index: Int, progress: PlayerProgress, modifier: Modifier) {
    val chapter = chapters[index]
    val end = chapters.getOrNull(index + 1)?.startMs ?: progress.durationMs
    val length = (end - chapter.startMs).coerceAtLeast(1)
    val into = (progress.positionMs - chapter.startMs).coerceIn(0, length)
    MiniCard(
        imageUrl = chapter.imageUrl,
        label = stringResource(R.string.player_info_chapter, index + 1, chapters.size),
        title = chapter.name.ifBlank { stringResource(R.string.player_info_chapter, index + 1, chapters.size) },
        sub = stringResource(R.string.player_info_chapter_left, formatTime(progress.positionMs), formatTime(length - into)),
        fraction = into.toFloat() / length,
        modifier = modifier,
    )
}

@Composable
private fun NextCard(next: MediaItem, episode: Boolean, modifier: Modifier) {
    MiniCard(
        imageUrl = next.thumbUrl,
        label = stringResource(if (episode) R.string.player_info_next_episode else R.string.player_info_up_next),
        title = listOfNotNull(episodeText(next), next.title).joinToString(" · "),
        sub = next.runtimeMinutes?.let { runtimeText(it) },
        fraction = null,
        modifier = modifier,
    )
}

@Composable
private fun MiniCard(imageUrl: String?, label: String, title: String, sub: String?, fraction: Float?, modifier: Modifier) {
    InfoSurface(modifier.height(122.dp), padding = 16) {
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Artwork(imageUrl, Modifier.width(160.dp).height(90.dp).clip(RoundedCornerShape(GlacierShapes.RadiusSm)))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionLabel(label)
                Text(title, style = GlacierText.body(20, FontWeight.SemiBold), color = GlacierColors.Ice, maxLines = 1, overflow = TextOverflow.Ellipsis)
                fraction?.let { InfoBar(it, LocalAccent.current.main, Modifier.padding(vertical = 2.dp)) }
                sub?.let { Text(it, style = GlacierText.body(15), color = GlacierColors.Mist, maxLines = 1) }
            }
        }
    }
}

// Technical

@Composable
private fun TechnicalTab(state: PlayerUiState, stats: StreamStats?) {
    val context = LocalContext.current
    val display = remember(context) { displayInfo(context) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            VideoCard(state, stats, Modifier.weight(1f).fillMaxHeight())
            AudioCard(state, stats, Modifier.weight(1f).fillMaxHeight())
        }
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            SubtitleCard(state, Modifier.weight(1f).fillMaxHeight())
            DisplayCard(state, stats, display, Modifier.weight(1f).fillMaxHeight())
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

@Composable
private fun VideoCard(state: PlayerUiState, stats: StreamStats?, modifier: Modifier) {
    val format = stats?.video
    val decoder = state.videoDecoder
    InfoCard(
        label = stringResource(R.string.player_info_video),
        status = decoder?.let {
            if (isSoftwareDecoder(it)) stringResource(R.string.player_info_software) to Tone.Plain else stringResource(R.string.player_info_hardware) to Tone.Ok
        },
        headline = format?.let(::videoHeadline),
        details = format?.let(::videoDetails),
        modifier = modifier,
    ) {
        decoder?.let { FactRow(stringResource(R.string.player_info_decoder), it, mono = true) }
        stats?.droppedFrames?.let { FactRow(stringResource(R.string.player_info_dropped), it.toString()) }
    }
}

@Composable
private fun AudioCard(state: PlayerUiState, stats: StreamStats?, modifier: Modifier) {
    val locale = LocalConfiguration.current.locales[0]
    val track = state.audioTracks.firstOrNull { it.index == state.audioIndex }
    val format = stats?.audio
    val output = state.audioOutput
    InfoCard(
        label = stringResource(R.string.player_audio),
        status = output?.passthrough?.let { stringResource(R.string.player_info_passthrough) to Tone.Ok },
        headline = listOfNotNull(Languages.name(track?.language, locale), format?.let(::audioHeadline)).joinToString(" · ").ifEmpty { null },
        details = format?.let(::audioDetails)?.ifEmpty { null },
        modifier = modifier,
    ) {
        output?.let {
            val text = it.passthrough?.let { codec -> stringResource(R.string.player_info_passthrough_to, codec) }
                ?: stringResource(R.string.player_info_decoded_to, channelLayout(it.channels).orEmpty())
            FactRow(stringResource(R.string.player_info_output), text)
        }
        state.audioDecoder?.takeIf { output?.passthrough == null }?.let { FactRow(stringResource(R.string.player_info_decoder), it, mono = true) }
    }
}

@Composable
private fun SubtitleCard(state: PlayerUiState, modifier: Modifier) {
    val subtitle = state.subtitles.firstOrNull { it.track.index == state.subtitleIndex }
    InfoCard(
        label = stringResource(R.string.player_subtitles),
        status = subtitle?.let {
            if (it.delivery == SubtitleDelivery.BurnIn) {
                stringResource(R.string.player_info_sub_burned) to Tone.Warn
            } else {
                stringResource(R.string.player_info_sub_drawn) to Tone.Plain
            }
        },
        headline = subtitle?.let { trackLabel(it.track, subtitle = true) } ?: stringResource(R.string.track_off),
        details = subtitle?.let {
            listOfNotNull(
                subtitleFormat(it.track.codec),
                when (it.delivery) {
                    SubtitleDelivery.Embedded -> stringResource(R.string.player_info_sub_embedded)
                    SubtitleDelivery.External -> stringResource(R.string.player_info_sub_external)
                    SubtitleDelivery.BurnIn -> null
                },
            ).joinToString(" · ")
        },
        modifier = modifier,
    )
}

@Composable
private fun DisplayCard(state: PlayerUiState, stats: StreamStats?, display: DisplayInfo, modifier: Modifier) {
    val fps = stats?.video?.frameRate?.takeIf { it > 0 } ?: state.file?.video?.frameRate
    val matches = fps?.let { frameRateMatches(display.refreshRate, it) }
    val accent = LocalAccent.current
    InfoCard(
        label = stringResource(R.string.player_info_tv),
        status = matches?.let {
            if (it) stringResource(R.string.player_info_refresh_ok) to Tone.Ok else stringResource(R.string.player_info_refresh_off) to Tone.Warn
        },
        headline = "${display.width}×${display.height} @ ${refreshText(display.refreshRate)}",
        details = fps?.let {
            stringResource(
                if (matches == false) R.string.player_info_refresh_hint else R.string.player_info_refresh_fine,
                fpsText(it),
                refreshText(display.refreshRate),
            )
        },
        modifier = modifier,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.player_info_hdr), style = GlacierText.body(17), color = GlacierColors.Mist, modifier = Modifier.width(150.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HdrFormat.entries.forEach { format ->
                    val on = format in display.hdr
                    FactBadge(
                        format.label,
                        border = if (on) accent.deep else GlacierColors.GlassBorder,
                        size = 15,
                        color = if (on) GlacierColors.Ice else GlacierColors.Mist.copy(alpha = 0.6f),
                    )
                }
            }
        }
    }
}

// Server

@Composable
private fun ServerTab(state: PlayerUiState, stats: StreamStats?, transcodeStatus: suspend () -> TranscodeStatus?) {
    val method = state.method ?: return
    val file = state.file
    val direct = method == PlaybackMethod.DirectPlay
    val status by produceState<TranscodeStatus?>(null, method) {
        while (!direct) {
            value = transcodeStatus()
            delay(2_000)
        }
    }
    val reasons = file?.transcodeReasons?.ifEmpty { null } ?: status?.reasons.orEmpty()
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            StatusPill(
                stringResource(
                    when (method) {
                        PlaybackMethod.DirectPlay -> R.string.player_direct_play
                        PlaybackMethod.DirectStream -> R.string.player_direct_stream
                        PlaybackMethod.Transcode -> R.string.player_transcode
                    },
                ),
                if (direct) Tone.Ok else Tone.Warn,
                large = true,
                modifier = Modifier.padding(end = 14.dp).align(Alignment.CenterVertically),
            )
            if (!direct && reasons.isNotEmpty()) {
                SectionLabel(stringResource(R.string.player_info_why), Modifier.align(Alignment.CenterVertically).padding(end = 4.dp))
                reasons.forEach { StatusPill(reasonText(it), Tone.Plain, Modifier.align(Alignment.CenterVertically)) }
            }
        }
        if (file != null) StreamTable(state, stats, file, status, direct)
        status?.let { TranscodeCard(it, file) }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun StreamTable(state: PlayerUiState, stats: StreamStats?, file: SourceFile, status: TranscodeStatus?, direct: Boolean) {
    val locale = LocalConfiguration.current.locales[0]
    val video = file.video
    val audio = file.audio.firstOrNull { it.index == state.audioIndex }
    val subtitle = state.subtitles.firstOrNull { it.track.index == state.subtitleIndex }
    val copied = stringResource(R.string.player_info_copied)
    val converted = stringResource(R.string.player_info_converted)
    val directLabel = stringResource(R.string.player_info_direct)
    val unchanged = stringResource(R.string.player_info_unchanged)

    val rows = buildList {
        add(
            StreamRow(
                stringResource(R.string.player_info_container),
                listOfNotNull(file.container?.uppercase(), file.sizeBytes?.let(::sizeText)).joinToString(" · "),
                if (direct) directLabel else stringResource(R.string.player_info_remuxed),
                if (direct) Tone.Ok else Tone.Plain,
                if (direct) stringResource(R.string.player_info_as_is) else file.streamContainer,
            ),
        )
        if (video != null) {
            val videoCopied = status?.videoDirect
                ?: (serverCodecFamily(video.codec) == mimeCodecFamily(stats?.video?.sampleMimeType))
            add(
                StreamRow(
                    stringResource(R.string.player_info_video),
                    listOfNotNull(sourceVideoHeadline(video), rangeText(video)).joinToString(" · "),
                    if (direct) directLabel else if (videoCopied) copied else converted,
                    if (direct || videoCopied) Tone.Ok else Tone.Warn,
                    when {
                        direct -> null
                        videoCopied -> unchanged
                        else -> stats?.video?.let { listOfNotNull(videoHeadline(it), bitrateText(it.bitrate)).joinToString(" · ") }
                    },
                ),
            )
        }
        if (audio != null) {
            val audioCopied = status?.audioDirect
                ?: (serverCodecFamily(audio.codec) == mimeCodecFamily(stats?.audio?.sampleMimeType))
            val language = state.audioTracks.firstOrNull { it.index == audio.index }?.language
            add(
                StreamRow(
                    stringResource(R.string.player_audio),
                    listOfNotNull(sourceAudioText(audio), Languages.name(language, locale)).joinToString(" · "),
                    if (direct) directLabel else if (audioCopied) copied else converted,
                    if (direct || audioCopied) Tone.Ok else Tone.Warn,
                    when {
                        direct -> null
                        audioCopied -> unchanged
                        else -> stats?.audio?.let { listOfNotNull(audioHeadline(it), bitrateText(it.bitrate)).joinToString(" · ") }
                    },
                ),
            )
        }
        if (subtitle != null) add(subtitleRow(subtitle, direct))
        file.bitrate?.let(::bitrateText)?.let { source ->
            val streamBitrate = stats?.video?.bitrate?.takeIf { !direct && it > 0 }
            add(StreamRow(stringResource(R.string.player_info_bitrate), source, null, Tone.Plain, streamBitrate?.let(::bitrateText)))
        }
    }

    val shape = RoundedCornerShape(GlacierShapes.RadiusMd)
    Column(Modifier.fillMaxWidth().clip(shape).border(1.dp, GlacierColors.GlassBorder, shape)) {
        Row(Modifier.fillMaxWidth().background(GlacierColors.GlassFill2).padding(vertical = 12.dp)) {
            TableCell(Modifier.width(STREAM_LABEL_WIDTH.dp)) {}
            TableCell(Modifier.weight(1f)) { SectionLabel(stringResource(R.string.player_info_on_server)) }
            TableCell(Modifier.width(STREAM_MID_WIDTH.dp)) {}
            TableCell(Modifier.weight(1f)) { SectionLabel(stringResource(R.string.player_info_to_device)) }
        }
        rows.forEach { row ->
            Box(Modifier.fillMaxWidth().height(1.dp).background(GlacierColors.GlassBorder))
            Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                TableCell(Modifier.width(STREAM_LABEL_WIDTH.dp)) {
                    Text(row.label, style = GlacierText.body(18), color = GlacierColors.Mist, maxLines = 1)
                }
                TableCell(Modifier.weight(1f)) {
                    Text(row.source, style = GlacierText.body(18), color = GlacierColors.Ice, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                TableCell(Modifier.width(STREAM_MID_WIDTH.dp)) {
                    row.change?.let { StatusPill(it, row.tone) }
                }
                TableCell(Modifier.weight(1f)) {
                    Text(
                        row.stream ?: if (direct) "" else "–",
                        style = GlacierText.body(18),
                        color = if (row.stream != null) GlacierColors.Ice else GlacierColors.Mist,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private const val STREAM_LABEL_WIDTH = 170
private const val STREAM_MID_WIDTH = 190

private data class StreamRow(val label: String, val source: String, val change: String?, val tone: Tone, val stream: String?)

@Composable
private fun subtitleRow(subtitle: PlaybackSubtitle, direct: Boolean): StreamRow {
    val locale = LocalConfiguration.current.locales[0]
    val source = listOfNotNull(subtitleFormat(subtitle.track.codec), Languages.name(subtitle.track.language, locale)).joinToString(" · ")
    val (change, tone, stream) = when (subtitle.delivery) {
        SubtitleDelivery.BurnIn -> Triple(R.string.player_info_burned, Tone.Warn, R.string.player_info_sub_burned)
        SubtitleDelivery.External -> Triple(R.string.player_info_sent, Tone.Ok, R.string.player_info_sub_external)
        SubtitleDelivery.Embedded -> Triple(if (direct) R.string.player_info_direct else R.string.player_info_copied, Tone.Ok, R.string.player_info_sub_drawn)
    }
    return StreamRow(stringResource(R.string.player_subtitles), source, stringResource(change), tone, stringResource(stream).takeIf { !direct })
}

@Composable
private fun TranscodeCard(status: TranscodeStatus, file: SourceFile?) {
    val sourceFps = file?.video?.frameRate?.takeIf { it > 0 }
    InfoSurface(Modifier.fillMaxWidth(), padding = 22) {
        Row(horizontalArrangement = Arrangement.spacedBy(56.dp), verticalAlignment = Alignment.CenterVertically) {
            status.framerate?.let { fps ->
                val speed = sourceFps?.let { String.format(java.util.Locale.ROOT, "%.1f×", fps / it) } ?: fpsText(fps)
                Stat(speed, stringResource(R.string.player_info_speed))
            }
            Stat(status.hardware ?: stringResource(R.string.player_info_software), stringResource(R.string.player_info_acceleration))
            status.completion?.let { completion ->
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionLabel(stringResource(R.string.player_info_progress, completion.roundToInt()))
                    InfoBar((completion / 100).toFloat(), InfoWarn, thickness = 8)
                }
            }
        }
    }
}

@Composable
private fun Stat(value: String, label: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(value, style = GlacierText.body(30, FontWeight.Bold), color = GlacierColors.Ice, maxLines = 1, softWrap = false)
        Text(label, style = GlacierText.body(15), color = GlacierColors.Mist, maxLines = 1, softWrap = false)
    }
}

/** The server's transcode reasons in words; unknown ones keep the server's name. */
@Composable
private fun reasonText(reason: String): String = when (reason) {
    "ContainerNotSupported" -> R.string.reason_container
    "VideoCodecNotSupported" -> R.string.reason_video_codec
    "AudioCodecNotSupported" -> R.string.reason_audio_codec
    "SubtitleCodecNotSupported" -> R.string.reason_subtitle_codec
    "AudioIsExternal" -> R.string.reason_audio_external
    "SecondaryAudioNotSupported" -> R.string.reason_secondary_audio
    "VideoProfileNotSupported" -> R.string.reason_video_profile
    "VideoLevelNotSupported" -> R.string.reason_video_level
    "VideoResolutionNotSupported" -> R.string.reason_resolution
    "VideoBitDepthNotSupported" -> R.string.reason_bit_depth
    "VideoFramerateNotSupported" -> R.string.reason_framerate
    "RefFramesNotSupported" -> R.string.reason_ref_frames
    "AnamorphicVideoNotSupported" -> R.string.reason_anamorphic
    "InterlacedVideoNotSupported" -> R.string.reason_interlaced
    "AudioChannelsNotSupported" -> R.string.reason_audio_channels
    "AudioProfileNotSupported" -> R.string.reason_audio_profile
    "AudioSampleRateNotSupported" -> R.string.reason_sample_rate
    "AudioBitDepthNotSupported" -> R.string.reason_audio_bit_depth
    "ContainerBitrateExceedsLimit" -> R.string.reason_bitrate_limit
    "VideoBitrateNotSupported" -> R.string.reason_video_bitrate
    "AudioBitrateNotSupported" -> R.string.reason_audio_bitrate
    "UnknownVideoStreamInfo" -> R.string.reason_unknown_video
    "UnknownAudioStreamInfo" -> R.string.reason_unknown_audio
    "DirectPlayError" -> R.string.reason_direct_play_error
    "VideoRangeTypeNotSupported" -> R.string.reason_range_type
    "VideoCodecTagNotSupported" -> R.string.reason_codec_tag
    "StreamCountExceedsLimit" -> R.string.reason_stream_count
    "VideoRotationNotSupported" -> R.string.reason_rotation
    else -> null
}?.let { stringResource(it) } ?: reason

// Building blocks

@Composable
private fun InfoSurface(modifier: Modifier, padding: Int = 24, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(GlacierShapes.RadiusMd)
    Box(
        modifier
            .clip(shape)
            .background(GlacierColors.GlassFill)
            .border(1.dp, GlacierColors.GlassBorder, shape)
            .padding(horizontal = (padding + 4).dp, vertical = padding.dp),
    ) { content() }
}

/** A tile of the technical tab: label and status on top, a big headline, details, then fact rows. */
@Composable
private fun InfoCard(
    label: String,
    status: Pair<String, Tone>?,
    headline: String?,
    details: String?,
    modifier: Modifier,
    rows: @Composable ColumnScope.() -> Unit = {},
) {
    InfoSurface(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically) {
                SectionLabel(label, Modifier.weight(1f))
                status?.let { (text, tone) -> StatusPill(text, tone) }
            }
            Text(headline ?: "–", style = GlacierText.body(28, FontWeight.Bold), color = GlacierColors.Ice, maxLines = 1, overflow = TextOverflow.Ellipsis)
            details?.let { Text(it, style = GlacierText.body(18), color = OsdSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = rows)
        }
    }
}

@Composable
private fun FactRow(label: String, value: String, labelWidth: Int = 150, mono: Boolean = false) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = GlacierText.body(17), color = GlacierColors.Mist, maxLines = 1, modifier = Modifier.width(labelWidth.dp))
        Text(
            value,
            style = if (mono) GlacierText.mono(16) else GlacierText.body(17, FontWeight.Medium),
            color = if (mono) OsdSecondary else GlacierColors.Ice,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = GlacierText.label(14, 0.08), color = GlacierColors.Mist, maxLines = 1, softWrap = false, modifier = modifier)
}

@Composable
private fun StatusPill(text: String, tone: Tone, modifier: Modifier = Modifier, large: Boolean = false) {
    val color = tone.color()
    Row(
        modifier
            .clip(PillShape)
            .background(if (tone == Tone.Plain) GlacierColors.GlassFill2 else color.copy(alpha = 0.13f))
            .padding(horizontal = if (large) 18.dp else 14.dp, vertical = if (large) 8.dp else 5.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (tone != Tone.Plain) Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(text, style = GlacierText.body(if (large) 20 else 16, FontWeight.SemiBold), color = color, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun Tone.color(): Color = when (this) {
    Tone.Ok -> InfoOk
    Tone.Warn -> InfoWarn
    Tone.Plain -> OsdSecondary
}

@Composable
private fun InfoBar(fraction: Float, color: Color, modifier: Modifier = Modifier, thickness: Int = 6) {
    Box(modifier.fillMaxWidth().height(thickness.dp).clip(PillShape).background(GlacierColors.GlassFill2)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().clip(PillShape).background(color))
    }
}

@Composable
private fun RowScope.TableCell(modifier: Modifier, content: @Composable () -> Unit) {
    Box(modifier.padding(horizontal = 22.dp)) { content() }
}

