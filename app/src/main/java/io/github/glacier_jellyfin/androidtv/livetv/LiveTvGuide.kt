package io.github.glacier_jellyfin.androidtv.livetv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.data.media.GuideCell
import io.github.glacier_jellyfin.androidtv.core.data.media.HALF_HOUR_MS
import io.github.glacier_jellyfin.androidtv.core.data.media.LiveChannel
import io.github.glacier_jellyfin.androidtv.core.data.media.LiveProgram
import io.github.glacier_jellyfin.androidtv.core.data.media.cellIndexAt
import io.github.glacier_jellyfin.androidtv.core.data.media.guideCells
import io.github.glacier_jellyfin.androidtv.core.data.media.halfHourFloor
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierIcons
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.glacier_jellyfin.androidtv.core.designsystem.LocalAccent
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.util.UUID

/** The guide shows three hours at a time. */
private const val WINDOW_MS = 3 * 60 * 60_000L
private const val CHANNEL_COLUMN = 300
private const val ROW_HEIGHT = 84
private const val ROW_GAP = 8
private const val CELL_GAP = 4

/** Rows beyond the visible ones whose guide loads ahead. */
private const val PRELOAD_ROWS = 8

/**
 * The programme guide: a row per channel, programmes as boxes along a
 * three hour window that follows the focus. One focusable for the whole
 * grid, moved with the keys: Left and Right go from programme to
 * programme, Up and Down change channel at the same time. OK watches a
 * programme on air, holding OK (or the menu key) marks the channel.
 */
