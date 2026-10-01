package io.github.glacier_jellyfin.androidtv.music

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode as AnimationRepeat
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.data.media.ItemKind
import io.github.glacier_jellyfin.androidtv.core.data.media.LyricLine
import io.github.glacier_jellyfin.androidtv.core.data.media.MusicTrack
import io.github.glacier_jellyfin.androidtv.core.data.playback.PlaybackMethod
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierIcons
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierShapes
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.glacier_jellyfin.androidtv.core.designsystem.LocalAccent
import io.github.glacier_jellyfin.androidtv.core.designsystem.PillButton
import io.github.glacier_jellyfin.androidtv.core.designsystem.PillShape
import io.github.glacier_jellyfin.androidtv.core.designsystem.SpinningDiamond
import io.github.glacier_jellyfin.androidtv.core.designsystem.focusFrame
import io.github.glacier_jellyfin.androidtv.core.designsystem.focusScale
import io.github.glacier_jellyfin.androidtv.player.formatTime
import io.github.glacier_jellyfin.androidtv.ui.Artwork
import io.github.glacier_jellyfin.androidtv.ui.CollectEvents
import io.github.glacier_jellyfin.androidtv.ui.audioFormatText
import io.github.glacier_jellyfin.androidtv.ui.runtimeText
import kotlinx.coroutines.delay
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** Where the current lyric line sits in the lyrics box (design `lyrFollow`: 40 % from the top). */
private const val LYRIC_ANCHOR = 0.4f

@Composable
fun MusicPlayerScreen(
    onBack: () -> Unit,
    viewModel: MusicPlayerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) { viewModel.finished.collect { onBack() } }
    CollectEvents(viewModel.events, onNavigate = {})
    BackHandler { viewModel.close() }
    // Awake while music plays here; paused, the screensaver may come.
    val view = LocalView.current
    DisposableEffect(view, state.playing) {
        view.keepScreenOn = state.playing
        onDispose { view.keepScreenOn = false }
    }

    val playFocus = remember { FocusRequester() }
    LaunchedEffect(state.loading, state.failed) {
        if (state.loading || state.failed) return@LaunchedEffect
        withFrameNanos { }
        runCatching { playFocus.requestFocus() }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(GlacierColors.Void)
            .onPreviewKeyEvent { event ->
                // Back closes on the first press, whatever has focus (see PlayerScreen).
                if (event.key == Key.Back) {
                    if (event.type == KeyEventType.KeyUp) viewModel.close()
                    return@onPreviewKeyEvent true
                }
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> viewModel.togglePlay()
                    Key.MediaNext -> viewModel.next()
                    Key.MediaPrevious -> viewModel.previous()
                    Key.MediaRewind -> viewModel.seekBy(-state.seekBackMs)
                    Key.MediaFastForward -> viewModel.seekBy(state.seekForwardMs)
                    else -> return@onPreviewKeyEvent false
                }
                true
            },
    ) {
        val current = state.current
        Background(current)
        if (current != null) {
            TopBar(state, current, Modifier.padding(start = 96.dp, end = 96.dp, top = 60.dp))
            Box(Modifier.padding(start = 96.dp).width(1064.dp)) {
                AnimatedContent(state.lyricsOn, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "lyrics") { lyricsOn ->
                    if (lyricsOn) {
                        LyricsView(state, current, progress.positionMs, onSeek = viewModel::seekTo)
                    } else {
                        NowPlaying(state, current)
                    }
                }
            }
            Controls(
                state = state,
                progress = progress,
                playFocus = playFocus,
                viewModel = viewModel,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 96.dp, bottom = 90.dp)
                    .width(1064.dp),
            )
            QueuePanel(
                state = state,
                progress = progress,
                onPick = viewModel::playAt,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 96.dp, top = 170.dp, bottom = 90.dp)
                    .width(600.dp)
                    .fillMaxHeight(),
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
                    .background(Color(0xCC05090F), RoundedCornerShape(GlacierShapes.RadiusLg))
                    .padding(48.dp),
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

/** The cover, blown up and blurred behind everything (design: blur 90, 80 %), darkened towards the bottom. */
@Composable
private fun Background(track: MusicTrack?) {
    Box(Modifier.fillMaxSize()) {
        // Blur needs Android 12; before that the small cover, scaled up, is soft enough on its own.
        val blurred = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        Artwork(
            if (blurred) track?.largeCoverUrl else track?.coverUrl,
            Modifier
                .fillMaxSize()
                .scale(1.17f)
                .then(if (blurred) Modifier.blur(90.dp) else Modifier)
                .alpha(0.8f),
        )
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x8005090F), Color(0xDB05090F)))))
    }
}

