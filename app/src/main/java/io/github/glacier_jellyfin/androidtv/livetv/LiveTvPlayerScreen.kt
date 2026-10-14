package io.github.glacier_jellyfin.androidtv.livetv

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.ContentFrame
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.data.media.LiveChannel
import io.github.glacier_jellyfin.androidtv.core.data.media.LiveProgram
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierClickable
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierIcons
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.glacier_jellyfin.androidtv.core.designsystem.LocalAccent
import io.github.glacier_jellyfin.androidtv.core.designsystem.PillButton
import io.github.glacier_jellyfin.androidtv.core.designsystem.PillShape
import io.github.glacier_jellyfin.androidtv.core.designsystem.SpinningDiamond
import io.github.glacier_jellyfin.androidtv.core.designsystem.ignoreHeldOk
import io.github.glacier_jellyfin.androidtv.player.OsdGlass
import io.github.glacier_jellyfin.androidtv.player.OsdSecondary
import io.github.glacier_jellyfin.androidtv.player.formatTime
import io.github.glacier_jellyfin.androidtv.player.osdScrim
import io.github.glacier_jellyfin.androidtv.ui.CollectEvents
import io.github.glacier_jellyfin.androidtv.ui.UiEvent
import io.github.glacier_jellyfin.androidtv.ui.OverlayCard
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

/** The channel banner hides after this long without input. */
private const val BANNER_TIMEOUT_MS = 5_000L

/** Left/Right in the time bar seek once the keys rest this long. */
private const val SCRUB_COMMIT_MS = 900L

/** A Back key released this soon after the dispatcher acted belongs to the same press. */
private const val BACK_DEDUP_MS = 1_000L

/** Typed channel digits tune after this pause. */
private const val NUMBER_COMMIT_MS = 1_500L

/**
 * A Live TV channel, full screen. Up and down (or the channel keys) zap,
 * Left opens the channel list, Right (or the guide key) the channel's
 * programmes. Digits tune to a channel number, the "last channel" key
 * goes back to the one before.
 *
 * OK, Play/Pause, Menu, Info and the spool keys open the controls at the
 * bottom: Play/Pause and, when the server kept the channel since tuning
 * in, the time bar. There Left/Right wind back and forth, Down reaches the
 * buttons and Back closes it.
 */
