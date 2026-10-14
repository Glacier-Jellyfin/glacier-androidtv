package io.github.glacier_jellyfin.androidtv.livetv

import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.data.media.LiveChannel
import io.github.glacier_jellyfin.androidtv.core.data.media.LiveProgram
import io.github.glacier_jellyfin.androidtv.core.data.media.nowAndNext
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierIcons
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.glacier_jellyfin.androidtv.core.designsystem.LocalAccent
import io.github.glacier_jellyfin.androidtv.core.designsystem.PillButton
import io.github.glacier_jellyfin.androidtv.core.designsystem.SpinningDiamond
import io.github.glacier_jellyfin.androidtv.core.designsystem.focusFrame
import io.github.glacier_jellyfin.androidtv.core.designsystem.onMenuKey
import io.github.glacier_jellyfin.androidtv.ui.Artwork
import io.github.glacier_jellyfin.androidtv.ui.CollectEvents
import io.github.glacier_jellyfin.androidtv.ui.FilterChip
import io.github.glacier_jellyfin.androidtv.ui.HoldOkMark
import io.github.glacier_jellyfin.androidtv.ui.LocalLibraryKinds
import io.github.glacier_jellyfin.androidtv.ui.NavTarget
import io.github.glacier_jellyfin.androidtv.ui.PageEdge
import io.github.glacier_jellyfin.androidtv.ui.ToggleChip
import io.github.glacier_jellyfin.androidtv.ui.TopNav
import io.github.glacier_jellyfin.androidtv.ui.UiEvent

private val RowShape = RoundedCornerShape(18.dp)