@Composable
private fun TopBar(state: MusicUiState, track: MusicTrack, modifier: Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.SpaceBetween) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val kind = stringResource(
                when (state.sourceKind) {
                    ItemKind.Artist -> R.string.music_artist
                    ItemKind.Playlist -> R.string.music_playlist
                    else -> R.string.music_album
                },
            )
            Text(
                stringResource(R.string.music_playing_from, kind).uppercase(),
                style = GlacierText.body(18).copy(letterSpacing = 0.06.em),
                color = GlacierColors.Mist,
            )
            Text(state.sourceTitle, style = GlacierText.display(28), color = GlacierColors.Ice, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            audioFormatText(track.format, short = true)?.let { StatusPill(it) }
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
}

private fun PlaybackMethod.label(): Int = when (this) {
    PlaybackMethod.DirectPlay -> R.string.player_direct_play
    PlaybackMethod.DirectStream -> R.string.player_direct_stream
    PlaybackMethod.Transcode -> R.string.player_transcode
}

@Composable
private fun StatusPill(text: String) {
    Box(
        Modifier
            .height(44.dp)
            .clip(PillShape)
            .background(GlacierColors.GlassFill)
            .border(1.dp, GlacierColors.GlassBorder, PillShape)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = GlacierText.body(16).copy(letterSpacing = 0.06.em), color = GlacierColors.Ice, maxLines = 1)
    }
}

