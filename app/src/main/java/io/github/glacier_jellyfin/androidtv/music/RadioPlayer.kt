package io.github.glacier_jellyfin.androidtv.music

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.data.media.LiveChannel
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierIcons
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierShapes
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.glacier_jellyfin.androidtv.core.designsystem.SpinningDiamond
import io.github.glacier_jellyfin.androidtv.core.designsystem.LocalAccent
import io.github.glacier_jellyfin.androidtv.core.designsystem.onMenuKey
import io.github.glacier_jellyfin.androidtv.livetv.ChannelSheet
import io.github.glacier_jellyfin.androidtv.livetv.ChannelLogo
import io.github.glacier_jellyfin.androidtv.livetv.ProgramProgress
import io.github.glacier_jellyfin.androidtv.livetv.RowIconButton
import io.github.glacier_jellyfin.androidtv.livetv.clockText
import io.github.glacier_jellyfin.androidtv.livetv.minutesLeft
import io.github.glacier_jellyfin.androidtv.livetv.timeRange
import io.github.glacier_jellyfin.androidtv.ui.HoldOkMark
import java.time.LocalTime
import kotlinx.coroutines.delay

/**
 * The music player while a radio station plays: the station's logo, what
 * runs on it now and next, the buttons, and the stations to switch to.
 * There is no timeline: the skip buttons, the remote's next and previous
 * and its channel keys switch stations.
 */
