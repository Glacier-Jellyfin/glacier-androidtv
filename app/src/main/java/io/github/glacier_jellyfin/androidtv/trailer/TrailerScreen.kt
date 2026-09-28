package io.github.glacier_jellyfin.androidtv.trailer

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.ContentFrame
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.data.media.ItemKind
import io.github.glacier_jellyfin.androidtv.core.data.media.Trailer
import io.github.glacier_jellyfin.androidtv.core.data.media.YouTubeTrailer
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierCard
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierIcons
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierShapes
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.glacier_jellyfin.androidtv.core.designsystem.LocalAccent
import io.github.glacier_jellyfin.androidtv.core.designsystem.PillButton
import io.github.glacier_jellyfin.androidtv.core.designsystem.PillShape
import io.github.glacier_jellyfin.androidtv.core.designsystem.SpinningDiamond
import io.github.glacier_jellyfin.androidtv.core.designsystem.focusFrame
import io.github.glacier_jellyfin.androidtv.player.ControlButton
import io.github.glacier_jellyfin.androidtv.player.LabelButton
import io.github.glacier_jellyfin.androidtv.player.formatTime
import io.github.glacier_jellyfin.androidtv.ui.Artwork
import io.github.glacier_jellyfin.androidtv.ui.CollectEvents
import io.github.glacier_jellyfin.androidtv.ui.FactBadge
import io.github.glacier_jellyfin.androidtv.ui.KenBurns
import io.github.glacier_jellyfin.androidtv.ui.LocalToaster
import io.github.glacier_jellyfin.androidtv.ui.UiEvent
import io.github.glacier_jellyfin.androidtv.ui.ageRatingText
import io.github.glacier_jellyfin.androidtv.ui.runtimeText
import kotlinx.coroutines.delay
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** The OSD hides after this long without input while playing (design: 5 s). */
private const val OSD_TIMEOUT_MS = 5_000L

/** The end screen takes focus after this pause. */
private const val END_FOCUS_DELAY_MS = 600L

/** The design's trailer skip buttons. */
private const val SEEK_BACK_MS = 10_000L
private const val SEEK_FORWARD_MS = 15_000L

/**
 * Where a YouTube video sits while the OSD is shown, on the 1920×1080 grid:
 * YouTube forbids drawing over its player, so the video moves into the free
 * space top right and the OSD keeps clear of it.
 */
private const val FRAME_WIDTH = 800f
private const val FRAME_RIGHT = 80f
private const val FRAME_TOP = 140f