/** Big cover with title, artist, album and what comes next (design: lyrics off). */
@Composable
private fun NowPlaying(state: MusicUiState, track: MusicTrack) {
    Row(Modifier.padding(top = 190.dp).fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(56.dp)) {
        val shape = RoundedCornerShape(GlacierShapes.RadiusLg)
        Box(Modifier.size(480.dp).clip(shape).border(1.dp, GlacierColors.GlassBorder2, shape)) {
            Artwork(track.largeCoverUrl, Modifier.fillMaxSize())
        }
        Column(Modifier.weight(1f).padding(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            val accent = LocalAccent.current.main
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Equalizer(state.playing)
                Text(
                    stringResource(if (state.playing) R.string.music_now_playing else R.string.music_paused).uppercase(),
                    style = GlacierText.body(16, FontWeight.Bold).copy(letterSpacing = 0.09.em),
                    color = accent,
                )
            }
            Text(
                track.title,
                style = GlacierText.display(54).copy(lineHeight = 58.sp),
                color = GlacierColors.Ice,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            track.artist?.let { Text(it, style = GlacierText.body(26), color = GlacierColors.Ice, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            val album = listOfNotNull(track.album, track.year?.toString()).joinToString(" · ")
            if (album.isNotEmpty()) Text(album, style = GlacierText.body(20), color = GlacierColors.Mist, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(nextLabel(state), style = GlacierText.body(18), color = GlacierColors.Mist, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 14.dp))
        }
    }
}

@Composable
private fun nextLabel(state: MusicUiState): String {
    val next = state.queue.getOrNull(state.index + 1)?.track
        ?: state.queue.firstOrNull()?.track?.takeIf { state.repeat == RepeatMode.All }
    return when {
        state.repeat == RepeatMode.One -> stringResource(R.string.music_repeating)
        next != null -> stringResource(R.string.music_up_next, listOfNotNull(next.title, next.artist).joinToString(" · "))
        else -> stringResource(R.string.music_last_track)
    }
}

/** Three bars bouncing while music plays (design `gEq`); still when paused. */
@Composable
private fun Equalizer(playing: Boolean) {
    val accent = LocalAccent.current.main
    val transition = rememberInfiniteTransition(label = "eq")
    val bars = listOf(700 to 0, 900 to 400, 600 to 200).map { (duration, offset) ->
        if (playing) {
            transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(duration), AnimationRepeat.Reverse, initialStartOffset = androidx.compose.animation.core.StartOffset(offset)),
                label = "bar",
            ).value
        } else {
            0.5f
        }
    }
    Row(Modifier.height(18.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        bars.forEach { level ->
            Box(
                Modifier
                    .width(4.dp)
                    .height(18.dp)
                    .graphicsLayer {
                        scaleY = level
                        transformOrigin = TransformOrigin(0.5f, 1f)
                    }
                    .background(accent, RoundedCornerShape(2.dp)),
            )
        }
    }
}

/** Small cover and title above the lyrics, which follow the song when synced (design: "Songtext"). */
@Composable
private fun LyricsView(state: MusicUiState, track: MusicTrack, positionMs: Long, onSeek: (Long) -> Unit) {
    val accent = LocalAccent.current.main
    val loaded = (state.lyrics as? LyricsState.Loaded)?.lyrics
    Column(Modifier.padding(top = 170.dp).fillMaxWidth().height(600.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            val shape = RoundedCornerShape(GlacierShapes.RadiusMd)
            Box(Modifier.size(112.dp).clip(shape).border(1.dp, GlacierColors.GlassBorder2, shape)) {
                Artwork(track.coverUrl, Modifier.fillMaxSize())
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(if (loaded?.synced == true) R.string.lyrics_synced else R.string.lyrics_title).uppercase(),
                    style = GlacierText.body(15, FontWeight.Bold).copy(letterSpacing = 0.09.em),
                    color = accent,
                )
                Text(track.title, style = GlacierText.display(34), color = GlacierColors.Ice, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(track.artist, track.album).joinToString(" · "),
                    style = GlacierText.body(20),
                    color = GlacierColors.Mist,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box(Modifier.padding(top = 28.dp).fillMaxSize()) {
            when (val lyrics = state.lyrics) {
                LyricsState.Loading -> Unit
                LyricsState.None -> NoLyrics(stringResource(R.string.lyrics_none), Modifier.align(Alignment.CenterStart))
                is LyricsState.Loaded -> if (lyrics.lyrics.instrumental) {
                    NoLyrics(stringResource(R.string.lyrics_instrumental), Modifier.align(Alignment.CenterStart))
                } else {
                    LyricLines(track, lyrics.lyrics.lines, lyrics.lyrics.synced, positionMs, onSeek)
                }
            }
        }
    }
}

/** "Kein Songtext" or, when the lyrics file says so, "Instrumental" (design `lyrInst`). */
@Composable
private fun NoLyrics(title: String, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = GlacierText.display(40), color = GlacierColors.Mist)
        Text(stringResource(R.string.lyrics_none_hint), style = GlacierText.body(20), color = GlacierColors.Mist)
    }
}

@Composable
private fun LyricLines(track: MusicTrack, lines: List<LyricLine>, synced: Boolean, positionMs: Long, onSeek: (Long) -> Unit) {
    val listState = rememberLazyListState()
    val current = if (synced) lines.indexOfLast { (it.startMs ?: Long.MAX_VALUE) <= positionMs } else -1
    var focusedInside by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .offset(x = (-24).dp)
            .onFocusChanged { focusedInside = it.hasFocus }
            // Lines fade out at the top and bottom edge (design mask 16 % / 78 %).
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(
                    Brush.verticalGradient(0f to Color.Transparent, 0.16f to Color.Black, 0.78f to Color.Black, 1f to Color.Transparent),
                    blendMode = BlendMode.DstIn,
                )
            },
    ) {
        val anchor = with(density) { (maxHeight * LYRIC_ANCHOR).roundToPx() }
        // The current line keeps its place while nobody is reading ahead with the remote.
        LaunchedEffect(track.id, current, focusedInside) {
            if (focusedInside) return@LaunchedEffect
            if (current < 0) listState.animateScrollToItem(0) else listState.animateScrollToItem(current, -anchor + with(density) { 90.dp.roundToPx() })
        }
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(top = 90.dp, bottom = 260.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(lines, key = { i, _ -> "${track.id}-$i" }) { i, line ->
                LyricRow(
                    line = line,
                    state = when {
                        !synced -> LineState.Plain
                        i == current -> LineState.Current
                        i < current -> LineState.Past
                        else -> LineState.Future
                    },
                    onClick = { line.startMs?.let(onSeek) },
                )
            }
        }
    }
}

private enum class LineState { Past, Current, Future, Plain }

@Composable
private fun LyricRow(line: LyricLine, state: LineState, onClick: () -> Unit) {
    val accent = LocalAccent.current.main
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(GlacierShapes.RadiusMd)
    val opacity = when {
        focused || state == LineState.Current -> 1f
        state == LineState.Past -> 0.4f
        state == LineState.Plain -> 0.85f
        else -> 0.6f
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (focused) GlacierColors.GlassFill else Color.Transparent)
            .border(2.dp, if (focused) accent else Color.Transparent, shape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(
            line.text.ifBlank { "♪" },
            style = GlacierText.display(40).copy(lineHeight = 50.sp),
            color = if (state == LineState.Current || state == LineState.Plain) GlacierColors.Ice else GlacierColors.Mist,
            modifier = Modifier.weight(1f).alpha(opacity),
        )
        val start = line.startMs
        if (focused && start != null) Text(formatTime(start), style = GlacierText.mono(18), color = accent)
    }
}

