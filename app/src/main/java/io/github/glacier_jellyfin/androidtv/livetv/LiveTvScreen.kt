package io.github.glacier_jellyfin.androidtv.livetv

import androidx.compose.foundation.background
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.data.media.LiveChannel
import io.github.glacier_jellyfin.androidtv.core.data.media.LiveProgram
import io.github.glacier_jellyfin.androidtv.core.data.media.nowAndNext
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierClickable
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierIcons
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.glacier_jellyfin.androidtv.core.designsystem.LocalAccent
import io.github.glacier_jellyfin.androidtv.core.designsystem.PillButton
import io.github.glacier_jellyfin.androidtv.core.designsystem.SpinningDiamond
import io.github.glacier_jellyfin.androidtv.ui.Artwork
import io.github.glacier_jellyfin.androidtv.ui.CollectEvents
import io.github.glacier_jellyfin.androidtv.ui.FilterChip
import io.github.glacier_jellyfin.androidtv.ui.LocalLibraryKinds
import io.github.glacier_jellyfin.androidtv.ui.NavTarget
import io.github.glacier_jellyfin.androidtv.ui.PageEdge
import io.github.glacier_jellyfin.androidtv.ui.TopNav
import io.github.glacier_jellyfin.androidtv.ui.UiEvent

private val RowShape = RoundedCornerShape(18.dp)

/**
 * The channels of the server's Live TV: a list with what runs on each now,
 * and beside it the focused channel's programme and what follows. OK
 * watches, holding OK (or the menu key) marks a favorite.
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
    val listState = rememberLazyListState()
    // Back from a channel lands on it again; the first time on the first channel.
    var lastFocus by rememberSaveable { mutableStateOf<String?>(null) }
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    val shown = state.shown

    LaunchedEffect(shown != null, state.favoritesOnly) {
        if (shown == null) return@LaunchedEffect
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
        withFrameNanos { }
        val restored = requesters[shown[index].id.toString()]?.let { runCatching { it.requestFocus() }.getOrDefault(false) } == true
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
            channels.isEmpty() -> Message(text = stringResource(R.string.livetv_empty), body = stringResource(R.string.livetv_empty_body))
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
                    FilterChip(stringResource(R.string.livetv_channels), active = state.view == LiveTvView.Channels, onClick = { viewModel.setView(LiveTvView.Channels) })
                    FilterChip(stringResource(R.string.livetv_guide), active = state.view == LiveTvView.Guide, onClick = { viewModel.setView(LiveTvView.Guide) })
                    Spacer(Modifier.width(28.dp))
                    FilterChip(stringResource(R.string.livetv_all), active = !state.favoritesOnly, onClick = { viewModel.setFavoritesOnly(false) })
                    FilterChip(stringResource(R.string.livetv_favorites), active = state.favoritesOnly, onClick = { viewModel.setFavoritesOnly(true) })
                }
                if (state.view == LiveTvView.Guide && !shown.isNullOrEmpty()) {
                    GuideGrid(
                        channels = shown,
                        guide = state.guide,
                        fromMs = state.guideFromMs,
                        toMs = state.guideToMs,
                        nowMs = state.nowMs,
                        focus = gridFocus,
                        up = chipsFocus,
                        onLoad = viewModel::loadGuide,
                        onOpen = viewModel::openProgram,
                        onFavorite = viewModel::toggleFavorite,
                        modifier = Modifier.fillMaxSize().padding(bottom = 36.dp),
                    )
                } else Row(Modifier.fillMaxSize()) {
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
                                    onLongClick = { viewModel.toggleFavorite(channel) },
                                    modifier = Modifier
                                        .focusRequester(requester)
                                        // Nothing to the right takes focus; the panel only follows it.
                                        .focusProperties {
                                            right = FocusRequester.Cancel
                                            // The chips sit above the list; focus search would pass them for the navigation.
                                            if (channel.id == shown.first().id) up = chipsFocus
                                        }
                                        .onFocusChanged {
                                            if (it.isFocused) {
                                                lastFocus = key
                                                viewModel.onFocus(channel)
                                            }
                                        },
                                )
                            }
                        }
                    }
                    Spacer(Modifier.width(56.dp))
                    val focused = channels.firstOrNull { it.id == state.focused }
                    if (focused != null && !shown.isNullOrEmpty()) {
                        ProgramPanel(focused, state.upcoming[focused.id], state.nowMs, Modifier.weight(1f).padding(top = 8.dp))
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
                shown.isNullOrEmpty() -> chipsFocus
                state.view == LiveTvView.Guide -> gridFocus
                else -> listFocus
            },
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 34.dp),
        )
    }
}

@Composable
private fun ChannelRow(
    channel: LiveChannel,
    nowMs: Long,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = LocalAccent.current.main
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
                    if (channel.favorite) Icon(GlacierIcons.HeartFilled, contentDescription = stringResource(R.string.livetv_favorite), tint = accent, modifier = Modifier.size(20.dp))
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
        }
    }
}

/** The focused channel: its programme now, large, and what follows. */
@Composable
private fun ProgramPanel(channel: LiveChannel, upcoming: List<LiveProgram>?, nowMs: Long, modifier: Modifier = Modifier) {
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
            val image = now?.imageUrl
            if (image != null) {
                Artwork(image, Modifier.fillMaxSize())
            } else {
                Box(Modifier.fillMaxSize().background(GlacierColors.GlassFill))
                ChannelLogo(channel, Modifier.width(260.dp).height(146.dp), textSize = 44)
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
            Text(stringResource(R.string.livetv_no_guide), style = GlacierText.display(32), color = GlacierColors.Ice)
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
        now.overview?.let {
            Text(it, style = GlacierText.body(19), color = GlacierColors.Ice.copy(alpha = 0.78f), maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
        val following = listOfNotNull(next) + later
        if (following.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.livetv_next), style = GlacierText.label(15), color = GlacierColors.Mist)
            following.forEach { program ->
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(clockText(program.startMs), style = GlacierText.mono(19), color = GlacierColors.Mist)
                    Text(program.title, style = GlacierText.body(19), color = GlacierColors.Ice, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