@OptIn(UnstableApi::class)
@Composable
fun LiveTvPlayerScreen(
    onNavigate: (UiEvent.Navigate) -> Unit,
    onBack: () -> Unit,
    viewModel: LiveTvPlayerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.finished.collect { onBack() } }
    CollectEvents(viewModel.events, onNavigate)
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
    var programsOpen by remember { mutableStateOf(false) }
    /** The programme whose recording sheet is open. */
    var sheetProgram by remember { mutableStateOf<LiveProgram?>(null) }
    var interaction by remember { mutableIntStateOf(0) }
    var typed by remember { mutableStateOf("") }
    var unknownNumber by remember { mutableStateOf<String?>(null) }
    /** The time bar has the keys. */
    var seeking by remember { mutableStateOf(false) }
    /** Where Left/Right moved the knob to, until it seeks there. */
    var scrubMs by remember { mutableStateOf<Long?>(null) }
    /** Which part of the time bar has the keys: the bar or a button under it. */
    var control by remember { mutableStateOf(TimeControl.Bar) }
    /** Back went down on this screen; its release may act (see [back]). */
    var backDown by remember { mutableStateOf(false) }
    /** When the back dispatcher last acted, so the key's release does not act again. */
    var backHandledAt by remember { mutableLongStateOf(0L) }

    fun showBanner() {
        bannerVisible = true
        interaction++
    }

    /** Opens the controls; without anything to wind back, only Play/Pause is there. */
    fun openSeeking(at: TimeControl = TimeControl.Bar) {
        if (!seeking) control = if (state.timeshift == null) TimeControl.Play else at
        seeking = true
        showBanner()
    }

    fun closeSeeking() {
        seeking = false
        scrubMs = null
        control = TimeControl.Bar
    }

    /** Moves the knob; the buttons -10/+30 do it too, without taking the keys to the bar. */
    /** One step back: a list, then the time bar, then the player itself. */
    fun back() {
        when {
            listOpen -> listOpen = false
            programsOpen -> programsOpen = false
            seeking -> {
                closeSeeking()
                bannerVisible = state.paused
            }
            else -> viewModel.stop()
        }
    }

    // Newer Android hands Back to the dispatcher on its own, older only as a key: each
    // press must act once, so the key's release only acts when the dispatcher did not.
    BackHandler(enabled = sheetProgram == null) {
        backHandledAt = SystemClock.uptimeMillis()
        backDown = false
        back()
    }

    fun scrub(byMs: Long, fromButton: Boolean = false) {
        val shift = state.timeshift ?: return
        scrubMs = ((scrubMs ?: shift.positionMs) + byMs).coerceIn(0, shift.liveMs)
        if (!fromButton) control = TimeControl.Bar
    }

    /** The buttons under the bar, left to right; winding only when the stream allows it, "Back to live" only while behind. */
    fun buttons() = listOfNotNull(
        TimeControl.Back.takeIf { state.timeshift != null },
        TimeControl.Play,
        TimeControl.Forward.takeIf { state.timeshift != null },
        TimeControl.Live.takeIf { state.timeshift?.live == false },
    )

    fun moveControl(by: Int) {
        val buttons = buttons()
        val index = buttons.indexOf(control).takeIf { it >= 0 } ?: buttons.indexOf(TimeControl.Play)
        control = buttons[(index + by).coerceIn(0, buttons.lastIndex)]
    }

    fun commitScrub() {
        scrubMs?.let(viewModel::seekTo)
        scrubMs = null
    }

    fun commitNumber() {
        val number = typed.takeIf { it.isNotEmpty() } ?: return
        typed = ""
        if (!viewModel.tuneNumber(number)) unknownNumber = number
        showBanner()
    }

    LaunchedEffect(bannerVisible, interaction, state.loading, state.failed, listOpen, programsOpen, state.paused) {
        // Paused, the time bar stays: Play goes on from there.
        if (bannerVisible && !state.loading && !state.failed && !listOpen && !programsOpen && !(seeking && state.paused)) {
            delay(BANNER_TIMEOUT_MS)
            bannerVisible = false
            closeSeeking()
        }
    }
    LaunchedEffect(scrubMs) {
        if (scrubMs == null) return@LaunchedEffect
        delay(SCRUB_COMMIT_MS)
        commitScrub()
    }
    // Nothing to wind back (a new channel, or a stream that cannot): only Play/Pause stays.
    LaunchedEffect(state.timeshift == null) {
        if (state.timeshift == null) {
            scrubMs = null
            control = TimeControl.Play
        }
    }
    // Back at the edge, "Back to live" goes away: the keys move to Play/Pause.
    LaunchedEffect(state.timeshift?.live) {
        if (state.timeshift?.live == true && control == TimeControl.Live) control = TimeControl.Play
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
    LaunchedEffect(listOpen, programsOpen) {
        if (listOpen || programsOpen) return@LaunchedEffect
        withFrameNanos { }
        runCatching { rootFocus.requestFocus() }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            .onPreviewKeyEvent { event ->
                // The sheet handles its own keys, Back included.
                if (sheetProgram != null) return@onPreviewKeyEvent false
                // Compose would turn Back into "exit focus" inside the lists: it is taken here.
                if (event.key == Key.Back) {
                    if (event.type == KeyEventType.KeyDown) {
                        backDown = true
                    } else if (event.type == KeyEventType.KeyUp && backDown) {
                        backDown = false
                        if (SystemClock.uptimeMillis() - backHandledAt > BACK_DEDUP_MS) back()
                    }
                    return@onPreviewKeyEvent true
                }
                if (programsOpen) {
                    // Left (towards the picture) closes the programmes.
                    if (event.key == Key.DirectionLeft) {
                        if (event.type == KeyEventType.KeyUp) programsOpen = false
                        return@onPreviewKeyEvent true
                    }
                    return@onPreviewKeyEvent false
                }
                if (listOpen) {
                    // Right (towards the picture) closes it, as Left closes the programmes.
                    if (event.key == Key.DirectionRight) {
                        if (event.type == KeyEventType.KeyUp) listOpen = false
                        return@onPreviewKeyEvent true
                    }
                    return@onPreviewKeyEvent false
                }
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                interaction++
                if (seeking) {
                    val handled = when (event.key) {
                        Key.DirectionLeft -> {
                            if (control == TimeControl.Bar) scrub(-state.seekBackMs) else moveControl(-1)
                            true
                        }
                        Key.DirectionRight -> {
                            if (control == TimeControl.Bar) scrub(state.seekForwardMs) else moveControl(1)
                            true
                        }
                        Key.DirectionDown -> {
                            if (control == TimeControl.Bar) control = TimeControl.Play
                            true
                        }
                        Key.DirectionUp -> {
                            if (state.timeshift != null) control = TimeControl.Bar
                            true
                        }
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                            when {
                                control == TimeControl.Live -> {
                                    scrubMs = null
                                    control = TimeControl.Play
                                    viewModel.goLive()
                                }
                                control == TimeControl.Back -> scrub(-state.seekBackMs, fromButton = true)
                                control == TimeControl.Forward -> scrub(state.seekForwardMs, fromButton = true)
                                control == TimeControl.Bar && scrubMs != null -> commitScrub()
                                else -> viewModel.togglePlay()
                            }
                            true
                        }
                        else -> false
                    }
                    if (handled) return@onPreviewKeyEvent true
                }
                digit(event.key)?.let { digit ->
                    if (typed.length < MAX_DIGITS) typed += digit
                    return@onPreviewKeyEvent true
                }
                when (event.key) {
                    Key.DirectionUp, Key.ChannelUp, Key.PageUp -> {
                        closeSeeking()
                        viewModel.zap(1)
                        showBanner()
                        true
                    }
                    Key.DirectionDown, Key.ChannelDown, Key.PageDown -> {
                        closeSeeking()
                        viewModel.zap(-1)
                        showBanner()
                        true
                    }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        if (typed.isNotEmpty()) commitNumber() else openSeeking(at = TimeControl.Play)
                        true
                    }
                    Key.DirectionLeft -> {
                        listOpen = true
                        true
                    }
                    Key.DirectionRight, Key.Guide -> {
                        programsOpen = true
                        true
                    }
                    Key.Info, Key.Menu -> {
                        openSeeking()
                        true
                    }
                    Key.MediaRewind -> {
                        openSeeking()
                        scrub(-state.seekBackMs)
                        true
                    }
                    Key.MediaFastForward -> {
                        openSeeking()
                        scrub(state.seekForwardMs)
                        true
                    }
                    Key.LastChannel -> {
                        viewModel.lastChannel()
                        showBanner()
                        true
                    }
                    Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> {
                        val pause = when (event.key) {
                            Key.MediaPause -> true
                            Key.MediaPlay -> false
                            else -> !state.paused
                        }
                        if (pause != state.paused) viewModel.togglePlay()
                        openSeeking(at = TimeControl.Play)
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

        AnimatedVisibility(visible = (bannerVisible || state.loading || state.paused) && !state.failed && !listOpen && !programsOpen, enter = fadeIn(), exit = fadeOut()) {
            ChannelBanner(state, seeking = seeking, scrubMs = scrubMs, control = control)
        }

        if (typed.isNotEmpty() || unknownNumber != null) {
            NumberEntry(
                text = typed.ifEmpty { unknownNumber.orEmpty() },
                unknown = typed.isEmpty(),
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 64.dp, end = 80.dp),
            )
        }

        // Only the channel and a line on its picture: the keys go on as ever, so zapping away and back tries again.
        if (state.failed) {
            OverlayCard {
                state.channel?.takeIf { it.logoUrl != null }?.let { ChannelLogo(it, Modifier.width(200.dp).height(112.dp), textSize = 36) }
                Text(state.channel?.name ?: stringResource(R.string.nav_live_tv), style = GlacierText.body(21, FontWeight.SemiBold), color = GlacierColors.Mist)
                Text(stringResource(R.string.livetv_play_error), style = GlacierText.display(30), color = GlacierColors.Ice)
            }
        }

        AnimatedVisibility(
            visible = listOpen,
            enter = slideInHorizontally { -it } + fadeIn(),
            exit = slideOutHorizontally { -it } + fadeOut(),
            modifier = Modifier.fillMaxHeight(),
        ) {
            ChannelList(
                channels = state.zapChannels,
                favoritesOnly = state.zapChannels.size < state.channels.size,
                current = state.channel,
                nowMs = state.nowMs,
                onPick = { channel ->
                    viewModel.tune(channel)
                    listOpen = false
                    showBanner()
                },
            )
        }

        AnimatedVisibility(
            visible = programsOpen,
            enter = slideInHorizontally { it } + fadeIn(),
            exit = slideOutHorizontally { it } + fadeOut(),
            modifier = Modifier.fillMaxHeight().align(Alignment.TopEnd),
        ) {
            ProgramList(
                channel = state.channel,
                programs = state.programs.filter { it.endMs > state.nowMs },
                loading = !state.guideLoaded,
                nowMs = state.nowMs,
                sheetOpen = sheetProgram != null,
                onPick = { program ->
                    // What runs now is what plays: the list just closes.
                    if (program.airsAt(state.nowMs)) programsOpen = false else sheetProgram = program
                },
            )
        }

        val channel = state.channel
        val program = sheetProgram
        if (channel != null && program != null) {
            // The marks may have changed meanwhile: the sheet shows the programme as the list has it now.
            val current = state.programs.firstOrNull { it.id == program.id } ?: program
            ProgramSheet(channel, current, state.nowMs, actions = object : RecordActions by viewModel {
                override fun record(program: LiveProgram) {
                    sheetProgram = null
                    viewModel.record(program)
                }

                override fun recordSeries(program: LiveProgram) {
                    sheetProgram = null
                    viewModel.recordSeries(program)
                }

                override fun cancelTimer(timerId: String, title: String) {
                    sheetProgram = null
                    viewModel.cancelTimer(timerId, title)
                }

                override fun cancelSeriesTimer(seriesTimerId: String, title: String) {
                    sheetProgram = null
                    viewModel.cancelSeriesTimer(seriesTimerId, title)
                }
            }, onDismiss = { sheetProgram = null })
        }
    }
}