@Composable
internal fun BoxScope.RadioPlayer(state: MusicUiState, radio: RadioState, playFocus: FocusRequester, viewModel: MusicPlayerViewModel) {
    val nowMs by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(CLOCK_TICK_MS)
            value = System.currentTimeMillis()
        }
    }
    val station = radio.station
    val (now, next) = radio.nowAndNextAt(nowMs)

    Row(
        Modifier.padding(start = 96.dp, end = 96.dp, top = 60.dp).fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(R.string.music_playing_from, stringResource(R.string.nav_live_tv)).uppercase(),
                style = GlacierText.body(18).copy(letterSpacing = 0.06.em),
                color = GlacierColors.Mist,
            )
            Text(stringResource(R.string.livetv_radio), style = GlacierText.display(28), color = GlacierColors.Ice)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            state.method?.let { StatusPill(stringResource(it.label())) }
            val clock by produceState(LocalTime.now().format(ClockFormat)) {
                while (true) {
                    value = LocalTime.now().format(ClockFormat)
                    delay(CLOCK_TICK_MS)
                }
            }
            Text(clock, style = GlacierText.mono(20), color = GlacierColors.Mist)
        }
    }

    Row(
        Modifier.padding(start = 96.dp, top = 210.dp).width(1064.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(56.dp),
    ) {
        val shape = RoundedCornerShape(GlacierShapes.RadiusLg)
        ChannelLogo(station, Modifier.size(420.dp).clip(shape).border(1.dp, GlacierColors.GlassBorder2, shape), textSize = 90)
        Column(Modifier.weight(1f).padding(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            val accent = LocalAccent.current.main
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Equalizer(state.playing)
                Text(
                    stringResource(
                        when {
                            !state.playWhenReady -> R.string.music_paused
                            state.loading -> R.string.radio_tuning
                            else -> R.string.livetv_live
                        },
                    ).uppercase(),
                    style = GlacierText.body(16, FontWeight.Bold).copy(letterSpacing = 0.09.em),
                    color = accent,
                )
            }
            Text(
                listOfNotNull(station.number, station.name).joinToString("  "),
                style = GlacierText.display(54).copy(lineHeight = 58.sp),
                color = GlacierColors.Ice,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (now == null && !radio.programsLoaded) {
                Box(Modifier.fillMaxWidth().height(96.dp), contentAlignment = Alignment.Center) { SpinningDiamond(48) }
            } else if (now == null) {
                Text(stringResource(R.string.livetv_no_guide), style = GlacierText.body(22), color = GlacierColors.Mist)
            } else {
                Text(now.title, style = GlacierText.body(26), color = GlacierColors.Ice, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOf(timeRange(now), minutesLeft(now, nowMs)).joinToString(" · "),
                    style = GlacierText.body(20),
                    color = GlacierColors.Mist,
                    maxLines = 1,
                )
                ProgramProgress(now.progressAt(nowMs), Modifier.width(420.dp), height = 6)
            }
            if (next != null) {
                Row(
                    Modifier.padding(top = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(stringResource(R.string.livetv_next).uppercase(), style = GlacierText.label(15), color = GlacierColors.Mist)
                    Text(clockText(next.startMs), style = GlacierText.mono(19), color = GlacierColors.Mist)
                    Text(next.title, style = GlacierText.body(19), color = GlacierColors.Ice, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }

    Row(
        Modifier.align(Alignment.BottomStart).padding(start = 96.dp, bottom = 90.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        MusicButton(GlacierIcons.SkipBack, stringResource(R.string.radio_previous), viewModel::previous)
        MusicButton(
            if (state.playWhenReady) GlacierIcons.Pause else GlacierIcons.Play,
            stringResource(if (state.playWhenReady) R.string.player_pause else R.string.player_play),
            viewModel::togglePlay,
            big = true,
            modifier = Modifier.focusRequester(playFocus),
        )
        MusicButton(GlacierIcons.SkipForward, stringResource(R.string.radio_next), viewModel::next)
        MusicButton(
            if (station.favorite) GlacierIcons.HeartFilled else GlacierIcons.Heart,
            stringResource(if (station.favorite) R.string.livetv_favorite_remove else R.string.livetv_favorite_add),
            viewModel::toggleFavorite,
            on = station.favorite,
        )
    }

    val menu by viewModel.stationMenu.collectAsStateWithLifecycle()
    StationsPanel(
        radio = radio,
        playing = state.playing,
        nowMs = nowMs,
        menuOpen = menu != null,
        onPick = viewModel::tuneTo,
        onFavorite = viewModel::toggleStationFavorite,
        onMenu = viewModel::openStationMenu,
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(end = 96.dp, top = 170.dp, bottom = 90.dp)
            .width(600.dp)
            .fillMaxHeight(),
    )

    menu?.let { opened ->
        // The sheet follows the heart while it is open.
        val station = radio.stations.firstOrNull { it.id == opened.id } ?: opened
        ChannelSheet(
            channel = station,
            onPlay = {
                viewModel.closeStationMenu()
                viewModel.tuneTo(station)
            },
            onFavorite = { viewModel.toggleStationFavorite(station) },
            onMove = { viewModel.moveStationToTv(station) },
            onDismiss = viewModel::closeStationMenu,
        )
    }
}

/** The stations to switch to, the one tuned to marked; laid out like the queue. */
@Composable
private fun StationsPanel(
    radio: RadioState,
    playing: Boolean,
    nowMs: Long,
    menuOpen: Boolean,
    onPick: (LiveChannel) -> Unit,
    onFavorite: (LiveChannel) -> Unit,
    onMenu: (LiveChannel) -> Unit,
    modifier: Modifier,
) {
    val shape = RoundedCornerShape(GlacierShapes.RadiusLg)
    val listState = rememberLazyListState()
    val currentFocus = remember { FocusRequester() }
    var focusedInside by remember { mutableStateOf(false) }
    val index = radio.stations.indexOfFirst { it.id == radio.station.id }
    // The options closed: back on the station they were about, or on the list when it moved away.
    val stationFocus = remember { mutableMapOf<String, FocusRequester>() }
    var menuFrom by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(menuOpen) {
        if (menuOpen) return@LaunchedEffect
        val from = menuFrom ?: return@LaunchedEffect
        menuFrom = null
        withFrameNanos { }
        val row = stationFocus[from]
        if (row == null || runCatching { row.requestFocus() }.isFailure) runCatching { currentFocus.requestFocus() }
    }
    LaunchedEffect(index, focusedInside) {
        if (!focusedInside && index >= 0) listState.animateScrollToItem((index - 1).coerceAtLeast(0))
    }
    Column(
        modifier
            .clip(shape)
            .background(GlacierColors.GlassFill2)
            .border(1.dp, GlacierColors.GlassBorder2, shape),
    ) {
        Column(Modifier.padding(start = 32.dp, end = 32.dp, top = 28.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.radio_stations), style = GlacierText.display(28), color = GlacierColors.Ice)
            Text(
                pluralStringResource(R.plurals.radio_station_count, radio.stations.size, radio.stations.size),
                style = GlacierText.body(18),
                color = GlacierColors.Mist,
            )
        }
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .fillMaxSize()
                .onFocusChanged { focusedInside = it.hasFocus }
                // Coming in from the buttons lands on the station that plays.
                .focusProperties { onEnter = { if (index >= 0) currentFocus.requestFocus() } },
        ) {
            itemsIndexed(radio.stations, key = { _, station -> station.id }) { i, station ->
                val key = station.id.toString()
                val rowFocus = remember(key) { stationFocus.getOrPut(key) { FocusRequester() } }
                StationRow(
                    station = station,
                    nowTitle = station.now?.takeIf { it.airsAt(nowMs) }?.title,
                    current = i == index,
                    playing = playing,
                    onClick = { onPick(station) },
                    onFavorite = { onFavorite(station) },
                    onMenu = {
                        menuFrom = key
                        onMenu(station)
                    },
                    modifier = Modifier.focusRequester(rowFocus).then(if (i == index) Modifier.focusRequester(currentFocus) else Modifier),
                )
            }
        }
    }
}

@Composable
private fun StationRow(
    station: LiveChannel,
    nowTitle: String?,
    current: Boolean,
    playing: Boolean,
    onClick: () -> Unit,
    onFavorite: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier,
) {
    val accent = LocalAccent.current.main
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    var inside by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(GlacierShapes.RadiusMd)
    // One card: the station and its heart; it lights up while either has the focus.
    Row(
        Modifier
            .fillMaxWidth()
            .onFocusChanged { inside = it.hasFocus }
            .scale(if (focused) 1.015f else 1f)
            .clip(shape)
            .background(
                when {
                    inside -> GlacierColors.GlassFill2
                    current -> accent.copy(alpha = 0.14f)
                    else -> Color.Transparent
                },
            )
            .border(2.dp, if (focused) accent else Color.Transparent, shape)
            .padding(end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        StationMain(station, nowTitle, current, playing, focused, interaction, onClick, onMenu, modifier.weight(1f))
        RowIconButton(
            if (station.favorite) GlacierIcons.HeartFilled else GlacierIcons.Heart,
            stringResource(if (station.favorite) R.string.livetv_favorite_remove else R.string.livetv_favorite_add),
            onFavorite,
            modifier = Modifier.focusProperties { right = FocusRequester.Cancel },
            on = station.favorite,
            size = 44,
        )
    }
}

/** The station itself: OK switches to it, holding OK (or the menu key) opens its options; the mark says so. */
@Composable
private fun StationMain(
    station: LiveChannel,
    nowTitle: String?,
    current: Boolean,
    playing: Boolean,
    focused: Boolean,
    interaction: MutableInteractionSource,
    onClick: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier,
) {
    val accent = LocalAccent.current.main
    Row(
        modifier
            .fillMaxWidth()
            .height(76.dp)
            .onMenuKey(onMenu)
            .combinedClickable(interactionSource = interaction, indication = null, onLongClick = onMenu, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(Modifier.width(30.dp), contentAlignment = Alignment.Center) {
            if (current) Equalizer(playing) else station.number?.let { Text(it, style = GlacierText.mono(17), color = GlacierColors.Mist, maxLines = 1) }
        }
        ChannelLogo(station, Modifier.width(96.dp).height(54.dp), textSize = 17)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                station.name,
                style = GlacierText.body(19, FontWeight.SemiBold),
                color = if (current) accent else GlacierColors.Ice,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            nowTitle?.let { Text(it, style = GlacierText.body(16), color = GlacierColors.Mist, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        HoldOkMark(focused, accent, size = 26)
    }
}
