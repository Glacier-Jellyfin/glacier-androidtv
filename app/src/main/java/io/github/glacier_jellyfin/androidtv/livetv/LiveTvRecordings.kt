package io.github.glacier_jellyfin.androidtv.livetv

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.data.media.LiveChannel
import io.github.glacier_jellyfin.androidtv.core.data.media.LiveProgram
import io.github.glacier_jellyfin.androidtv.core.data.media.LiveRecording
import io.github.glacier_jellyfin.androidtv.core.data.media.LiveRecordings
import io.github.glacier_jellyfin.androidtv.core.data.media.LiveSeriesTimer
import io.github.glacier_jellyfin.androidtv.core.data.media.LiveTimer
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierClickable
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierIcons
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.glacier_jellyfin.androidtv.core.designsystem.LocalAccent
import io.github.glacier_jellyfin.androidtv.core.designsystem.PillButton
import io.github.glacier_jellyfin.androidtv.core.designsystem.SpinningDiamond
import io.github.glacier_jellyfin.androidtv.core.designsystem.ignoreHeldOk
import io.github.glacier_jellyfin.androidtv.ui.Artwork
import io.github.glacier_jellyfin.androidtv.ui.HoldOkMark
import io.github.glacier_jellyfin.androidtv.ui.ModalSheet

private val RowShape = RoundedCornerShape(18.dp)

/** One line of the recordings list: a recording, a planned one, or a series. */
private sealed interface Entry {
    val key: String

    data class Recorded(val recording: LiveRecording) : Entry {
        override val key get() = "r" + recording.id
    }

    data class Scheduled(val timer: LiveTimer) : Entry {
        override val key get() = "t" + timer.id
    }

    data class Series(val timer: LiveSeriesTimer) : Entry {
        override val key get() = "s" + timer.id
    }
}

/**
 * What the server recorded, what it will record and the series it follows,
 * as a list with the focused entry beside it. OK plays a recording (holding
 * OK offers to delete it) and opens the options of a planned one.
 */