/**
 * The channel's programmes at the right edge, from what runs now on; the
 * focus starts there. OK on a later one offers to record it.
 */
@Composable
private fun ProgramList(
    channel: LiveChannel?,
    programs: List<LiveProgram>,
    loading: Boolean,
    nowMs: Long,
    sheetOpen: Boolean,
    onPick: (LiveProgram) -> Unit,
) {
    val firstFocus = remember { FocusRequester() }
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    var focusedId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(programs.isEmpty()) {
        withFrameNanos { }
        runCatching { firstFocus.requestFocus() }
    }
    // The sheet took the focus along: back on the programme it was about.
    LaunchedEffect(sheetOpen) {
        if (sheetOpen) return@LaunchedEffect
        withFrameNanos { }
        runCatching { (focusedId?.let { requesters[it] } ?: firstFocus).requestFocus() }
    }
    Box(
        Modifier
            .fillMaxHeight()
            .width(720.dp)
            .background(Brush.horizontalGradient(listOf(GlacierColors.Void.copy(alpha = 0.95f), GlacierColors.Void))),
    ) {
        Column(Modifier.fillMaxSize().padding(top = 56.dp)) {
            Text(
                stringResource(R.string.livetv_guide),
                style = GlacierText.display(34),
                color = GlacierColors.Ice,
                modifier = Modifier.padding(start = 48.dp),
            )
            channel?.let {
                Text(
                    listOfNotNull(it.number, it.name).joinToString("  "),
                    style = GlacierText.body(20),
                    color = GlacierColors.Mist,
                    modifier = Modifier.padding(start = 48.dp, top = 4.dp, bottom = 18.dp),
                )
            }
            if (programs.isEmpty()) {
                // Still on its way: a loader, not "no guide data". Either way it holds the focus.
                if (loading) {
                    Box(
                        Modifier.fillMaxSize().padding(bottom = 56.dp).focusRequester(firstFocus).focusable(),
                        contentAlignment = Alignment.Center,
                    ) { SpinningDiamond(56) }
                } else {
                    val holder = Modifier.padding(start = 48.dp).focusRequester(firstFocus).focusable()
                    Text(stringResource(R.string.livetv_no_guide), style = GlacierText.body(20), color = GlacierColors.Mist, modifier = holder)
                }
                return@Column
            }
            LazyColumn(
                contentPadding = PaddingValues(start = 40.dp, end = 40.dp, top = 8.dp, bottom = 60.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .ignoreHeldOk()
                    // Focus stays in the list, except for the sheet it opens.
                    .focusProperties { if (!sheetOpen) onExit = { cancelFocusChange() } }
                    .focusGroup(),
            ) {
                itemsIndexed(programs, key = { _, it -> it.id }) { index, program ->
                    val airing = program.airsAt(nowMs)
                    val shape = RoundedCornerShape(14.dp)
                    val requester = remember(program.id) { requesters.getOrPut(program.id) { FocusRequester() } }
                    GlacierClickable(
                        onClick = { onPick(program) },
                        shape = shape,
                        scaleOnFocus = false,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(requester)
                            .then(if (index == 0) Modifier.focusRequester(firstFocus) else Modifier)
                            .onFocusChanged { if (it.isFocused) focusedId = program.id },
                    ) { focused ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(shape)
                                .background(if (focused) GlacierColors.GlassFill2 else Color.Transparent)
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(18.dp),
                        ) {
                            Text(clockText(program.startMs), style = GlacierText.mono(19), color = if (airing) LocalAccent.current.main else GlacierColors.Mist)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    if (airing) LiveBadge()
                                    Text(
                                        program.title,
                                        style = GlacierText.body(20, FontWeight.SemiBold),
                                        color = GlacierColors.Ice,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false),
                                    )
                                    if (program.recorded) RecordDot(program, Modifier.size(16.dp))
                                }
                                val details = listOfNotNull(if (airing) null else dayText(program.startMs), program.episodeTitle)
                                if (details.isNotEmpty()) {
                                    Text(details.joinToString(" · "), style = GlacierText.body(17), color = GlacierColors.Mist, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                if (airing) ProgramProgress(program.progressAt(nowMs), Modifier.width(220.dp).padding(top = 4.dp), height = 3)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** What runs on the channel: logo, number and name, the programme with its progress, and what follows. */
@Composable
private fun ChannelBanner(state: LiveTvPlayerState, seeking: Boolean, scrubMs: Long?, control: TimeControl) {
    val channel = state.channel ?: return
    val now = state.now?.takeIf { it.airsAt(state.nowMs) }
    val shift = state.timeshift
    val behind = shift?.takeIf { !it.live }?.behindMs
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
                    // The one place that says how far behind live the picture is.
                    if (behind == null) LiveBadge() else BehindBadge(behind)
                    channel.number?.let { Text(it, style = GlacierText.mono(22), color = LocalAccent.current.main) }
                    Text(channel.name, style = GlacierText.body(22, FontWeight.SemiBold), color = GlacierColors.Ice, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (state.paused) Text(stringResource(R.string.livetv_paused), style = GlacierText.body(20), color = GlacierColors.Mist)
                }
                if (now == null && !state.guideLoaded) {
                    Box(Modifier.fillMaxWidth().height(56.dp), contentAlignment = Alignment.Center) { SpinningDiamond(48) }
                } else {
                    Text(
                        now?.title ?: stringResource(R.string.livetv_no_guide),
                        style = GlacierText.display(40),
                        color = GlacierColors.Ice,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (now != null) {
                    Text(
                        listOfNotNull(now.episodeTitle, minutesLeft(now, state.nowMs)).joinToString(" · "),
                        style = GlacierText.body(20),
                        color = GlacierColors.Mist,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (now != null || shift != null) {
                    ProgramTimeline(
                        program = now,
                        shift = shift,
                        scrubMs = scrubMs,
                        // The knob only shows once the picture can move away from now.
                        knob = shift != null && (seeking || behind != null || state.paused || scrubMs != null),
                        focused = seeking && control == TimeControl.Bar,
                        nowMs = state.nowMs,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                if (seeking) {
                    Row(
                        Modifier.padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        if (shift != null) TimeButton(GlacierIcons.Replay, stringResource(R.string.player_rewind), selected = control == TimeControl.Back)
                        TimeButton(
                            icon = if (state.paused) GlacierIcons.Play else GlacierIcons.Pause,
                            description = stringResource(if (state.paused) R.string.player_play else R.string.player_pause),
                            selected = control == TimeControl.Play,
                            big = true,
                        )
                        if (shift != null) TimeButton(GlacierIcons.Forward, stringResource(R.string.player_forward), selected = control == TimeControl.Forward)
                        if (shift?.live == false) {
                            TimeLabelButton(GlacierIcons.SkipForward, stringResource(R.string.livetv_go_live), selected = control == TimeControl.Live)
                        }
                    }
                } else {
                    state.next?.let { next ->
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(top = 4.dp)) {
                            Text(stringResource(R.string.livetv_next), style = GlacierText.label(16), color = GlacierColors.Mist)
                            Text(clockText(next.startMs), style = GlacierText.mono(19), color = GlacierColors.Mist)
                            Text(next.title, style = GlacierText.body(19), color = GlacierColors.Ice, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            Text(clockText(state.nowMs), style = GlacierText.display(34), color = GlacierColors.Ice)
        }
    }
}

/** "2 min behind live", in place of the red LIVE badge. */
@Composable
private fun BehindBadge(behindMs: Long) {
    val seconds = (behindMs / 1000).toInt()
    val text = if (seconds < 60) {
        stringResource(R.string.livetv_behind_seconds, seconds)
    } else {
        stringResource(R.string.livetv_behind_minutes, seconds / 60)
    }
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(GlacierColors.GlassFill2)
            .border(1.dp, GlacierColors.GlassBorder2, RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 3.dp),
    ) {
        Text(text, style = GlacierText.label(15), color = GlacierColors.Ice)
    }
}

/**
 * One bar for the programme and what the server kept of it, a time-of-day
 * axis from the programme's start to its end (wider when the stream reaches
 * further back): dim accent for what ran before tuning in, full accent up to
 * the picture, light glass for what was kept ahead of it, a tick at now.
 * Sized like the video and music players' seek bars. While scrubbing, the
 * time the picture under the knob went out stands above it.
 */
@Composable
private fun ProgramTimeline(
    program: LiveProgram?,
    shift: Timeshift?,
    scrubMs: Long?,
    knob: Boolean,
    focused: Boolean,
    nowMs: Long,
    modifier: Modifier = Modifier,
) {
    val accent = LocalAccent.current.main
    // Wall clock times: the first picture kept, the one shown, the one under the knob.
    val keptFrom = shift?.let { nowMs - it.liveMs }
    val playingAt = shift?.let { nowMs - it.behindMs } ?: nowMs
    val knobAt = shift?.let { nowMs - (it.liveMs - (scrubMs ?: it.positionMs)) } ?: nowMs
    val from = listOfNotNull(program?.startMs, keptFrom).minOrNull() ?: nowMs
    val to = maxOf(program?.endMs ?: nowMs, nowMs)
    val span = (to - from).coerceAtLeast(1)
    fun at(timeMs: Long) = ((timeMs - from).toFloat() / span).coerceIn(0f, 1f)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(38.dp), contentAlignment = Alignment.CenterStart) {
            val width = maxWidth
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(if (focused) 12.dp else 7.dp)
                    .clip(PillShape)
                    .background(OsdGlass),
            ) {
                fun Modifier.stretch(start: Long, end: Long) = offset(x = width * at(start)).width(width * (at(end) - at(start))).fillMaxHeight()
                val kept = keptFrom ?: nowMs
                if (kept > from) Box(Modifier.stretch(from, kept).background(accent.copy(alpha = 0.4f)))
                Box(Modifier.stretch(kept, playingAt).background(accent))
                if (nowMs > playingAt) Box(Modifier.stretch(playingAt, nowMs).background(GlacierColors.GlassBorder2))
            }
            // Now, when the picture is not there: where "Back to live" goes.
            if (knob && nowMs > playingAt && to > nowMs) {
                Box(
                    Modifier
                        .offset(x = width * at(nowMs) - 1.dp)
                        .size(2.dp, if (focused) 22.dp else 16.dp)
                        .background(GlacierColors.Ice),
                )
            }
            if (knob) {
                val size = if (focused) 26.dp else 18.dp
                // Focused, a glow ring of 8 around the knob shows where the keys go (design).
                val ring = if (focused) 8.dp else 0.dp
                Box(
                    Modifier
                        .offset(x = width * at(knobAt) - size / 2 - ring)
                        .size(size + ring * 2)
                        .background(if (focused) accent.copy(alpha = 0.3f) else Color.Transparent, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.size(size).shadow(if (focused) 0.dp else 8.dp, CircleShape).clip(CircleShape).background(accent))
                }
            }
            if (scrubMs != null) {
                // Above the bar without taking room, so the banner does not jump.
                val half = 70.dp
                Text(
                    secondsClockText(knobAt),
                    style = GlacierText.mono(24),
                    color = GlacierColors.Ice,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .width(half * 2)
                        .offset(x = (width * at(knobAt)).coerceIn(half, width - half) - half, y = (-40).dp),
                )
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Text(clockText(from), style = GlacierText.mono(20), color = OsdSecondary)
            Spacer(Modifier.weight(1f))
            Text(clockText(to), style = GlacierText.mono(20), color = OsdSecondary)
        }
    }
}

/** Round button under the time bar, drawn like the video player's (62, Play 78); the keys pick it, not the focus. */
@Composable
private fun TimeButton(icon: ImageVector, description: String, selected: Boolean, big: Boolean = false) {
    val accent = LocalAccent.current.main
    Box(
        Modifier
            .size(if (big) 78.dp else 62.dp)
            .clip(CircleShape)
            .background(if (selected) accent else OsdGlass)
            .border(2.dp, if (selected) accent else GlacierColors.GlassBorder2, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = if (selected) GlacierColors.Void else GlacierColors.Ice, modifier = Modifier.size(if (big) 30.dp else 24.dp))
    }
}

/** Pill with icon and text, drawn like the video player's label buttons. */
@Composable
private fun TimeLabelButton(icon: ImageVector, label: String, selected: Boolean) {
    val accent = LocalAccent.current.main
    Row(
        Modifier
            .height(62.dp)
            .clip(PillShape)
            .background(if (selected) accent else OsdGlass)
            .border(2.dp, if (selected) accent else GlacierColors.GlassBorder2, PillShape)
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val color = if (selected) GlacierColors.Void else GlacierColors.Ice
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(23.dp))
        Text(label, style = GlacierText.body(19, FontWeight.SemiBold), color = color)
    }
}

private val SecondsClock: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

/** 18:58:40: a step of a few seconds still shows. */
private fun secondsClockText(timeMs: Long): String = Instant.ofEpochMilli(timeMs).atZone(ZoneId.systemDefault()).format(SecondsClock)

/** Where the keys are while the time bar is open. */
private enum class TimeControl { Bar, Back, Play, Forward, Live }


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
    favoritesOnly: Boolean,
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
                stringResource(if (favoritesOnly) R.string.livetv_favorites else R.string.livetv_channels),
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