@OptIn(UnstableApi::class)
@Composable
fun TrailerScreen(
    onNavigate: (UiEvent.Navigate) -> Unit,
    onBack: () -> Unit,
    viewModel: TrailerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) { viewModel.finished.collect { onBack() } }
    CollectEvents(viewModel.events, onNavigate = onNavigate)
    BackHandler { viewModel.close() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.pause() }
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    val rootFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }
    val endFocus = remember { FocusRequester() }
    val errorFocus = remember { FocusRequester() }
    val current = state.current
    val youTube = current as? YouTubeTrailer
    val stopped = state.ended || state.failed || state.empty
    var osdVisible by remember { mutableStateOf(true) }
    var interaction by remember { mutableIntStateOf(0) }
    val osdShown = osdVisible && !stopped && current != null

    fun showOsd() {
        osdVisible = true
        interaction++
    }

    // Every new trailer and every pause brings the OSD back.
    LaunchedEffect(state.attempt) { showOsd() }
    LaunchedEffect(state.playWhenReady) { if (!state.playWhenReady) showOsd() }
    LaunchedEffect(osdVisible, interaction, state.playWhenReady, state.loading) {
        if (osdVisible && state.playWhenReady && !state.loading) {
            delay(OSD_TIMEOUT_MS)
            osdVisible = false
        }
    }
    // Keyed here rather than inside the OSD: an OSD shown again while still fading out keeps its composition.
    LaunchedEffect(osdShown, state.attempt) {
        if (stopped) return@LaunchedEffect
        withFrameNanos { }
        runCatching { (if (osdShown) playFocus else rootFocus).requestFocus() }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(GlacierColors.Void)
            .focusRequester(rootFocus)
            .onPreviewKeyEvent { event ->
                // One Back closes, whatever has focus; on key up (see PlayerScreen).
                if (event.key == Key.Back) {
                    if (event.type == KeyEventType.KeyUp) viewModel.close()
                    return@onPreviewKeyEvent true
                }
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                interaction++
                when (event.key) {
                    Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> {
                        viewModel.togglePlay()
                        true
                    }
                    Key.MediaRewind -> { viewModel.seekBy(-SEEK_BACK_MS); true }
                    Key.MediaFastForward -> { viewModel.seekBy(SEEK_FORWARD_MS); true }
                    // With the OSD hidden, a key only wakes it (design).
                    else -> if (!osdVisible && !stopped) {
                        showOsd()
                        true
                    } else {
                        false
                    }
                }
            }
            .focusable(),
    ) {
        val item = state.item
        val art = item?.backdropUrl ?: item?.thumbUrl
        KenBurns { Artwork(art, Modifier.fillMaxSize()) }
        // Around the smaller YouTube frame the artwork stays visible: dimmed, so the OSD reads and the video stands out.
        if (youTube != null && !stopped) Box(Modifier.fillMaxSize().background(Color(0xA605090F)))

        state.player?.let { player ->
            if (!state.ended) ContentFrame(player = player, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        }

        // Hidden OSD: a hairline of progress at the bottom edge (not over a YouTube player).
        if (!osdShown && !stopped && youTube == null && !state.loading) {
            ProgressLine(progress, Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp))
        }

        AnimatedVisibility(visible = osdShown, enter = fadeIn(), exit = fadeOut()) {
            TrailerOsd(
                state = state,
                progress = progress,
                compactTitle = youTube != null,
                playFocus = playFocus,
                onTogglePlay = viewModel::togglePlay,
                onSeekBy = viewModel::seekBy,
                onPlayTitle = viewModel::playTitle,
                onFavorite = viewModel::toggleFavorite,
                onPick = { index -> if (index == state.index) viewModel.replay() else viewModel.play(index) },
            )
        }

        // Drawn above the OSD, so nothing ever covers the YouTube player, not even while it moves.
        if (youTube != null && !stopped) {
            val shrink by animateFloatAsState(if (osdShown && !state.loading) 1f else 0f, tween(350), label = "frame")
            key(state.attempt) {
                YouTubePlayer(
                    videoId = youTube.videoId,
                    commands = viewModel.youTubeCommands,
                    listener = viewModel,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            // Invisible until it plays: the loader shows meanwhile.
                            alpha = if (state.loading) 0f else 1f
                            transformOrigin = TransformOrigin(0f, 0f)
                            val scale = 1f - (1f - FRAME_WIDTH / 1920f) * shrink
                            scaleX = scale
                            scaleY = scale
                            translationX = size.width * (1920f - FRAME_RIGHT - FRAME_WIDTH) / 1920f * shrink
                            translationY = size.height * FRAME_TOP / 1080f * shrink
                        },
                )
            }
        }

        if (state.ended) {
            EndScreen(
                state = state,
                firstFocus = endFocus,
                onPlayTitle = viewModel::playTitle,
                onNext = viewModel::playNext,
                onAgain = viewModel::replay,
                onDetails = viewModel::close,
            )
            LaunchedEffect(state.attempt) {
                // Presses meant for the skip button that ended the trailer must not start the title.
                delay(END_FOCUS_DELAY_MS)
                runCatching { endFocus.requestFocus() }
            }
        }

        if (state.loading && !state.failed && current != null) {
            LoadingOverlay(trailerName(state, state.index), item?.title.orEmpty())
        }

        if (state.failed || state.empty) {
            ErrorView(
                state = state,
                focus = errorFocus,
                onNext = viewModel::playNext,
                onRetry = viewModel::replay,
                onBack = viewModel::close,
            )
            LaunchedEffect(state.failed, state.empty) {
                withFrameNanos { }
                runCatching { errorFocus.requestFocus() }
            }
        }
    }
}

