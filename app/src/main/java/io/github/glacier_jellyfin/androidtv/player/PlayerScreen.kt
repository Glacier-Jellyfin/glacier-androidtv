package io.github.glacier_jellyfin.androidtv.player

import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
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
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.ContentFrame
import androidx.tv.material3.Text
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.glacier_jellyfin.androidtv.core.designsystem.PillButton
import io.github.glacier_jellyfin.androidtv.core.designsystem.SpinningDiamond
import io.github.glacier_jellyfin.androidtv.detail.TrackKind
import io.github.glacier_jellyfin.androidtv.ui.Artwork
import io.github.glacier_jellyfin.androidtv.ui.CollectEvents
import kotlinx.coroutines.delay

/** OSD hides after this long without input while playing (agreed: 3 s). */
private const val OSD_TIMEOUT_MS = 3_000L

/** Scrubbing on the timeline only moves the preview; the jump happens after this pause (design: 1.2 s). */
private const val SCRUB_COMMIT_MS = 1_200L

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    onBack: () -> Unit,
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) { viewModel.finished.collect { onBack() } }
    CollectEvents(viewModel.events, onNavigate = {})
    BackHandler { viewModel.stop() }
    // Leaving the app (Home button) pauses; nobody is watching.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.pause() }
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    val rootFocus = remember { FocusRequester() }
    val seekFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }
    val buttonFocus = remember { OsdButton.entries.associateWith { FocusRequester() } }
    val skipFocus = remember { FocusRequester() }
    val upNextFocus = remember { FocusRequester() }
    val overlayOpen = state.trackPanel != null || state.chaptersOpen
    val upNext = progress.upNext?.let { countdown -> state.next?.let { it to countdown } }
    val skip = progress.skip
    /** Skip button or "Up next" card on screen; with the OSD hidden, it holds the focus. */
    val prompt = when {
        overlayOpen || state.loading || state.failed -> null
        upNext != null -> upNextFocus
        skip != null -> skipFocus
        else -> null
    }
    var promptFocused by remember { mutableStateOf(false) }
    val currentPrompt by rememberUpdatedState(prompt)
    var osdVisible by remember { mutableStateOf(true) }
    /** Which control gets focus when the OSD appears. */
    var osdTarget by remember { mutableStateOf(playFocus) }
    var interaction by remember { mutableIntStateOf(0) }
    var scrubMs by remember { mutableStateOf<Long?>(null) }

    fun showOsd(target: FocusRequester) {
        osdTarget = target
        osdVisible = true
        interaction++
    }

    fun scrub(deltaMs: Long) {
        val duration = progress.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE
        scrubMs = ((scrubMs ?: progress.positionMs) + deltaMs).coerceIn(0, duration)
        interaction++
    }

    LaunchedEffect(scrubMs) {
        val target = scrubMs ?: return@LaunchedEffect
        delay(SCRUB_COMMIT_MS)
        viewModel.seekTo(target)
        scrubMs = null
    }
    LaunchedEffect(osdVisible, interaction, state.playing, scrubMs, overlayOpen) {
        if (osdVisible && state.playing && scrubMs == null && !overlayOpen) {
            delay(OSD_TIMEOUT_MS)
            osdVisible = false
        }
    }
    // Closing an overlay returns focus to the button that opened it.
    var lastOverlay by remember { mutableStateOf<OsdButton?>(null) }
    LaunchedEffect(overlayOpen) {
        val button = lastOverlay ?: return@LaunchedEffect
        if (overlayOpen || !osdVisible) return@LaunchedEffect
        withFrameNanos { }
        runCatching { buttonFocus.getValue(button).requestFocus() }
        lastOverlay = null
    }
    // Showing the OSD focuses its target. Keyed here rather than inside the OSD: an OSD shown again while
    // still fading out keeps its composition, so an effect in there would not run and the root would keep focus.
    LaunchedEffect(osdVisible) {
        if (!osdVisible) return@LaunchedEffect
        withFrameNanos { }
        runCatching { osdTarget.requestFocus() }
    }
    // Hidden, a prompt or the root takes the keys.
    LaunchedEffect(osdVisible, prompt) {
        if (osdVisible) {
            // A prompt that went away while focused hands focus back to the OSD.
            if (prompt == null && promptFocused) {
                promptFocused = false
                withFrameNanos { }
                runCatching { playFocus.requestFocus() }
            }
            return@LaunchedEffect
        }
        if (prompt == null) promptFocused = false
        withFrameNanos { }
        runCatching { (prompt ?: rootFocus).requestFocus() }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            .onPreviewKeyEvent { event ->
                if (overlayOpen) return@onPreviewKeyEvent false
                // Compose turns an unhandled Back into "exit focus": with focus on an OSD control it would
                // first move focus to the root and only the second Back would stop. Stop on the first one.
                // Key up, like the system's back; newer Android also sends Back to the BackHandler.
                if (event.key == Key.Back) {
                    if (event.type == KeyEventType.KeyUp) viewModel.stop()
                    return@onPreviewKeyEvent true
                }
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                interaction++
                when (event.key) {
                    Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> {
                        viewModel.togglePlay()
                        showOsd(playFocus)
                        true
                    }
                    Key.MediaRewind -> { viewModel.seekBy(-state.seekBackMs); true }
                    Key.MediaFastForward -> { viewModel.seekBy(state.seekForwardMs); true }
                    // With the OSD hidden, Left/Right scrub straight away by the seek steps, except between the card's buttons.
                    Key.DirectionLeft, Key.DirectionRight -> if (!osdVisible && !state.loading && prompt != upNextFocus) {
                        showOsd(seekFocus)
                        scrub(if (event.key == Key.DirectionLeft) -state.seekBackMs else state.seekForwardMs)
                        true
                    } else {
                        false
                    }
                    // OK presses a focused prompt.
                    Key.DirectionCenter, Key.Enter -> if (!osdVisible && prompt == null) {
                        showOsd(playFocus)
                        true
                    } else {
                        false
                    }
                    Key.DirectionUp, Key.DirectionDown -> if (!osdVisible) {
                        showOsd(playFocus)
                        true
                    } else {
                        false
                    }
                    else -> false
                }
            }
            .focusable(),
    ) {
        state.playback?.let { playback ->
            val player = playback.player
            ContentFrame(player = player, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            val lift = when {
                state.chaptersOpen -> SubtitleLift.Chapters
                osdVisible -> SubtitleLift.Osd
                else -> SubtitleLift.None
            }
            PlayerSubtitles(playback, lift, state.subtitleStyle, modifier = Modifier.fillMaxSize())
        }

        if (state.loading || state.failed) {
            Artwork(state.details?.item?.backdropUrl, Modifier.fillMaxSize())
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.radialGradient(listOf(Color.Transparent, Color(0x99050910)), radius = 1400f)),
            )
        }

        // The chapter sheet takes the OSD's place at the bottom (design); it would show through otherwise.
        AnimatedVisibility(visible = osdVisible && !state.failed && !state.chaptersOpen, enter = fadeIn(), exit = fadeOut()) {
            PlayerOsd(
                state = state,
                progress = progress,
                scrubMs = scrubMs,
                seekFocus = seekFocus,
                playFocus = playFocus,
                buttonFocus = buttonFocus,
                onOpen = { button ->
                    lastOverlay = button
                    when (button) {
                        OsdButton.Audio -> viewModel.openTracks(TrackKind.Audio)
                        OsdButton.Subtitles -> viewModel.openTracks(TrackKind.Subtitles)
                        OsdButton.Chapters -> viewModel.openChapters()
                    }
                },
                onScrub = ::scrub,
                onCommitScrub = {
                    scrubMs?.let(viewModel::seekTo)
                    scrubMs = null
                },
                onTogglePlay = viewModel::togglePlay,
                onSeekBy = viewModel::seekBy,
                onPrevious = viewModel::playPrevious,
                onNext = viewModel::playNext,
                onStop = viewModel::stop,
            )
        }

        if (prompt != null) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 80.dp, bottom = 300.dp)
                                        // Losing focus because the prompt is going away must not clear the flag the refocus above relies on.
                    .onFocusChanged { if (currentPrompt != null || it.hasFocus) promptFocused = it.hasFocus },
            ) {
                if (upNext != null) {
                    UpNextCard(
                        next = upNext.first,
                        countdown = upNext.second,
                        playFocus = upNextFocus,
                        onPlay = viewModel::playNext,
                        onDismiss = viewModel::dismissUpNext,
                    )
                } else if (skip != null) {
                    SkipButton(skip, progress.positionMs, onClick = viewModel::skipSegment, modifier = Modifier.focusRequester(skipFocus))
                }
            }
        }

        state.trackPanel?.let { PlayerTrackPanel(state, it, viewModel) }
        if (state.chaptersOpen) {
            ChapterSheet(
                chapters = state.chapters,
                positionMs = progress.positionMs,
                fallbackImage = state.details?.item?.backdropUrl,
                onPick = viewModel::playChapter,
                onDismiss = viewModel::closeChapters,
            )
        }

        if (state.loading && !state.failed) {
            Column(
                Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                SpinningDiamond(110)
                Text(stringResource(R.string.player_loading), style = GlacierText.body(21), color = GlacierColors.Mist)
            }
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
                Text(stringResource(R.string.player_error), style = GlacierText.display(30), color = GlacierColors.Ice)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    PillButton(stringResource(R.string.action_retry), onClick = viewModel::load, primary = true, modifier = Modifier.focusRequester(retryFocus))
                    PillButton(stringResource(R.string.action_back), onClick = viewModel::stop)
                }
            }
            LaunchedEffect(Unit) {
                withFrameNanos { }
                runCatching { retryFocus.requestFocus() }
            }
        }
    }
}

/** The bottom gradient keeps the OSD legible on bright pictures (design: 420 high). */
internal fun Modifier.osdScrim(top: Boolean): Modifier = fillMaxWidth()
    .height(if (top) 220.dp else 420.dp)
    .background(
        Brush.verticalGradient(
            if (top) listOf(Color(0xD9050910), Color.Transparent) else listOf(Color.Transparent, Color(0xEB050910)),
        ),
    )
