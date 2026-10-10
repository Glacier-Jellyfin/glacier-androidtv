package io.github.glacier_jellyfin.androidtv.livetv

import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.ContentFrame
import androidx.tv.material3.Text
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.data.media.LiveChannel
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierClickable
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.glacier_jellyfin.androidtv.core.designsystem.LocalAccent
import io.github.glacier_jellyfin.androidtv.core.designsystem.PillButton
import io.github.glacier_jellyfin.androidtv.core.designsystem.SpinningDiamond
import io.github.glacier_jellyfin.androidtv.core.designsystem.ignoreHeldOk
import io.github.glacier_jellyfin.androidtv.player.osdScrim
import kotlinx.coroutines.delay

/** The channel banner hides after this long without input. */
private const val BANNER_TIMEOUT_MS = 5_000L

/** Typed channel digits tune after this pause. */
private const val NUMBER_COMMIT_MS = 1_500L

/**
 * A Live TV channel, full screen. Up and down (or the channel keys) zap,
 * OK and Left open the channel list, Right and Info show the channel
 * banner. Digits tune to a channel number, the "last channel" key goes
 * back to the one before.
 */
@OptIn(UnstableApi::class)
@Composable
fun LiveTvPlayerScreen(
    onBack: () -> Unit,
    viewModel: LiveTvPlayerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.finished.collect { onBack() } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onBackground() }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.onForeground() }
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    val rootFocus = remember { FocusRequester() }
    var bannerVisible by remember { mutableStateOf(true) }
    var listOpen by remember { mutableStateOf(false) }
    var interaction by remember { mutableIntStateOf(0) }
    var typed by remember { mutableStateOf("") }
    var unknownNumber by remember { mutableStateOf<String?>(null) }
    BackHandler(enabled = listOpen) { listOpen = false }
    BackHandler(enabled = !listOpen) { viewModel.stop() }

    fun showBanner() {
        bannerVisible = true
        interaction++
    }

    fun commitNumber() {
        val number = typed.takeIf { it.isNotEmpty() } ?: return
        typed = ""
        if (!viewModel.tuneNumber(number)) unknownNumber = number
        showBanner()
    }

    LaunchedEffect(bannerVisible, interaction, state.loading, state.failed, listOpen) {
        if (bannerVisible && !state.loading && !state.failed && !listOpen) {
            delay(BANNER_TIMEOUT_MS)
            bannerVisible = false
        }
    }
    LaunchedEffect(typed) {
        if (typed.isEmpty()) return@LaunchedEffect
        delay(NUMBER_COMMIT_MS)
        commitNumber()
    }
    LaunchedEffect(unknownNumber) {
        if (unknownNumber == null) return@LaunchedEffect
        delay(NUMBER_COMMIT_MS)
        unknownNumber = null
    }
    // Closing the list hands the keys back to the picture.
    LaunchedEffect(listOpen, state.failed) {
        if (listOpen || state.failed) return@LaunchedEffect
        withFrameNanos { }
        runCatching { rootFocus.requestFocus() }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            .onPreviewKeyEvent { event ->
                if (listOpen) {
                    // Compose would turn Back into "exit focus" inside the list; close it instead.
                    if (event.key == Key.Back) {
                        if (event.type == KeyEventType.KeyUp) listOpen = false
                        return@onPreviewKeyEvent true
                    }
                    return@onPreviewKeyEvent false
                }
                if (event.key == Key.Back) {
                    if (event.type == KeyEventType.KeyUp) viewModel.stop()
                    return@onPreviewKeyEvent true
                }
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                interaction++
                digit(event.key)?.let { digit ->
                    if (typed.length < MAX_DIGITS) typed += digit
                    return@onPreviewKeyEvent true
                }
                when (event.key) {
                    Key.DirectionUp, Key.ChannelUp, Key.PageUp -> {
                        viewModel.zap(1)
                        showBanner()
                        true
                    }
                    Key.DirectionDown, Key.ChannelDown, Key.PageDown -> {
                        viewModel.zap(-1)
                        showBanner()
                        true
                    }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        when {
                            typed.isNotEmpty() -> commitNumber()
                            state.failed -> return@onPreviewKeyEvent false
                            else -> listOpen = true
                        }
                        true
                    }
                    Key.DirectionLeft -> {
                        if (!state.failed) listOpen = true
                        !state.failed
                    }
                    Key.DirectionRight, Key.Info, Key.Guide -> {
                        if (state.failed) return@onPreviewKeyEvent false
                        showBanner()
                        true
                    }
                    Key.LastChannel -> {
                        viewModel.lastChannel()
                        showBanner()
                        true
                    }
                    Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> {
                        viewModel.togglePlay()
                        showBanner()
                        true
                    }
                    else -> false
                }
            }
            .focusable(),
    ) {
        state.player?.let { player ->
            ContentFrame(player = player, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        }

        if (state.loading && !state.failed) {
            Box(Modifier.align(Alignment.Center)) { SpinningDiamond(90) }
        }

        AnimatedVisibility(visible = (bannerVisible || state.loading || state.paused) && !state.failed && !listOpen, enter = fadeIn(), exit = fadeOut()) {
            ChannelBanner(state)
        }

        if (typed.isNotEmpty() || unknownNumber != null) {
            NumberEntry(
                text = typed.ifEmpty { unknownNumber.orEmpty() },
                unknown = typed.isEmpty(),
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 64.dp, end = 80.dp),
            )
        }

        if (state.failed) {
            val retryFocus = remember { FocusRequester() }
            Column(
                Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 80.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(28.dp),
            ) {
                Text(state.channel?.name ?: stringResource(R.string.nav_live_tv), style = GlacierText.body(21, FontWeight.SemiBold), color = GlacierColors.Mist)
                Text(stringResource(R.string.livetv_play_error), style = GlacierText.display(30), color = GlacierColors.Ice)
                Text(stringResource(R.string.livetv_play_error_body), style = GlacierText.body(21), color = GlacierColors.Mist)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    PillButton(stringResource(R.string.action_retry), onClick = viewModel::retry, primary = true, modifier = Modifier.focusRequester(retryFocus))
                    PillButton(stringResource(R.string.action_back), onClick = viewModel::stop)
                }
            }
            LaunchedEffect(Unit) {
                withFrameNanos { }
                runCatching { retryFocus.requestFocus() }
            }
        }

        AnimatedVisibility(
            visible = listOpen,
            enter = slideInHorizontally { -it } + fadeIn(),
            exit = slideOutHorizontally { -it } + fadeOut(),
            modifier = Modifier.fillMaxHeight(),
        ) {
            ChannelList(
                channels = state.channels,
                current = state.channel,
                nowMs = state.nowMs,
                onPick = { channel ->
                    viewModel.tune(channel)
                    listOpen = false
                    showBanner()
                },
            )
        }
    }
}