@Composable
fun RecordingsView(
    recordings: LiveRecordings?,
    loading: Boolean,
    failed: Boolean,
    nowMs: Long,
    sheetOpen: Boolean,
    focus: FocusRequester,
    up: FocusRequester,
    onRetry: () -> Unit,
    onPlay: (LiveRecording) -> Unit,
    onRecordingMenu: (LiveRecording) -> Unit,
    onTimer: (LiveTimer) -> Unit,
    onSeriesTimer: (LiveSeriesTimer) -> Unit,
    modifier: Modifier = Modifier,
) {
    // The list showed entries: when the last one goes, the focus would fall to the top bar.
    var hadEntries by remember { mutableStateOf(false) }
    when {
        recordings == null && loading -> {
            Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { SpinningDiamond(90) }
            return
        }
        recordings == null && failed -> {
            Column(modifier.padding(top = 40.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.livetv_recordings_error), style = GlacierText.display(30), color = GlacierColors.Ice)
                Text(stringResource(R.string.livetv_error_body), style = GlacierText.body(20), color = GlacierColors.Mist)
                PillButton(
                    stringResource(R.string.action_retry),
                    onClick = onRetry,
                    primary = true,
                    modifier = Modifier.focusRequester(focus).focusProperties { this.up = up },
                )
            }
            return
        }
        recordings == null -> return
        recordings.isEmpty -> {
            // The last entry went away: the focus goes up to the views instead.
            LaunchedEffect(Unit) {
                if (!hadEntries) return@LaunchedEffect
                hadEntries = false
                withFrameNanos { }
                runCatching { up.requestFocus() }
            }
            Column(modifier.width(LIST_WIDTH.dp).padding(top = 40.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.livetv_recordings_empty), style = GlacierText.display(30), color = GlacierColors.Ice)
                Text(stringResource(R.string.livetv_recordings_empty_body), style = GlacierText.body(20), color = GlacierColors.Mist)
            }
            return
        }
    }
    val data = recordings ?: return
    SideEffect { hadEntries = true }
    val sections = listOf(
        R.string.livetv_recorded to data.recorded.map { Entry.Recorded(it) },
        // The few series before the many episodes they plan.
        R.string.livetv_series to data.series.map { Entry.Series(it) },
        R.string.livetv_scheduled to data.scheduled.map { Entry.Scheduled(it) },
    ).filter { it.second.isNotEmpty() }
    val entries = sections.flatMap { it.second }
    val firstKey = entries.first().key
    var focusKey by rememberSaveable { mutableStateOf<String?>(null) }
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    val listState = rememberLazyListState()

    // Once the sheet closes (it took the focus along), the focus lands on the same entry. When
    // that went away with the sheet (deleted, a series no longer recorded), on the one now in its place.
    var keyAtSheet by remember { mutableStateOf<String?>(null) }
    var indexAtSheet by remember { mutableIntStateOf(0) }
    val keys = entries.map { it.key }
    LaunchedEffect(sheetOpen, keys) {
        if (sheetOpen) {
            if (keyAtSheet == null) {
                keyAtSheet = focusKey
                indexAtSheet = keys.indexOf(focusKey).coerceAtLeast(0)
            }
            return@LaunchedEffect
        }
        val wanted = keyAtSheet ?: return@LaunchedEffect
        keyAtSheet = null
        val target = wanted.takeIf { it in keys } ?: keys[indexAtSheet.coerceAtMost(keys.lastIndex)]
        withFrameNanos { }
        runCatching { requesters[target]?.requestFocus() }
    }

    // Back from playing a recording: the remembered entry takes the focus again. Opening
    // the view from its chip leaves the focus on the chip (nothing is remembered then).
    LaunchedEffect(Unit) {
        val restored = focusKey?.takeIf { it in keys } ?: return@LaunchedEffect
        // The list's rows include the section headings; the one above the entry stays in sight.
        val row = sections.flatMap { (title, list) -> listOf("h$title") + list.map { it.key } }.indexOf(restored)
        listState.scrollToItem((row - 1).coerceAtLeast(0))
        withFrameNanos { }
        runCatching { requesters[restored]?.requestFocus() }
    }

    Row(modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(top = 8.dp, bottom = 80.dp, start = 8.dp, end = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier
                .width(LIST_WIDTH.dp)
                .fillMaxHeight()
                .focusRequester(focus)
                .focusRestorer(),
        ) {
            sections.forEachIndexed { index, (title, sectionEntries) ->
                item(key = "h$title") {
                    Text(
                        stringResource(title),
                        style = GlacierText.label(16),
                        color = GlacierColors.Mist,
                        modifier = Modifier.padding(top = if (index == 0) 0.dp else 18.dp, bottom = 2.dp, start = 4.dp),
                    )
                }
                items(sectionEntries, key = { it.key }) { entry ->
                    val requester = remember(entry.key) { requesters.getOrPut(entry.key) { FocusRequester() } }
                    val rowModifier = Modifier
                        .focusRequester(requester)
                        .focusProperties {
                            right = FocusRequester.Cancel
                            if (entry.key == firstKey) this.up = up
                        }
                        .onFocusChanged { if (it.isFocused) focusKey = entry.key }
                    when (entry) {
                        is Entry.Recorded -> EntryRow(
                            title = entry.recording.title,
                            lines = listOfNotNull(
                                entry.recording.episodeTitle,
                                listOfNotNull(entry.recording.channelName, dayAndTime(entry.recording.startMs, entry.recording.endMs)).joinToString(" · "),
                            ),
                            imageUrl = entry.recording.imageUrl,
                            icon = GlacierIcons.Film,
                            rec = entry.recording.recording,
                            onClick = { onPlay(entry.recording) },
                            onLongClick = { onRecordingMenu(entry.recording) },
                            modifier = rowModifier,
                            holdMenu = true,
                        )
                        is Entry.Scheduled -> EntryRow(
                            title = entry.timer.title,
                            lines = listOfNotNull(
                                entry.timer.episodeTitle,
                                listOfNotNull(entry.timer.channelName, dayAndTime(entry.timer.startMs, entry.timer.endMs)).joinToString(" · "),
                            ),
                            imageUrl = null,
                            icon = if (entry.timer.seriesTimerId != null) GlacierIcons.RecordSeries else GlacierIcons.Record,
                            rec = entry.timer.recording,
                            onClick = { onTimer(entry.timer) },
                            onLongClick = { onTimer(entry.timer) },
                            modifier = rowModifier,
                        )
                        is Entry.Series -> EntryRow(
                            title = entry.timer.title,
                            lines = listOf(entry.timer.channelName ?: stringResource(R.string.livetv_series_any_channel)),
                            imageUrl = entry.timer.imageUrl,
                            icon = GlacierIcons.RecordSeries,
                            rec = false,
                            onClick = { onSeriesTimer(entry.timer) },
                            onLongClick = { onSeriesTimer(entry.timer) },
                            modifier = rowModifier,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.width(56.dp))
        val focused = entries.firstOrNull { it.key == focusKey } ?: entries.first()
        EntryPanel(focused, nowMs, Modifier.weight(1f).padding(top = 8.dp))
    }
}

@Composable
private fun EntryRow(
    title: String,
    lines: List<String>,
    imageUrl: String?,
    icon: ImageVector,
    rec: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** Holding OK does more than OK: the mark says so on focus. */
    holdMenu: Boolean = false,
) {
    GlacierClickable(
        onClick = onClick,
        onLongClick = onLongClick,
        shape = RowShape,
        scaleOnFocus = false,
        modifier = modifier.fillMaxWidth(),
    ) { focused ->
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RowShape)
                .background(if (focused) GlacierColors.GlassFill2 else GlacierColors.GlassFill)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            Thumb(imageUrl, icon, Modifier.width(136.dp).height(76.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        title,
                        style = GlacierText.body(22, FontWeight.SemiBold),
                        color = GlacierColors.Ice,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (rec) RecBadge()
                }
                lines.forEach {
                    Text(it, style = GlacierText.body(18), color = GlacierColors.Mist, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (holdMenu) HoldOkMark(focused, LocalAccent.current.main)
        }
    }
}

/** The entry's picture, or a glass tile with [icon] when the guide had none. */
@Composable
private fun Thumb(imageUrl: String?, icon: ImageVector, modifier: Modifier = Modifier, iconSize: Int = 30) {
    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(GlacierColors.GlassFill2),
        contentAlignment = Alignment.Center,
    ) {
        if (imageUrl != null) {
            Artwork(imageUrl, Modifier.fillMaxSize())
        } else {
            val red = icon == GlacierIcons.Record || icon == GlacierIcons.RecordSeries
            Icon(icon, contentDescription = null, tint = if (red) RecordRed else GlacierColors.Mist, modifier = Modifier.size(iconSize.dp))
        }
    }
}

/** The focused entry, large: picture, title, when and what it is about. */
@Composable
private fun EntryPanel(entry: Entry, nowMs: Long, modifier: Modifier = Modifier) {
    val (title, image, overview) = when (entry) {
        is Entry.Recorded -> Triple(entry.recording.title, entry.recording.imageUrl, entry.recording.overview)
        is Entry.Scheduled -> Triple(entry.timer.title, null, entry.timer.overview)
        is Entry.Series -> Triple(entry.timer.title, entry.timer.imageUrl, entry.timer.overview)
    }
    val icon = when (entry) {
        is Entry.Recorded -> GlacierIcons.Film
        is Entry.Scheduled -> if (entry.timer.seriesTimerId != null) GlacierIcons.RecordSeries else GlacierIcons.Record
        is Entry.Series -> GlacierIcons.RecordSeries
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Thumb(image, icon, Modifier.fillMaxWidth().aspectRatio(16f / 9f), iconSize = 64)
        Spacer(Modifier.height(8.dp))
        Text(title, style = GlacierText.display(34), color = GlacierColors.Ice, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val meta = when (entry) {
            is Entry.Recorded -> listOfNotNull(entry.recording.episodeTitle, entry.recording.channelName, dayAndTime(entry.recording.startMs, entry.recording.endMs))
            is Entry.Scheduled -> listOfNotNull(entry.timer.episodeTitle, entry.timer.channelName, dayAndTime(entry.timer.startMs, entry.timer.endMs))
            is Entry.Series -> listOf(entry.timer.channelName ?: stringResource(R.string.livetv_series_any_channel))
        }
        Text(meta.joinToString(" · "), style = GlacierText.body(19), color = GlacierColors.Mist, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val running = (entry as? Entry.Recorded)?.recording?.recording == true ||
            (entry as? Entry.Scheduled)?.timer?.let { it.recording || nowMs in it.startMs until it.endMs } == true
        if (running) RecBadge()
        overview?.let {
            Text(it, style = GlacierText.body(19), color = GlacierColors.Ice.copy(alpha = 0.78f), maxLines = 5, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** What a sheet can do about recording a programme; the Live TV page and the player both offer it. */
interface RecordActions {
    fun record(program: LiveProgram)
    fun recordSeries(program: LiveProgram)
    fun cancelTimer(timerId: String, title: String)
    fun cancelSeriesTimer(seriesTimerId: String, title: String)
}

/** The modal sheet of Live TV, holding the focus until it closes. */
@Composable
private fun SheetFrame(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalSheet(onDismiss = onDismiss, width = 680) {
        Column(
            // Focus stays in the sheet. Holding OK may have opened it: the held key must not pick the first option.
            Modifier
                .ignoreHeldOk()
                .focusProperties { onExit = { cancelFocusChange() } }
                .focusGroup(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(28.dp),
            content = content,
        )
    }
}

/** A programme's sheet on its own, for the player: record it or its series. */
@Composable
fun ProgramSheet(channel: LiveChannel, program: LiveProgram, nowMs: Long, actions: RecordActions, onDismiss: () -> Unit) {
    SheetFrame(onDismiss) {
        val first = remember(program) { FocusRequester() }
        ProgramChoices(channel, program, nowMs, actions, first, onWatch = null, favorite = null, onFavorite = {})
        FocusOnStart(first)
    }
}

/** A channel's or station's sheet on its own, for the radio player: play it, mark it, move it. */
@Composable
fun ChannelSheet(channel: LiveChannel, onPlay: () -> Unit, onFavorite: () -> Unit, onMove: () -> Unit, onDismiss: () -> Unit) {
    SheetFrame(onDismiss) {
        val first = remember(channel.id) { FocusRequester() }
        ChannelChoices(channel, first, onPlay, onFavorite, onMove)
        FocusOnStart(first)
    }
}

/** A channel's name and choices: watch or listen, the heart, and moving it between TV and radio. */
@Composable
private fun ChannelChoices(channel: LiveChannel, first: FocusRequester, onPlay: () -> Unit, onFavorite: () -> Unit, onMove: () -> Unit) {
    Header(
        title = listOfNotNull(channel.number, channel.name).joinToString("  "),
        subtitle = channel.now?.title,
        body = stringResource(if (channel.radio) R.string.livetv_channel_body_radio else R.string.livetv_channel_body_tv),
    )
    Options {
        Option(if (channel.radio) R.string.radio_listen else R.string.livetv_watch, GlacierIcons.Play, first, onPlay)
        Option(
            if (channel.favorite) R.string.livetv_favorite_remove else R.string.livetv_favorite_add,
            if (channel.favorite) GlacierIcons.HeartFilled else GlacierIcons.Heart,
            onClick = onFavorite,
        )
        Option(
            if (channel.radio) R.string.livetv_move_tv else R.string.livetv_move_radio,
            if (channel.radio) GlacierIcons.Film else GlacierIcons.Speaker,
            onClick = onMove,
        )
    }
}

/**
 * A programme's title and choices: record it or its series, or stop that. With [onWatch]
 * it can be watched while it airs, with [favorite] the channel marked.
 */
@Composable
private fun ProgramChoices(
    channel: LiveChannel,
    program: LiveProgram,
    nowMs: Long,
    actions: RecordActions,
    first: FocusRequester,
    onWatch: (() -> Unit)?,
    favorite: Boolean?,
    onFavorite: () -> Unit,
) {
    val airing = program.airsAt(nowMs)
    val watch = onWatch?.takeIf { airing }
    Header(
        title = program.title,
        subtitle = listOfNotNull(channel.name, dayAndTime(program.startMs, program.endMs)).joinToString(" · "),
        body = stringResource(if (airing) R.string.livetv_program_body_airing else R.string.livetv_program_body),
    )
    Options {
        if (watch != null) Option(R.string.livetv_watch, GlacierIcons.Play, first, watch)
        val timerId = program.timerId
        if (timerId == null) {
            Option(R.string.livetv_record, GlacierIcons.Record, first.takeIf { watch == null }) { actions.record(program) }
        } else {
            Option(
                if (airing) R.string.livetv_record_stop else R.string.livetv_record_cancel,
                GlacierIcons.Close,
                first.takeIf { watch == null },
            ) { actions.cancelTimer(timerId, program.title) }
        }
        val seriesTimerId = program.seriesTimerId
        if (seriesTimerId != null) {
            Option(R.string.livetv_series_cancel, GlacierIcons.Close) { actions.cancelSeriesTimer(seriesTimerId, program.title) }
        } else if (program.series) {
            Option(R.string.livetv_record_series, GlacierIcons.RecordSeries) { actions.recordSeries(program) }
        }
        if (favorite != null) {
            Option(
                if (favorite) R.string.livetv_favorite_remove else R.string.livetv_favorite_add,
                if (favorite) GlacierIcons.HeartFilled else GlacierIcons.Heart,
                onClick = onFavorite,
            )
        }
    }
}

/** The options sheet of the Live TV page; the view model holds what it shows. */
@Composable
fun LiveTvSheetHost(sheet: LiveTvSheet, nowMs: Long, channels: List<LiveChannel>?, viewModel: LiveTvViewModel) {
    SheetFrame(onDismiss = viewModel::closeSheet) {
        val first = remember(sheet) { FocusRequester() }
        when (sheet) {
            is LiveTvSheet.Program -> ProgramChoices(
                channel = sheet.channel,
                program = sheet.program,
                nowMs = nowMs,
                actions = viewModel,
                first = first,
                onWatch = if (sheet.more) ({ viewModel.play(sheet.channel) }) else null,
                // The sheet holds the channel as it was when it opened.
                favorite = if (sheet.more) (channels?.firstOrNull { it.id == sheet.channel.id } ?: sheet.channel).favorite else null,
                onFavorite = { viewModel.toggleFavorite(sheet.channel) },
            )
            is LiveTvSheet.Channel -> {
                // The sheet holds the channel as it was when it opened.
                val channel = channels?.firstOrNull { it.id == sheet.channel.id } ?: sheet.channel
                ChannelChoices(
                    channel = channel,
                    first = first,
                    onPlay = {
                        viewModel.closeSheet()
                        viewModel.play(channel)
                    },
                    onFavorite = { viewModel.toggleFavorite(channel) },
                    onMove = { viewModel.moveChannel(channel) },
                )
            }
            is LiveTvSheet.Recording -> {
                val recording = sheet.recording
                Header(
                    title = recording.title,
                    subtitle = listOfNotNull(recording.channelName, dayAndTime(recording.startMs, recording.endMs)).joinToString(" · "),
                    body = stringResource(R.string.livetv_recording_body),
                )
                Options {
                    Option(R.string.livetv_watch, GlacierIcons.Play, first) { viewModel.playRecording(recording) }
                    val timerId = recording.timerId
                    if (recording.recording && timerId != null) {
                        Option(R.string.livetv_record_stop, GlacierIcons.Close) { viewModel.cancelTimer(timerId, recording.title) }
                    }
                    Option(R.string.livetv_delete, GlacierIcons.Trash) { viewModel.askDelete(recording) }
                }
            }
            is LiveTvSheet.ConfirmDelete -> {
                val recording = sheet.recording
                Header(
                    title = stringResource(R.string.livetv_delete_title),
                    subtitle = null,
                    body = stringResource(R.string.livetv_delete_body, recording.title),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    PillButton(stringResource(R.string.action_cancel), onClick = viewModel::closeSheet, modifier = Modifier.focusRequester(first))
                    PillButton(stringResource(R.string.livetv_delete), onClick = { viewModel.deleteRecording(recording) }, primary = true, icon = GlacierIcons.Trash)
                }
            }
            is LiveTvSheet.Timer -> {
                val timer = sheet.timer
                val airing = timer.recording || nowMs in timer.startMs until timer.endMs
                Header(
                    title = timer.title,
                    subtitle = listOfNotNull(timer.channelName, dayAndTime(timer.startMs, timer.endMs)).joinToString(" · "),
                    body = stringResource(R.string.livetv_timer_body),
                )
                Options {
                    Option(if (airing) R.string.livetv_record_stop else R.string.livetv_record_cancel, GlacierIcons.Close, first) {
                        viewModel.cancelTimer(timer.id, timer.title)
                    }
                    val seriesTimerId = timer.seriesTimerId
                    if (seriesTimerId != null) {
                        Option(R.string.livetv_series_cancel, GlacierIcons.Close) { viewModel.cancelSeriesTimer(seriesTimerId, timer.title) }
                    }
                }
            }
            is LiveTvSheet.SeriesTimer -> {
                val timer = sheet.timer
                Header(
                    title = timer.title,
                    subtitle = timer.channelName ?: stringResource(R.string.livetv_series_any_channel),
                    body = stringResource(R.string.livetv_series_timer_body),
                )
                Options {
                    Option(R.string.livetv_series_cancel, GlacierIcons.Close, first) { viewModel.cancelSeriesTimer(timer.id, timer.title) }
                }
            }
        }
        FocusOnStart(first)
    }
}

@Composable
private fun FocusOnStart(requester: FocusRequester) {
    LaunchedEffect(requester) {
        withFrameNanos { }
        runCatching { requester.requestFocus() }
    }
}

@Composable
private fun Header(title: String, subtitle: String?, body: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = GlacierText.display(30), color = GlacierColors.Ice, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        subtitle?.let { Text(it, style = GlacierText.body(19), color = GlacierColors.Mist, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        Text(body, style = GlacierText.body(17), color = GlacierColors.Mist, textAlign = TextAlign.Center)
    }
}

@Composable
private fun Options(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
}

@Composable
private fun Option(text: Int, icon: ImageVector, focus: FocusRequester? = null, onClick: () -> Unit) {
    PillButton(
        stringResource(text),
        onClick = onClick,
        icon = icon,
        fillWidth = true,
        modifier = if (focus != null) Modifier.focusRequester(focus) else Modifier,
    )
}

private const val LIST_WIDTH = 1000