/** Timeline, times and the button row (design rows 0 and 1). */
@Composable
private fun Controls(
    state: MusicUiState,
    progress: MusicProgress,
    playFocus: FocusRequester,
    viewModel: MusicPlayerViewModel,
    modifier: Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(28.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SeekBar(progress, state.seekBackMs, state.seekForwardMs, down = playFocus, onSeekBy = viewModel::seekBy, onClick = viewModel::togglePlay)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(formatTime(progress.positionMs), style = GlacierText.mono(20), color = GlacierColors.Ice)
                Text(
                    stringResource(R.string.music_track_of, state.index + 1, state.queue.size),
                    style = GlacierText.body(18),
                    color = GlacierColors.Mist,
                    modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                Text("−" + formatTime(progress.durationMs - progress.positionMs), style = GlacierText.mono(20), color = GlacierColors.Mist)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            MusicButton(GlacierIcons.Shuffle, stringResource(if (state.shuffle) R.string.action_shuffle_off else R.string.action_shuffle), viewModel::toggleShuffle, on = state.shuffle)
            MusicButton(GlacierIcons.SkipBack, stringResource(R.string.player_previous), viewModel::previous)
            MusicButton(
                if (state.playWhenReady) GlacierIcons.Pause else GlacierIcons.Play,
                stringResource(if (state.playWhenReady) R.string.player_pause else R.string.player_play),
                viewModel::togglePlay,
                big = true,
                modifier = Modifier.focusRequester(playFocus),
            )
            MusicButton(GlacierIcons.SkipForward, stringResource(R.string.player_next), viewModel::next)
            MusicButton(
                if (state.repeat == RepeatMode.One) GlacierIcons.RepeatOne else GlacierIcons.Repeat,
                stringResource(R.string.action_repeat),
                viewModel::cycleRepeat,
                on = state.repeat != RepeatMode.Off,
            )
            MusicButton(GlacierIcons.Lyrics, stringResource(R.string.lyrics_title), viewModel::toggleLyrics, on = state.lyricsOn, label = stringResource(R.string.lyrics_title))
        }
    }
}

/** Focused, Left/Right jump 10 s; the track and knob grow (design `mseek`). Down goes to [down], the play button. */
@Composable
private fun SeekBar(progress: MusicProgress, seekBackMs: Long, seekForwardMs: Long, down: FocusRequester, onSeekBy: (Long) -> Unit, onClick: () -> Unit) {
    val accent = LocalAccent.current.main
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val fraction = if (progress.durationMs > 0) (progress.positionMs.toFloat() / progress.durationMs).coerceIn(0f, 1f) else 0f
    val trackHeight by animateDpAsState(if (focused) 12.dp else 7.dp, label = "track")
    val knob by animateDpAsState(if (focused) 26.dp else 18.dp, label = "knob")
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(38.dp)
            .focusProperties { this.down = down }
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> { onSeekBy(-seekBackMs); true }
                    Key.DirectionRight -> { onSeekBy(seekForwardMs); true }
                    else -> false
                }
            }
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxWidth().height(trackHeight).clip(PillShape).background(GlacierColors.GlassFill2)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(accent))
        }
        Box(
            Modifier
                .offset { IntOffset((maxWidth * fraction - knob / 2).roundToPx(), 0) }
                .size(knob)
                .then(if (focused) Modifier.border(8.dp, accent.copy(alpha = 0.3f), PillShape) else Modifier)
                .clip(PillShape)
                .background(accent),
        )
    }
}

/**
 * Round control (62, the play button 78): accent when focused, tinted when
 * [on]; with [label] a pill with icon and text (design `mp.controls`).
 */