@Composable
fun GuideGrid(
    channels: List<LiveChannel>,
    guide: Map<UUID, List<LiveProgram>>,
    fromMs: Long,
    toMs: Long,
    nowMs: Long,
    focus: FocusRequester,
    up: FocusRequester,
    onLoad: (List<UUID>) -> Unit,
    onOpen: (LiveChannel, LiveProgram?) -> Unit,
    onFavorite: (LiveChannel) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (channels.isEmpty()) return
    var row by rememberSaveable { mutableIntStateOf(0) }
    row = row.coerceIn(0, channels.lastIndex)
    /** The time the focus sits at; Up and Down keep it. */
    var focusMs by rememberSaveable { mutableLongStateOf(fromMs) }
    var windowStart by rememberSaveable { mutableLongStateOf(fromMs) }
    var focused by remember { mutableStateOf(false) }
    var heldOk by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // The guide moved on to a new half hour: nothing before it is shown any more.
    if (windowStart < fromMs) windowStart = fromMs
    if (focusMs < fromMs) focusMs = fromMs

    fun cellsOf(index: Int): List<GuideCell> = guideCells(guide[channels[index].id].orEmpty(), fromMs, toMs)

    val cells = cellsOf(row)
    val cellIndex = cellIndexAt(cells, focusMs)
    val cell = cells.getOrNull(cellIndex)

    /** Keeps [target] in the window: a programme starting late moves the window along, one before it moves it back. */
    fun follow(target: GuideCell) {
        val latest = windowStart + WINDOW_MS - HALF_HOUR_MS
        windowStart = when {
            target.startMs >= latest -> halfHourFloor(target.startMs) - HALF_HOUR_MS
            target.endMs <= windowStart + HALF_HOUR_MS / 2 -> halfHourFloor(target.startMs)
            else -> windowStart
        }.coerceIn(fromMs, maxOf(fromMs, toMs - WINDOW_MS))
    }

    fun moveRow(to: Int) {
        val target = to.coerceIn(0, channels.lastIndex)
        if (target == row) return
        row = target
        val visible = listState.layoutInfo.visibleItemsInfo
        val first = visible.firstOrNull()?.index ?: 0
        val last = visible.lastOrNull()?.index ?: 0
        scope.launch {
            when {
                target < first -> listState.scrollToItem(target)
                target > last - 1 -> listState.scrollToItem((target - (last - first) + 1).coerceAtLeast(0))
            }
        }
    }

    // The rows on screen, and some below, get their guide.
    LaunchedEffect(channels, guide.isEmpty()) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.layoutInfo.visibleItemsInfo.size }
            .distinctUntilChanged()
            .collect { (first, count) ->
                val to = (first + count + PRELOAD_ROWS).coerceAtMost(channels.size)
                onLoad(channels.subList(first.coerceAtMost(to), to).map { it.id })
            }
    }

    Column(modifier) {
        GuideDetails(channels[row], cell, nowMs)
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val timeline = maxWidth - CHANNEL_COLUMN.dp
            val perMs = timeline / WINDOW_MS.toFloat()
            Column(Modifier.fillMaxSize()) {
                TimeHeader(windowStart, nowMs, perMs)
                LazyColumn(
                    state = listState,
                    userScrollEnabled = false,
                    contentPadding = PaddingValues(bottom = 60.dp),
                    verticalArrangement = Arrangement.spacedBy(ROW_GAP.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .focusRequester(focus)
                        .onFocusChanged { focused = it.isFocused }
                        .onKeyEvent { event ->
                            val native = event.nativeKeyEvent
                            if (event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter) {
                                when (event.type) {
                                    // Holding OK repeats the key: the first repeat marks the channel, the release does nothing.
                                    KeyEventType.KeyDown -> if (native.repeatCount == 1) {
                                        heldOk = true
                                        onFavorite(channels[row])
                                    }
                                    KeyEventType.KeyUp -> {
                                        if (!heldOk) onOpen(channels[row], cell?.program)
                                        heldOk = false
                                    }
                                }
                                return@onKeyEvent true
                            }
                            if (event.key == Key.Menu) {
                                if (event.type == KeyEventType.KeyUp) onFavorite(channels[row])
                                return@onKeyEvent true
                            }
                            if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                            when (event.key) {
                                Key.DirectionRight -> {
                                    cells.getOrNull(cellIndex + 1)?.let { next ->
                                        focusMs = next.startMs
                                        follow(next)
                                    }
                                    true
                                }
                                Key.DirectionLeft -> {
                                    cells.getOrNull(cellIndex - 1)?.let { previous ->
                                        focusMs = maxOf(previous.startMs, fromMs)
                                        follow(previous)
                                    }
                                    true
                                }
                                Key.DirectionUp -> {
                                    if (row == 0) {
                                        runCatching { up.requestFocus() }
                                    } else {
                                        keepColumn(cell, windowStart)?.let { focusMs = it }
                                        moveRow(row - 1)
                                    }
                                    true
                                }
                                Key.DirectionDown -> {
                                    keepColumn(cell, windowStart)?.let { focusMs = it }
                                    moveRow(row + 1)
                                    true
                                }
                                Key.ChannelUp, Key.PageUp, Key.ChannelDown, Key.PageDown -> {
                                    val page = (listState.layoutInfo.visibleItemsInfo.size - 1).coerceAtLeast(1)
                                    keepColumn(cell, windowStart)?.let { focusMs = it }
                                    moveRow(if (event.key == Key.ChannelUp || event.key == Key.PageUp) row - page else row + page)
                                    true
                                }
                                else -> false
                            }
                        }
                        .focusable(),
                ) {
                    items(channels.size, key = { channels[it].id }) { index ->
                        val channel = channels[index]
                        val rowCells = if (index == row) cells else cellsOf(index)
                        GuideRow(
                            channel = channel,
                            cells = rowCells,
                            loaded = channel.id in guide,
                            focusedCell = if (focused && index == row) cellIndex else -1,
                            selected = index == row,
                            windowStart = windowStart,
                            nowMs = nowMs,
                            perMs = perMs,
                        )
                    }
                }
            }
        }
    }
}

/** Up and Down keep the focus at the focused programme's start, or the window's left edge when it began earlier. */
private fun keepColumn(cell: GuideCell?, windowStart: Long): Long? = cell?.let { maxOf(it.startMs, windowStart) }