@Composable
private fun TrailerOsd(
    state: TrailerUiState,
    progress: TrailerProgress,
    /** Leaves room for the YouTube player top right. */
    compactTitle: Boolean,
    playFocus: FocusRequester,
    onTogglePlay: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onPlayTitle: () -> Unit,
    onFavorite: () -> Unit,
    onPick: (Int) -> Unit,
) {
    val item = state.item
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(260.dp)
                .background(Brush.verticalGradient(listOf(Color(0xD905090F), Color.Transparent))),
        )
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(560.dp)
                .background(Brush.verticalGradient(0f to Color.Transparent, 0.7f to Color(0xF205090F))),
        )

        Column(
            Modifier
                .align(Alignment.TopStart)
                .padding(start = 80.dp, top = 60.dp)
                .then(if (compactTitle) Modifier.widthIn(max = 900.dp) else Modifier),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                TrailerChip()
                Text(
                    stringResource(R.string.trailer_index, state.index + 1, state.trailers.size),
                    style = GlacierText.body(18),
                    color = GlacierColors.Mist,
                )
            }
            Text(item?.title.orEmpty(), style = GlacierText.display(46), color = GlacierColors.Ice, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(trailerName(state, state.index), trailerSource(state.current), state.durationOf(state.index)?.let(::formatTime))
                    .joinToString(" · "),
                style = GlacierText.body(20),
                color = GlacierColors.Mist,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Row(
            Modifier
                .align(Alignment.TopEnd)
                .padding(end = 80.dp, top = 64.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ageRatingText(item?.officialRating)?.let { rating ->
                val shape = RoundedCornerShape(GlacierShapes.RadiusSm)
                Box(
                    Modifier
                        .height(44.dp)
                        .clip(shape)
                        .background(GlacierColors.GlassFill)
                        .border(1.dp, GlacierColors.GlassBorder2, shape)
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(rating, style = GlacierText.body(17), color = GlacierColors.Ice)
                }
            }
            Text(LocalTime.now().format(ClockFormat), style = GlacierText.mono(20), color = GlacierColors.Mist)
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 80.dp, end = 80.dp, bottom = 60.dp),
            verticalArrangement = Arrangement.spacedBy(34.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ProgressBar(progress)
                Row(Modifier.fillMaxWidth()) {
                    Text(formatTime(progress.positionMs), style = GlacierText.mono(19), color = GlacierColors.Ice)
                    Spacer(Modifier.weight(1f))
                    if (progress.durationMs > 0) {
                        Text("−" + formatTime(progress.durationMs - progress.positionMs), style = GlacierText.mono(19), color = GlacierColors.Mist)
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                ControlButton(GlacierIcons.Replay, stringResource(R.string.player_rewind), onClick = { onSeekBy(-SEEK_BACK_MS) })
                ControlButton(
                    if (state.playWhenReady) GlacierIcons.Pause else GlacierIcons.Play,
                    stringResource(if (state.playWhenReady) R.string.player_pause else R.string.player_play),
                    onClick = onTogglePlay,
                    big = true,
                    modifier = Modifier.focusRequester(playFocus),
                )
                ControlButton(GlacierIcons.Forward, stringResource(R.string.trailer_forward), onClick = { onSeekBy(SEEK_FORWARD_MS) })
                state.titleAction?.let { action ->
                    LabelButton(
                        GlacierIcons.Play,
                        stringResource(action.label()),
                        onClick = onPlayTitle,
                        primary = true,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
                val favorite = item?.isFavorite == true
                LabelButton(
                    if (favorite) GlacierIcons.HeartFilled else GlacierIcons.Heart,
                    stringResource(if (favorite) R.string.favorite_tag else R.string.trailer_add_favorite),
                    onClick = onFavorite,
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    stringResource(R.string.trailer_strip).uppercase(),
                    style = GlacierText.body(17).copy(letterSpacing = 0.06.em),
                    color = GlacierColors.Mist,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                    state.trailers.forEachIndexed { index, trailer ->
                        TrailerThumb(
                            trailer = trailer,
                            name = trailerName(state, index),
                            fallbackImage = item?.backdropUrl ?: item?.thumbUrl,
                            durationMs = state.durationOf(index),
                            current = index == state.index,
                            progress = if (index == state.index) fraction(progress) else 0f,
                            onClick = { onPick(index) },
                        )
                    }
                }
            }
        }
    }
}

/** "Trailer" with the film icon, accent-tinted (design). */
@Composable
private fun TrailerChip() {
    val accent = LocalAccent.current.main
    Row(
        Modifier
            .height(36.dp)
            .clip(PillShape)
            .background(accent.copy(alpha = 0.16f))
            .border(1.dp, accent.copy(alpha = 0.5f), PillShape)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(GlacierIcons.Film, contentDescription = null, tint = accent, modifier = Modifier.size(16.dp))
        Text(
            stringResource(R.string.detail_trailer).uppercase(),
            style = GlacierText.body(15, FontWeight.Bold).copy(letterSpacing = 0.09.em),
            color = accent,
        )
    }
}

@Composable
private fun ProgressBar(progress: TrailerProgress) {
    val accent = LocalAccent.current.main
    val played = fraction(progress)
    Box(Modifier.fillMaxWidth().height(16.dp), contentAlignment = Alignment.CenterStart) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(PillShape)
                .background(GlacierColors.GlassFill2),
        ) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(played).clip(PillShape).background(accent))
        }
        if (progress.durationMs > 0) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                Box(
                    Modifier
                        .offset(x = maxWidth * played - 8.dp)
                        .size(16.dp)
                        .shadow(10.dp, CircleShape)
                        .clip(CircleShape)
                        .background(accent),
                )
            }
        }
    }
}