@Composable
private fun MusicButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    on: Boolean = false,
    big: Boolean = false,
    label: String? = null,
) {
    val accent = LocalAccent.current.main
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val size = if (big) 78.dp else 62.dp
    val background = when {
        focused -> accent
        on -> accent.copy(alpha = 0.26f)
        big -> GlacierColors.GlassFill2
        else -> GlacierColors.GlassFill
    }
    val border = when {
        focused || on -> accent
        big -> GlacierColors.GlassBorder2
        else -> GlacierColors.GlassBorder
    }
    val foreground = when {
        focused -> GlacierColors.Void
        on -> accent
        else -> GlacierColors.Ice
    }
    Row(
        modifier
            .focusScale(focused)
            .focusFrame(focused, PillShape, unfocusedBorder = border)
            .height(size)
            .then(if (label == null) Modifier.width(size) else Modifier)
            .clip(PillShape)
            .background(background)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(start = if (label != null) 20.dp else 0.dp, end = if (label != null) 24.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
    ) {
        Icon(icon, contentDescription = if (label == null) description else null, tint = foreground, modifier = Modifier.size(if (big) 30.dp else 24.dp))
        if (label != null) Text(label, style = GlacierText.body(18, FontWeight.SemiBold), color = foreground)
    }
}

/** "Wiedergabeliste": the queue, the current song marked, played ones faded. */
@Composable
private fun QueuePanel(state: MusicUiState, progress: MusicProgress, onPick: (Int) -> Unit, modifier: Modifier) {
    val shape = RoundedCornerShape(GlacierShapes.RadiusLg)
    val listState = rememberLazyListState()
    val currentFocus = remember { FocusRequester() }
    var focusedInside by remember { mutableStateOf(false) }
    LaunchedEffect(state.index, focusedInside) {
        if (!focusedInside && state.queue.isNotEmpty()) listState.animateScrollToItem((state.index - 1).coerceAtLeast(0))
    }
    Column(
        modifier
            .clip(shape)
            .background(GlacierColors.GlassFill2)
            .border(1.dp, GlacierColors.GlassBorder2, shape),
    ) {
        Column(Modifier.padding(start = 32.dp, end = 32.dp, top = 28.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.music_queue), style = GlacierText.display(28), color = GlacierColors.Ice)
            val total = state.queue.sumOf { it.track.durationMs }
            val left = state.queue.drop(state.index).sumOf { it.track.durationMs } - progress.positionMs
            Text(
                listOf(
                    pluralStringResource(R.plurals.count_titles, state.queue.size, state.queue.size),
                    runtimeText((total / 60_000).toInt().coerceAtLeast(1)),
                    stringResource(R.string.music_queue_left, runtimeText((left / 60_000).toInt().coerceAtLeast(1))),
                ).joinToString(" · "),
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
                // Coming in from the controls lands on the song that plays.
                .focusProperties { onEnter = { currentFocus.requestFocus() } },
        ) {
            itemsIndexed(state.queue, key = { _, entry -> entry.key }) { i, entry ->
                QueueRow(
                    number = i + 1,
                    track = entry.track,
                    current = i == state.index,
                    played = i < state.index,
                    playing = state.playing,
                    onClick = { onPick(i) },
                    modifier = if (i == state.index) Modifier.focusRequester(currentFocus) else Modifier,
                )
            }
        }
    }
}

@Composable
private fun QueueRow(number: Int, track: MusicTrack, current: Boolean, played: Boolean, playing: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val accent = LocalAccent.current.main
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(GlacierShapes.RadiusMd)
    val scale = if (focused) 1.015f else 1f
    Row(
        modifier
            .scale(scale)
            .fillMaxWidth()
            .height(72.dp)
            .clip(shape)
            .background(
                when {
                    focused -> GlacierColors.GlassFill2
                    current -> accent.copy(alpha = 0.14f)
                    else -> Color.Transparent
                },
            )
            .border(2.dp, if (focused) accent else Color.Transparent, shape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp)
            .alpha(if (played) 0.55f else 1f),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(Modifier.width(30.dp), contentAlignment = Alignment.Center) {
            if (current) Equalizer(playing) else Text("%02d".format(number), style = GlacierText.mono(17), color = GlacierColors.Mist)
        }
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(GlacierShapes.RadiusSm))) {
            Artwork(track.coverUrl, Modifier.fillMaxSize())
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                track.title,
                style = GlacierText.body(19, FontWeight.SemiBold),
                color = if (current) accent else GlacierColors.Ice,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(track.artist, track.album).joinToString(" · "),
                style = GlacierText.body(16),
                color = GlacierColors.Mist,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(formatTime(track.durationMs), style = GlacierText.mono(16), color = GlacierColors.Mist)
    }
}

private val ClockFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private const val CLOCK_TICK_MS = 10_000L