/** What runs on the channel: logo, number and name, the programme with its progress, and what follows. */
@Composable
private fun ChannelBanner(state: LiveTvPlayerState) {
    val channel = state.channel ?: return
    val now = state.now?.takeIf { it.airsAt(state.nowMs) }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.align(Alignment.BottomCenter).osdScrim(top = false))
        Row(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(start = 80.dp, end = 80.dp, bottom = 64.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(32.dp),
        ) {
            ChannelLogo(channel, Modifier.width(200.dp).height(112.dp), textSize = 36)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    LiveBadge()
                    channel.number?.let { Text(it, style = GlacierText.mono(22), color = LocalAccent.current.main) }
                    Text(channel.name, style = GlacierText.body(22, FontWeight.SemiBold), color = GlacierColors.Ice, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (state.paused) Text(stringResource(R.string.livetv_paused), style = GlacierText.body(20), color = GlacierColors.Mist)
                }
                Text(
                    now?.title ?: stringResource(R.string.livetv_no_guide),
                    style = GlacierText.display(40),
                    color = GlacierColors.Ice,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (now != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        ProgramProgress(now.progressAt(state.nowMs), Modifier.width(520.dp), height = 6)
                        Text(
                            listOfNotNull(timeRange(now), minutesLeft(now, state.nowMs), now.episodeTitle).joinToString(" · "),
                            style = GlacierText.body(20),
                            color = GlacierColors.Mist,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                state.next?.let { next ->
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(top = 4.dp)) {
                        Text(stringResource(R.string.livetv_next), style = GlacierText.label(16), color = GlacierColors.Mist)
                        Text(clockText(next.startMs), style = GlacierText.mono(19), color = GlacierColors.Mist)
                        Text(next.title, style = GlacierText.body(19), color = GlacierColors.Ice, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Text(clockText(state.nowMs), style = GlacierText.display(34), color = GlacierColors.Ice)
        }
    }
}

/** The digits typed on the remote, or a number no channel has. */
@Composable
private fun NumberEntry(text: String, unknown: Boolean, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier
            .clip(shape)
            .background(GlacierColors.Deep.copy(alpha = 0.88f))
            .padding(horizontal = 30.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.End,
    ) {
        Text(text, style = GlacierText.display(56), color = if (unknown) GlacierColors.Mist else GlacierColors.Ice)
        if (unknown) Text(stringResource(R.string.livetv_no_channel), style = GlacierText.body(18), color = GlacierColors.Mist)
    }
}

/** The channels at the left edge, focused on the one playing; OK switches. */
@Composable
private fun ChannelList(
    channels: List<LiveChannel>,
    current: LiveChannel?,
    nowMs: Long,
    onPick: (LiveChannel) -> Unit,
) {
    val listState = rememberLazyListState()
    val currentFocus = remember { FocusRequester() }
    val currentIndex = channels.indexOfFirst { it.id == current?.id }.coerceAtLeast(0)
    LaunchedEffect(Unit) {
        listState.scrollToItem((currentIndex - 3).coerceAtLeast(0))
        withFrameNanos { }
        runCatching { currentFocus.requestFocus() }
    }
    Box(
        Modifier
            .fillMaxHeight()
            .width(680.dp)
            .background(Brush.horizontalGradient(listOf(GlacierColors.Void, GlacierColors.Void.copy(alpha = 0.95f)))),
    ) {
        Column(Modifier.fillMaxSize().padding(top = 56.dp)) {
            Text(
                stringResource(R.string.livetv_channels),
                style = GlacierText.display(34),
                color = GlacierColors.Ice,
                modifier = Modifier.padding(start = 48.dp, bottom = 18.dp),
            )
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(start = 40.dp, end = 40.dp, top = 8.dp, bottom = 60.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .ignoreHeldOk()
                    .focusProperties { onExit = { cancelFocusChange() } }
                    .focusGroup(),
            ) {
                items(channels, key = { it.id }) { channel ->
                    val playing = channel.id == current?.id
                    val shape = RoundedCornerShape(14.dp)
                    GlacierClickable(
                        onClick = { onPick(channel) },
                        shape = shape,
                        scaleOnFocus = false,
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(if (playing) Modifier.focusRequester(currentFocus) else Modifier),
                    ) { focused ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(shape)
                                .background(
                                    when {
                                        focused -> GlacierColors.GlassFill2
                                        playing -> LocalAccent.current.main.copy(alpha = 0.14f)
                                        else -> Color.Transparent
                                    },
                                )
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(18.dp),
                        ) {
                            ChannelLogo(channel, Modifier.width(100.dp).height(56.dp), textSize = 20)
                            Column(Modifier.weight(1f)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    channel.number?.let { Text(it, style = GlacierText.mono(17), color = GlacierColors.Mist) }
                                    Text(channel.name, style = GlacierText.body(20, FontWeight.SemiBold), color = GlacierColors.Ice, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                val now = channel.now?.takeIf { it.airsAt(nowMs) }
                                Text(
                                    now?.title ?: stringResource(R.string.livetv_no_guide),
                                    style = GlacierText.body(18),
                                    color = GlacierColors.Mist,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (now != null) {
                                    Spacer(Modifier.height(6.dp))
                                    ProgramProgress(now.progressAt(nowMs), Modifier.width(180.dp), height = 3)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun digit(key: Key): Char? = when (key) {
    Key.Zero, Key.NumPad0 -> '0'
    Key.One, Key.NumPad1 -> '1'
    Key.Two, Key.NumPad2 -> '2'
    Key.Three, Key.NumPad3 -> '3'
    Key.Four, Key.NumPad4 -> '4'
    Key.Five, Key.NumPad5 -> '5'
    Key.Six, Key.NumPad6 -> '6'
    Key.Seven, Key.NumPad7 -> '7'
    Key.Eight, Key.NumPad8 -> '8'
    Key.Nine, Key.NumPad9 -> '9'
    else -> null
}

private const val MAX_DIGITS = 5