@Composable
private fun ProgressLine(progress: TrailerProgress, modifier: Modifier) {
    Box(modifier.background(Color(0x8005090F))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction(progress)).background(LocalAccent.current.main))
    }
}

/** A trailer in the strip (design: 268×151), with "Playing", its length and progress. */
@Composable
private fun TrailerThumb(
    trailer: Trailer,
    name: String,
    fallbackImage: String?,
    durationMs: Long?,
    current: Boolean,
    progress: Float,
    onClick: () -> Unit,
) {
    val accent = LocalAccent.current.main
    val shape = RoundedCornerShape(GlacierShapes.RadiusMd)
    GlacierCard(onClick = onClick, modifier = Modifier.width(268.dp)) { focused ->
        Box(
            Modifier
                .size(268.dp, 151.dp)
                .focusFrame(focused, shape)
                .clip(shape),
        ) {
            Artwork(trailer.imageUrl ?: fallbackImage, Modifier.fillMaxSize())
            if (current) {
                Row(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 10.dp, top = 10.dp)
                        .height(28.dp)
                        .clip(PillShape)
                        .background(accent)
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(GlacierIcons.Play, contentDescription = null, tint = GlacierColors.Void, modifier = Modifier.size(11.dp))
                    Text(stringResource(R.string.trailer_now), style = GlacierText.body(14, FontWeight.Bold), color = GlacierColors.Void)
                }
            }
            durationMs?.let {
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 10.dp, bottom = 12.dp)
                        .height(26.dp)
                        .clip(PillShape)
                        .background(Color(0xB805090F))
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(formatTime(it), style = GlacierText.mono(14), color = GlacierColors.Ice)
                }
            }
            if (current) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(Color(0x9905090F)),
                ) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(progress).background(accent))
                }
            }
        }
        Text(
            name,
            style = GlacierText.body(18, FontWeight.SemiBold),
            color = if (focused) accent else GlacierColors.Ice,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

/** "Trailer ended": the title with its facts, what to do next and the countdown to the next trailer (design). */
@Composable
private fun EndScreen(
    state: TrailerUiState,
    firstFocus: FocusRequester,
    onPlayTitle: () -> Unit,
    onNext: () -> Unit,
    onAgain: () -> Unit,
    onDetails: () -> Unit,
) {
    val item = state.item ?: return
    val accent = LocalAccent.current.main
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0f to Color(0xF505090F),
                        0.4f to Color(0xE005090F),
                        0.78f to Color(0x5905090F),
                        1f to Color(0x3305090F),
                    ),
                ),
        )
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(320.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC05090F)))),
        )
        Column(
            Modifier
                .align(Alignment.CenterStart)
                .padding(start = 120.dp)
                .width(860.dp),
            verticalArrangement = Arrangement.spacedBy(26.dp),
        ) {
            Text(
                stringResource(R.string.trailer_ended, trailerName(state, state.index)).uppercase(),
                style = GlacierText.body(17, FontWeight.Bold).copy(letterSpacing = 0.09.em),
                color = accent,
            )
            Text(item.title, style = GlacierText.display(84), color = GlacierColors.Ice, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                ageRatingText(item.officialRating)?.let { FactBadge(it, GlacierColors.GlassBorder2, 17, GlacierColors.Ice) }
                val length = when (item.kind) {
                    ItemKind.Series -> state.details?.seasonCount?.let { pluralStringResource(R.plurals.count_seasons, it, it) }
                    else -> item.runtimeMinutes?.let { runtimeText(it) }
                }
                val facts = listOfNotNull(item.year?.toString(), item.genres.firstOrNull(), length)
                if (facts.isNotEmpty()) Text(facts.joinToString(" · "), style = GlacierText.body(21), color = GlacierColors.Mist)
            }
            item.overview?.let {
                Text(
                    it,
                    style = GlacierText.body(23).copy(lineHeight = 1.5.em),
                    color = GlacierColors.Mist,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 760.dp),
                )
            }
            // Four buttons are wider than the text column; they may run past it.
            Row(
                Modifier
                    .padding(top = 14.dp)
                    .wrapContentWidth(Alignment.Start, unbounded = true),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                val action = state.titleAction
                val first = Modifier.focusRequester(firstFocus)
                if (action != null) {
                    PillButton(stringResource(action.label()), onClick = onPlayTitle, primary = true, height = 66, icon = GlacierIcons.Play, modifier = first)
                }
                if (state.next != null) {
                    PillButton(
                        stringResource(R.string.trailer_next),
                        onClick = onNext,
                        height = 66,
                        icon = GlacierIcons.SkipForward,
                        modifier = if (action == null) first else Modifier,
                    )
                }
                PillButton(
                    stringResource(R.string.trailer_again),
                    onClick = onAgain,
                    height = 66,
                    icon = GlacierIcons.Replay,
                    modifier = if (action == null && state.next == null) first else Modifier,
                )
                PillButton(stringResource(R.string.trailer_to_details), onClick = onDetails, height = 66)
            }
        }

        val next = state.next
        val countdown = state.countdown
        if (next != null && countdown != null) {
            NextCard(
                name = trailerName(state, state.index + 1),
                sub = listOfNotNull(trailerSource(next), state.durationOf(state.index + 1)?.let(::formatTime)).joinToString(" · "),
                image = next.imageUrl ?: item.backdropUrl,
                seconds = countdown,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 80.dp, bottom = 80.dp),
            )
        }
    }
}