/** What the focus rests on, above the grid: title, channel, time and a short overview. */
@Composable
private fun GuideDetails(channel: LiveChannel, cell: GuideCell?, nowMs: Long) {
    val program = cell?.program
    Column(Modifier.fillMaxWidth().height(158.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            program?.title ?: stringResource(R.string.livetv_no_guide),
            style = GlacierText.display(32),
            color = GlacierColors.Ice,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val airing = program != null && program.airsAt(nowMs)
        Text(
            listOfNotNull(
                listOfNotNull(channel.number, channel.name).joinToString(" "),
                program?.let(::timeRange),
                program?.takeIf { airing }?.let { minutesLeft(it, nowMs) },
                program?.episodeTitle,
            ).joinToString(" · "),
            style = GlacierText.body(19),
            color = GlacierColors.Mist,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        program?.overview?.let {
            Text(it, style = GlacierText.body(18), color = GlacierColors.Ice.copy(alpha = 0.78f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The half hours of the window above the rows, and where now is. */
@Composable
private fun TimeHeader(windowStart: Long, nowMs: Long, perMs: Dp) {
    Box(Modifier.fillMaxWidth().height(40.dp).padding(start = CHANNEL_COLUMN.dp)) {
        var at = windowStart
        while (at < windowStart + WINDOW_MS) {
            Text(
                clockText(at),
                style = GlacierText.mono(18),
                color = GlacierColors.Mist,
                modifier = Modifier.offset(x = perMs * (at - windowStart).toFloat() + 8.dp),
            )
            at += HALF_HOUR_MS
        }
        if (nowMs in windowStart until windowStart + WINDOW_MS) {
            Box(
                Modifier
                    .offset(x = perMs * (nowMs - windowStart).toFloat())
                    .align(Alignment.BottomStart)
                    .width(3.dp)
                    .height(12.dp)
                    .background(LocalAccent.current.main),
            )
        }
    }
}

@Composable
private fun GuideRow(
    channel: LiveChannel,
    cells: List<GuideCell>,
    loaded: Boolean,
    focusedCell: Int,
    selected: Boolean,
    windowStart: Long,
    nowMs: Long,
    perMs: Dp,
) {
    val accent = LocalAccent.current.main
    val windowEnd = windowStart + WINDOW_MS
    Row(Modifier.fillMaxWidth().height(ROW_HEIGHT.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.width(CHANNEL_COLUMN.dp).padding(end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ChannelLogo(channel, Modifier.width(104.dp).height(58.dp), textSize = 20)
            Column(Modifier.weight(1f)) {
                channel.number?.let { Text(it, style = GlacierText.mono(16), color = if (selected) accent else GlacierColors.Mist) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        channel.name,
                        style = GlacierText.body(18, FontWeight.SemiBold),
                        color = GlacierColors.Ice,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (channel.favorite) Icon(GlacierIcons.HeartFilled, contentDescription = null, tint = accent, modifier = Modifier.width(16.dp).height(16.dp))
                }
            }
        }
        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp))) {
            cells.forEachIndexed { index, cell ->
                if (cell.endMs <= windowStart || cell.startMs >= windowEnd) return@forEachIndexed
                val start = maxOf(cell.startMs, windowStart)
                val end = minOf(cell.endMs, windowEnd)
                GuideBox(
                    cell = cell,
                    loaded = loaded,
                    focused = index == focusedCell,
                    airing = cell.covers(nowMs),
                    modifier = Modifier
                        .offset(x = perMs * (start - windowStart).toFloat())
                        .width((perMs * (end - start).toFloat() - CELL_GAP.dp).coerceAtLeast(2.dp))
                        .fillMaxHeight(),
                )
            }
            if (nowMs in windowStart until windowEnd) {
                Box(
                    Modifier
                        .offset(x = perMs * (nowMs - windowStart).toFloat())
                        .width(2.dp)
                        .fillMaxHeight()
                        .background(accent.copy(alpha = 0.8f)),
                )
            }
        }
    }
}

@Composable
private fun GuideBox(cell: GuideCell, loaded: Boolean, focused: Boolean, airing: Boolean, modifier: Modifier) {
    val accent = LocalAccent.current.main
    val shape = RoundedCornerShape(12.dp)
    val program = cell.program
    Column(
        modifier
            .clip(shape)
            .background(
                when {
                    focused -> accent
                    program == null -> Color.Transparent
                    airing -> GlacierColors.GlassFill2
                    else -> GlacierColors.GlassFill
                },
            )
            .then(if (program == null && !focused) Modifier.border(1.dp, GlacierColors.GlassBorder, shape) else Modifier)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        val ink = if (focused) GlacierColors.Void else GlacierColors.Ice
        val soft = if (focused) GlacierColors.Void.copy(alpha = 0.75f) else GlacierColors.Mist
        if (program != null) {
            Text(program.title, style = GlacierText.body(18, FontWeight.SemiBold), color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(clockText(program.startMs), style = GlacierText.body(15), color = soft, maxLines = 1, overflow = TextOverflow.Clip)
        } else if (loaded) {
            Text(stringResource(R.string.livetv_no_guide), style = GlacierText.body(16), color = soft, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