/**
 * The channels of the server's Live TV: a list with what runs on each now,
 * and beside it the focused channel's programme and what follows. OK
 * watches, holding OK (or the menu key) marks a favorite. The guide, the
 * recordings and the radio stations are the other views; a station plays
 * in the music player.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun LiveTvScreen(
    onNavigate: (UiEvent.Navigate) -> Unit,
    viewModel: LiveTvViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    CollectEvents(viewModel.events, onNavigate)
    val listFocus = remember { FocusRequester() }
    val chipsFocus = remember { FocusRequester() }
    val gridFocus = remember { FocusRequester() }
    val recordingsFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    // Back from a channel lands on it again; the first time on the first channel.
    var lastFocus by rememberSaveable { mutableStateOf<String?>(null) }
    /** The row that has the focus right now, if one has. */
    var focusedRow by remember { mutableStateOf<String?>(null) }
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    val shown = state.shown
    // Zapped in the player: back here, the list lands on the channel watched last, once.
    val watched by viewModel.lastWatched.collectAsStateWithLifecycle()
    var landed by rememberSaveable { mutableStateOf<String?>(null) }

    // Back from inside a view (the long guide above all) first goes up to the chips; from there it leaves the page.
    var contentFocused by remember { mutableStateOf(false) }
    val contentFocus = Modifier.onFocusChanged { contentFocused = it.hasFocus }
    val activeChip = remember { FocusRequester() }
    BackHandler(enabled = contentFocused && state.sheet == null) {
        runCatching { activeChip.requestFocus() }.onFailure { runCatching { chipsFocus.requestFocus() } }
    }

    // The preview's tuner is freed while the page is away (player, other app), and comes back with it.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.stopPreview() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.schedulePreview() }

    // Back from playing a recording: the list shows what changed meanwhile.
    LaunchedEffect(Unit) { if (state.view == LiveTvView.Recordings) viewModel.loadRecordings() }
    // The sheet took the focus along: the guide gets it back (the recordings see to it themselves).
    var sheetWasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(state.sheet == null) {
        if (state.sheet != null) {
            sheetWasOpen = true
            return@LaunchedEffect
        }
        if (!sheetWasOpen) return@LaunchedEffect
        sheetWasOpen = false
        when (state.view) {
            LiveTvView.Guide -> {
                withFrameNanos { }
                runCatching { gridFocus.requestFocus() }
            }
            // Back on the row the sheet came from; the list's first one when it moved to the other list.
            LiveTvView.Channels, LiveTvView.Radio -> {
                withFrameNanos { }
                val row = lastFocus?.let { requesters[it] }
                if (row == null || runCatching { row.requestFocus() }.isFailure || focusedRow != lastFocus) runCatching { listFocus.requestFocus() }
            }
            LiveTvView.Recordings -> Unit
        }
    }

    // The favorites toggle keeps the focus, so it can be switched right back.
    var toggled by remember { mutableStateOf(false) }
    LaunchedEffect(shown != null, state.favoritesOnly, watched) {
        if (shown == null) return@LaunchedEffect
        if (toggled) {
            toggled = false
            return@LaunchedEffect
        }
        val watchedKey = watched?.toString()
        // Zapped away from the favorites: the list shows all channels again, to land on the one watched.
        if (watchedKey != null && watchedKey != landed && state.favoritesOnly && shown.none { it.id.toString() == watchedKey } &&
            state.listed?.any { it.id.toString() == watchedKey } == true
        ) {
            viewModel.setFavoritesOnly(false)
            return@LaunchedEffect
        }
        if (watchedKey != null && watchedKey != landed && shown.any { it.id.toString() == watchedKey }) {
            landed = watchedKey
            lastFocus = watchedKey
        }
        if (state.view == LiveTvView.Recordings) return@LaunchedEffect
        if (shown.isEmpty()) {
            withFrameNanos { }
            runCatching { chipsFocus.requestFocus() }
            return@LaunchedEffect
        }
        if (state.view == LiveTvView.Guide) {
            withFrameNanos { }
            runCatching { gridFocus.requestFocus() }
            return@LaunchedEffect
        }
        val index = shown.indexOfFirst { it.id.toString() == lastFocus }.coerceAtLeast(0)
        listState.scrollToItem(index)
        // Back from the player the page is still coming in: the row may refuse the focus for a
        // few frames. And focus coming into the list from outside goes to the row the list had
        // before (its focus restorer); once inside, the second request reaches the row.
        val key = shown[index].id.toString()
        var restored = false
        for (frame in 0 until FOCUS_FRAMES) {
            withFrameNanos { }
            requesters[key]?.let { runCatching { it.requestFocus() } }
            restored = focusedRow == key
            if (restored) break
        }
        if (!restored) runCatching { listFocus.requestFocus() }
    }

    Box(Modifier.fillMaxSize()) {
        val channels = state.channels
        when {
            state.loading && channels == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { SpinningDiamond(110) }
            state.failed && channels == null -> Message(
                text = stringResource(R.string.livetv_error),
                body = stringResource(R.string.livetv_error_body),
                button = stringResource(R.string.action_retry),
                onClick = viewModel::load,
            )
            channels == null -> Unit
            channels.isEmpty() && state.stations.isEmpty() -> Message(text = stringResource(R.string.livetv_empty), body = stringResource(R.string.livetv_empty_body))
            else -> Column(Modifier.fillMaxSize().padding(start = PageEdge.dp, end = PageEdge.dp, top = 150.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(stringResource(R.string.nav_live_tv), style = GlacierText.display(50), color = GlacierColors.Ice)
                    Spacer(Modifier.weight(1f))
                    Text(clockText(state.nowMs), style = GlacierText.display(34), color = GlacierColors.Mist)
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.padding(top = 22.dp, bottom = 22.dp).focusRequester(chipsFocus).focusRestorer(),
                ) {
                    // A tuner with radio only has no TV to list or guide.
                    if (channels.isNotEmpty()) {
                        FilterChip(
                            stringResource(R.string.livetv_channels),
                            active = state.view == LiveTvView.Channels,
                            onClick = { viewModel.setView(LiveTvView.Channels) },
                            modifier = if (state.view == LiveTvView.Channels) Modifier.focusRequester(activeChip) else Modifier,
                        )
                        FilterChip(
                            stringResource(R.string.livetv_guide),
                            active = state.view == LiveTvView.Guide,
                            onClick = { viewModel.setView(LiveTvView.Guide) },
                            modifier = if (state.view == LiveTvView.Guide) Modifier.focusRequester(activeChip) else Modifier,
                        )
                    }
                    if (state.stations.isNotEmpty()) {
                        FilterChip(
                            stringResource(R.string.livetv_radio),
                            active = state.view == LiveTvView.Radio,
                            onClick = { viewModel.setView(LiveTvView.Radio) },
                            modifier = if (state.view == LiveTvView.Radio) Modifier.focusRequester(activeChip) else Modifier,
                        )
                    }
                    FilterChip(
                        stringResource(R.string.livetv_recordings),
                        active = state.view == LiveTvView.Recordings,
                        onClick = { viewModel.setView(LiveTvView.Recordings) },
                        modifier = if (state.view == LiveTvView.Recordings) Modifier.focusRequester(activeChip) else Modifier,
                    )
                    Spacer(Modifier.weight(1f))
                    // Recordings are not listed by channel: the channel filter has nothing to do there.
                    if (state.view != LiveTvView.Recordings) {
                        ToggleChip(
                            label = stringResource(R.string.livetv_favorites),
                            on = state.favoritesOnly,
                            icon = GlacierIcons.Heart,
                            iconOn = GlacierIcons.HeartFilled,
                            onClick = {
                                toggled = true
                                viewModel.setFavoritesOnly(!state.favoritesOnly)
                            },
                        )
                    }
                }
                if (state.view == LiveTvView.Recordings) {
                    RecordingsView(
                        recordings = state.recordings,
                        loading = state.recordingsLoading,
                        failed = state.recordingsFailed,
                        nowMs = state.nowMs,
                        sheetOpen = state.sheet != null,
                        focus = recordingsFocus,
                        up = chipsFocus,
                        onRetry = viewModel::loadRecordings,
                        onPlay = viewModel::playRecording,
                        onRecordingMenu = viewModel::openRecording,
                        onTimer = viewModel::openTimer,
                        onSeriesTimer = viewModel::openSeriesTimer,
                        modifier = contentFocus,
                    )
                } else if (state.view == LiveTvView.Guide && !shown.isNullOrEmpty()) {
                    GuideGrid(
                        channels = shown,
                        landOn = watched,
                        guide = state.guide,
                        fromMs = state.guideFromMs,
                        toMs = state.guideToMs,
                        nowMs = state.nowMs,
                        focus = gridFocus,
                        up = chipsFocus,
                        onLoad = viewModel::loadGuide,
                        onOpen = viewModel::openProgram,
                        onMenu = viewModel::programMenu,
                        modifier = Modifier.fillMaxSize().padding(bottom = 36.dp).then(contentFocus),
                    )
                } else Row(Modifier.fillMaxSize().then(contentFocus)) {
                    if (shown.isNullOrEmpty()) {
                        Column(Modifier.width(LIST_WIDTH.dp).padding(top = 40.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(stringResource(R.string.livetv_favorites_empty), style = GlacierText.display(30), color = GlacierColors.Ice)
                            Text(stringResource(R.string.livetv_favorites_empty_body), style = GlacierText.body(20), color = GlacierColors.Mist)
                        }
                    } else {
                        LazyColumn(
                            state = listState,
                            contentPadding = PaddingValues(top = 8.dp, bottom = 80.dp, start = 8.dp, end = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier
                                .width(LIST_WIDTH.dp)
                                .fillMaxHeight()
                                .focusRequester(listFocus)
                                .focusRestorer(),
                        ) {
                            items(shown, key = { it.id }) { channel ->
                                val key = channel.id.toString()
                                val requester = remember(key) { requesters.getOrPut(key) { FocusRequester() } }
                                ChannelRow(
                                    channel = channel,
                                    nowMs = state.nowMs,
                                    onClick = { viewModel.play(channel) },
                                    onFavorite = { viewModel.toggleFavorite(channel) },
                                    onMenu = { viewModel.channelMenu(channel) },
                                    // The panel follows the row, also while its buttons have the focus.
                                    onFocused = {
                                        lastFocus = key
                                        viewModel.onFocus(channel)
                                    },
                                    // The chips sit above the list; focus search would pass them for the navigation.
                                    up = chipsFocus.takeIf { channel.id == shown.first().id },
                                    modifier = Modifier
                                        .focusRequester(requester)
                                        .onFocusChanged { if (it.isFocused) focusedRow = key else if (focusedRow == key) focusedRow = null },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.width(56.dp))
                    // A channel the filter hid gives way to the first one shown.
                    val focused = shown?.firstOrNull { it.id == state.focused } ?: shown?.firstOrNull()
                    if (focused != null && !shown.isNullOrEmpty()) {
                        ProgramPanel(
                            channel = focused,
                            upcoming = state.upcoming[focused.id],
                            nowMs = state.nowMs,
                            preview = state.preview?.takeIf { state.previewChannel == focused.id },
                            previewShown = state.previewShown,
                            modifier = Modifier.weight(1f).fillMaxHeight().padding(top = 8.dp, bottom = 80.dp),
                        )
                    }
                }
            }
        }

        TopNav(
            active = NavTarget.LiveTv,
            kinds = LocalLibraryKinds.current,
            userName = state.userName,
            onSelect = viewModel::onNav,
            down = when {
                state.view == LiveTvView.Recordings -> if (state.recordings?.isEmpty == false) recordingsFocus else chipsFocus
                shown.isNullOrEmpty() -> chipsFocus
                state.view == LiveTvView.Guide -> gridFocus
                else -> listFocus
            },
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 34.dp),
        )

        state.sheet?.let { LiveTvSheetHost(it, state.nowMs, state.all, viewModel) }
    }
}

/**
 * A channel or station: OK plays it, holding OK opens its options, which the
 * mark at the end of the focused card says. The heart sits inside the card
 * at its end; nothing further right takes focus. The card lights up while
 * either has the focus.
 */
@Composable
private fun ChannelRow(
    channel: LiveChannel,
    nowMs: Long,
    onClick: () -> Unit,
    onFavorite: () -> Unit,
    onMenu: () -> Unit,
    onFocused: () -> Unit,
    up: FocusRequester?,
    modifier: Modifier = Modifier,
) {
    val accent = LocalAccent.current.main
    val upFocus = Modifier.focusProperties { if (up != null) this.up = up }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    var inside by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .onFocusChanged {
                inside = it.hasFocus
                if (it.hasFocus) onFocused()
            }
            .focusFrame(focused, RowShape)
            .clip(RowShape)
            .background(if (inside) GlacierColors.GlassFill2 else GlacierColors.GlassFill)
            .padding(end = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier
                .then(upFocus)
                .weight(1f)
                .onMenuKey(onMenu)
                .combinedClickable(interactionSource = interaction, indication = null, onLongClick = onMenu, onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            ChannelLogo(channel, Modifier.width(136.dp).height(76.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    channel.number?.let { Text(it, style = GlacierText.mono(19), color = if (focused) accent else GlacierColors.Mist) }
                    Text(
                        channel.name,
                        style = GlacierText.body(22, FontWeight.SemiBold),
                        color = GlacierColors.Ice,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                val now = channel.now?.takeIf { it.airsAt(nowMs) }
                Text(
                    now?.title ?: stringResource(R.string.livetv_no_guide),
                    style = GlacierText.body(20),
                    color = if (now != null) GlacierColors.Ice.copy(alpha = 0.86f) else GlacierColors.Mist,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (now != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        ProgramProgress(now.progressAt(nowMs), Modifier.width(200.dp))
                        Text(timeRange(now), style = GlacierText.body(16), color = GlacierColors.Mist)
                    }
                }
            }
            HoldOkMark(focused, accent)
        }
        RowIconButton(
            if (channel.favorite) GlacierIcons.HeartFilled else GlacierIcons.Heart,
            stringResource(if (channel.favorite) R.string.livetv_favorite_remove else R.string.livetv_favorite_add),
            onFavorite,
            modifier = upFocus.focusProperties { right = FocusRequester.Cancel },
            on = channel.favorite,
        )
    }
}

/** The focused channel: its programme now, large, and what follows. */
@Composable
@OptIn(UnstableApi::class)
private fun ProgramPanel(
    channel: LiveChannel,
    upcoming: List<LiveProgram>?,
    nowMs: Long,
    preview: Player?,
    previewShown: Boolean,
    modifier: Modifier = Modifier,
) {
    val (fromGuide, next) = upcoming?.let { nowAndNext(it, nowMs) } ?: (null to null)
    val now = fromGuide ?: channel.now?.takeIf { it.airsAt(nowMs) }
    val later = upcoming.orEmpty().filter { it.startMs >= (now?.endMs ?: nowMs) }.drop(1).take(1)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val shape = RoundedCornerShape(18.dp)
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(shape),
            contentAlignment = Alignment.Center,
        ) {
            // The preview needs its surface before the first frame can come; the picture covers it until then.
            // A texture view, so the rounded corners cut the video too.
            if (preview != null) {
                Box(Modifier.fillMaxSize().background(Color.Black))
                ContentFrame(player = preview, modifier = Modifier.fillMaxSize(), surfaceType = SURFACE_TYPE_TEXTURE_VIEW, contentScale = ContentScale.Fit)
            }
            if (preview == null || !previewShown) {
                val image = now?.imageUrl
                if (image != null) {
                    Artwork(image, Modifier.fillMaxSize())
                } else {
                    Box(Modifier.fillMaxSize().background(if (preview != null) GlacierColors.GlassFill.compositeOver(GlacierColors.Deep) else GlacierColors.GlassFill))
                    ChannelLogo(channel, Modifier.width(260.dp).height(146.dp), textSize = 44)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            LiveBadge()
            Text(
                listOfNotNull(channel.number, channel.name).joinToString("  "),
                style = GlacierText.body(19, FontWeight.SemiBold),
                color = GlacierColors.Mist,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (now == null) {
            // The channel's programmes are still on their way.
            if (upcoming == null) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { SpinningDiamond(56) }
            } else {
                Text(stringResource(R.string.livetv_no_guide), style = GlacierText.display(32), color = GlacierColors.Ice)
            }
            return@Column
        }
        Text(now.title, style = GlacierText.display(34), color = GlacierColors.Ice, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            listOfNotNull(timeRange(now), minutesLeft(now, nowMs), now.episodeTitle).joinToString(" · "),
            style = GlacierText.body(19),
            color = GlacierColors.Mist,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        RecordMark(now, nowMs)
        now.overview?.let {
            Text(it, style = GlacierText.body(19), color = GlacierColors.Ice.copy(alpha = 0.78f), maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
        val following = listOfNotNull(next) + later
        if (following.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.livetv_next), style = GlacierText.label(15), color = GlacierColors.Mist)
            following.forEach { program ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(clockText(program.startMs), style = GlacierText.mono(19), color = GlacierColors.Mist)
                    Text(
                        program.title,
                        style = GlacierText.body(19),
                        color = GlacierColors.Ice,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (program.recorded) RecordDot(program, Modifier.size(16.dp))
                }
            }
        }
    }
}

/** Nothing to show: a line, an explanation and maybe a button, in the middle of the page. */
@Composable
private fun Message(text: String, body: String, button: String? = null, onClick: () -> Unit = {}) {
    val focus = remember { FocusRequester() }
    if (button != null) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
    ) {
        Text(text, style = GlacierText.display(30), color = GlacierColors.Ice)
        Text(body, style = GlacierText.body(20), color = GlacierColors.Mist)
        if (button != null) PillButton(button, onClick = onClick, primary = true, modifier = Modifier.focusRequester(focus))
    }
}

private const val LIST_WIDTH = 1000

/** How many frames a focus request on the list keeps trying while the page comes in. */
private const val FOCUS_FRAMES = 30