/** "Up next" with a ring counting down the seconds (design: 460 wide, bottom right). */
@Composable
private fun NextCard(name: String, sub: String, image: String?, seconds: Int, modifier: Modifier) {
    val accent = LocalAccent.current.main
    val shape = RoundedCornerShape(GlacierShapes.RadiusLg)
    // The ring empties over the countdown, ahead by a second so it reaches zero as the next trailer starts.
    val fill by animateFloatAsState((seconds - 1).coerceAtLeast(0) / 7f, tween(1_000), label = "countdown")
    Column(
        modifier
            .width(460.dp)
            .clip(shape)
            .background(GlacierColors.GlassFill2)
            .border(1.dp, GlacierColors.GlassBorder2, shape)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.trailer_up_next).uppercase(),
                style = GlacierText.body(16, FontWeight.Bold).copy(letterSpacing = 0.09.em),
                color = accent,
            )
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val stroke = 4.dp.toPx()
                    val inset = stroke / 2 + 1.5.dp.toPx()
                    val arc = Size(size.width - 2 * inset, size.height - 2 * inset)
                    val topLeft = Offset(inset, inset)
                    drawArc(GlacierColors.Ice.copy(alpha = 0.2f), 0f, 360f, useCenter = false, topLeft = topLeft, size = arc, style = Stroke(stroke))
                    drawArc(accent, -90f, 360f * fill, useCenter = false, topLeft = topLeft, size = arc, style = Stroke(stroke, cap = StrokeCap.Round))
                }
                Text(seconds.toString(), style = GlacierText.body(18, FontWeight.Bold), color = GlacierColors.Ice)
            }
        }
        Artwork(image, Modifier.size(412.dp, 232.dp).clip(RoundedCornerShape(GlacierShapes.RadiusMd)))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(name, style = GlacierText.body(22, FontWeight.SemiBold), color = GlacierColors.Ice, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub.isNotEmpty()) Text(sub, style = GlacierText.body(17), color = GlacierColors.Mist)
        }
    }
}

@Composable
private fun LoadingOverlay(name: String, title: String) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xB805090F)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(34.dp, Alignment.CenterVertically),
    ) {
        SpinningDiamond(110)
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(name, style = GlacierText.body(21), color = GlacierColors.Ice)
            Text(title, style = GlacierText.body(18), color = GlacierColors.Mist)
        }
    }
}

/** A trailer that would not play, or a title without playable trailers. */
@Composable
private fun ErrorView(
    state: TrailerUiState,
    focus: FocusRequester,
    onNext: () -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val noApp = stringResource(R.string.trailer_no_youtube_app)
    val youTube = state.current as? YouTubeTrailer
    Box(Modifier.fillMaxSize().background(Color(0xB805090F)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.padding(horizontal = 80.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            Text(
                stringResource(if (state.empty) R.string.trailer_none else R.string.trailer_error),
                style = GlacierText.display(30),
                color = GlacierColors.Ice,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                val buttons = buildList<Pair<String, () -> Unit>> {
                    if (!state.empty && state.next != null) add(stringResource(R.string.trailer_next) to onNext)
                    // Embedding can be switched off per video; the YouTube app still plays those.
                    if (youTube != null) {
                        add(
                            stringResource(R.string.trailer_open_youtube) to {
                                val intent = Intent(Intent.ACTION_VIEW, "https://www.youtube.com/watch?v=${youTube.videoId}".toUri())
                                try {
                                    context.startActivity(intent)
                                } catch (_: ActivityNotFoundException) {
                                    toaster.show(noApp)
                                }
                            },
                        )
                    } else if (!state.empty) {
                        add(stringResource(R.string.action_retry) to onRetry)
                    }
                    add(stringResource(R.string.action_back) to onBack)
                }
                buttons.forEachIndexed { index, (label, onClick) ->
                    PillButton(
                        label,
                        onClick = onClick,
                        primary = index == 0,
                        modifier = if (index == 0) Modifier.focusRequester(focus) else Modifier,
                    )
                }
            }
        }
    }
}

@Composable
private fun trailerName(state: TrailerUiState, index: Int): String =
    state.trailers.getOrNull(index)?.name ?: stringResource(R.string.trailer_fallback_name, index + 1)

/** YouTube trailers say where they come from; server files need no label. */
private fun trailerSource(trailer: Trailer?): String? = if (trailer is YouTubeTrailer) "YouTube" else null

private fun TitleAction.label(): Int = when (this) {
    TitleAction.PlayMovie -> R.string.trailer_play_movie
    TitleAction.ResumeMovie -> R.string.trailer_resume_movie
    TitleAction.WatchShow -> R.string.trailer_watch_show
    TitleAction.PlayEpisode -> R.string.episode_play
}

private fun fraction(progress: TrailerProgress): Float =
    if (progress.durationMs > 0) (progress.positionMs.toFloat() / progress.durationMs).coerceIn(0f, 1f) else 0f

private val ClockFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
