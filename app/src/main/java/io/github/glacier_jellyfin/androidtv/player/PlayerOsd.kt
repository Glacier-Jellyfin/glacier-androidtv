package io.github.glacier_jellyfin.androidtv.player

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.width
import io.github.glacier_jellyfin.androidtv.core.data.media.Chapter
import io.github.glacier_jellyfin.androidtv.core.data.media.Trickplay
import io.github.glacier_jellyfin.androidtv.core.data.playback.MediaSegment
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.TileMode
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierShapes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.data.media.ItemKind
import io.github.glacier_jellyfin.androidtv.core.data.playback.PlaybackMethod
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierClickable
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierIcons
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.glacier_jellyfin.androidtv.core.designsystem.LocalAccent
import io.github.glacier_jellyfin.androidtv.core.designsystem.PillShape
import io.github.glacier_jellyfin.androidtv.ui.seasonLabel
import io.github.glacier_jellyfin.androidtv.ui.qualityText
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@Composable
fun PlayerOsd(
    state: PlayerUiState,
    progress: PlayerProgress,
    scrubMs: Long?,
    seekFocus: FocusRequester,
    playFocus: FocusRequester,
    buttonFocus: Map<OsdButton, FocusRequester>,
    onScrub: (Long) -> Unit,
    onCommitScrub: () -> Unit,
    onTogglePlay: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onOpen: (OsdButton) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.align(Alignment.TopCenter).osdScrim(top = true))
        Box(Modifier.align(Alignment.BottomCenter).osdScrim(top = false))

        TitleBlock(state, Modifier.align(Alignment.TopStart).padding(start = 80.dp, top = 60.dp))
        StatusBlock(state, Modifier.align(Alignment.TopEnd).padding(end = 80.dp, top = 64.dp))

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 80.dp, end = 80.dp, bottom = 72.dp),
            verticalArrangement = Arrangement.spacedBy(30.dp),
        ) {
            Timeline(
                progress = progress,
                scrubMs = scrubMs,
                chapters = state.chapters,
                segments = state.segments,
                trickplay = state.details?.trickplay,
                imageHeaders = state.imageHeaders,
                focusRequester = seekFocus,
                downFocus = playFocus,
                seekBackMs = state.seekBackMs,
                seekForwardMs = state.seekForwardMs,
                onScrub = onScrub,
                onCommit = { if (scrubMs != null) onCommitScrub() else onTogglePlay() },
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (state.previous != null) {
                        ControlButton(GlacierIcons.SkipBack, stringResource(R.string.player_previous), onClick = onPrevious)
                    }
                    ControlButton(GlacierIcons.Replay, stringResource(R.string.player_rewind), onClick = { onSeekBy(-state.seekBackMs) })
                    ControlButton(
                        if (state.playWhenReady) GlacierIcons.Pause else GlacierIcons.Play,
                        stringResource(if (state.playWhenReady) R.string.player_pause else R.string.player_play),
                        onClick = onTogglePlay,
                        big = true,
                        modifier = Modifier.focusRequester(playFocus),
                    )
                    ControlButton(GlacierIcons.Forward, stringResource(R.string.player_forward), onClick = { onSeekBy(state.seekForwardMs) })
                    if (state.next != null) {
                        ControlButton(GlacierIcons.SkipForward, stringResource(R.string.player_next), onClick = onNext)
                    }
                    LabelButton(GlacierIcons.Speaker, stringResource(R.string.player_audio), { onOpen(OsdButton.Audio) }, Modifier.focusRequester(buttonFocus.getValue(OsdButton.Audio)))
                    if (state.subtitles.isNotEmpty()) {
                        LabelButton(GlacierIcons.Subtitles, stringResource(R.string.player_subtitles), { onOpen(OsdButton.Subtitles) }, Modifier.focusRequester(buttonFocus.getValue(OsdButton.Subtitles)))
                    }
                    if (state.chapters.isNotEmpty()) {
                        LabelButton(GlacierIcons.Chapters, stringResource(R.string.player_chapters), { onOpen(OsdButton.Chapters) }, Modifier.focusRequester(buttonFocus.getValue(OsdButton.Chapters)))
                    }
                    ControlButton(GlacierIcons.Info, stringResource(R.string.player_info), onClick = { onOpen(OsdButton.Info) }, modifier = Modifier.focusRequester(buttonFocus.getValue(OsdButton.Info)))
                }
                Spacer(Modifier.weight(1f))
                if (progress.durationMs > 0) {
                    val end = LocalTime.now().plusSeconds((progress.durationMs - progress.positionMs).coerceAtLeast(0) / 1000)
                    Text(stringResource(R.string.player_ends_at, end.format(ClockFormat)), style = GlacierText.body(18), color = OsdSecondary)
                }
            }
        }
    }
}

@Composable
private fun TitleBlock(state: PlayerUiState, modifier: Modifier) {
    val item = state.details?.item ?: return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val crumb = if (item.kind == ItemKind.Episode) {
            listOfNotNull(item.parentTitle, item.seasonNumber?.let { seasonLabel(it) })
        } else {
            listOfNotNull(stringResource(R.string.library_movies), item.year?.toString(), item.genres.firstOrNull())
        }.joinToString(" · ")
        Text(crumb.uppercase(), style = GlacierText.body(18).copy(letterSpacing = 0.06.em), color = OsdSecondary)
        val title = item.episodeNumber?.takeIf { item.kind == ItemKind.Episode }
            ?.let { stringResource(R.string.player_episode_title, it, item.title) } ?: item.title
        Text(title, style = GlacierText.display(46), color = GlacierColors.Ice, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Picture quality, how the stream is delivered, and the time of day. */
@Composable
private fun StatusBlock(state: PlayerUiState, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        qualityText(state.details?.item?.quality)?.let { StatusPill(it) }
        state.method?.let {
            StatusPill(
                stringResource(
                    when (it) {
                        PlaybackMethod.DirectPlay -> R.string.player_direct_play
                        PlaybackMethod.DirectStream -> R.string.player_direct_stream
                        PlaybackMethod.Transcode -> R.string.player_transcode
                    },
                ),
            )
        }
        Text(LocalTime.now().format(ClockFormat), style = GlacierText.mono(20), color = OsdSecondary)
    }
}

@Composable
private fun StatusPill(text: String) {
    Box(
        Modifier
            .height(44.dp)
            .clip(PillShape)
            .background(OsdGlass)
            .border(1.dp, GlacierColors.GlassBorder2, PillShape)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = GlacierText.body(17, FontWeight.SemiBold), color = GlacierColors.Ice)
    }
}

/**
 * The seek bar: buffered range, played range and a knob. Focused, Left/Right
 * move only the knob (scrubbing); the jump follows after a short pause or OK.
 */
@Composable
private fun Timeline(
    progress: PlayerProgress,
    scrubMs: Long?,
    chapters: List<Chapter>,
    segments: List<MediaSegment>,
    trickplay: Trickplay?,
    imageHeaders: Map<String, String>,
    focusRequester: FocusRequester,
    /** Down always lands on play; the nearest button under the knob could be Stop. */
    downFocus: FocusRequester,
    seekBackMs: Long,
    seekForwardMs: Long,
    onScrub: (Long) -> Unit,
    onCommit: () -> Unit,
) {
    val accent = LocalAccent.current.main
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val duration = progress.durationMs.coerceAtLeast(1)
    val shown = scrubMs ?: progress.positionMs
    if (scrubMs != null && progress.durationMs > 0) {
        ScrubPreview(
            positionMs = scrubMs,
            deltaMs = scrubMs - progress.positionMs,
            fraction = fraction(scrubMs, duration),
            chapter = chapterAt(chapters, scrubMs)?.name,
            trickplay = trickplay,
            imageHeaders = imageHeaders,
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .height(38.dp)
                .focusRequester(focusRequester)
                .focusProperties { down = downFocus }
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (event.key) {
                        Key.DirectionLeft -> { onScrub(-seekBackMs); true }
                        Key.DirectionRight -> { onScrub(seekForwardMs); true }
                        Key.DirectionCenter, Key.Enter -> { onCommit(); true }
                        else -> false
                    }
                }
                .focusable(interactionSource = interaction),
            contentAlignment = Alignment.CenterStart,
        ) {
            val trackHeight = if (focused) 12.dp else 7.dp
            val trackWidth = maxWidth
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(trackHeight)
                    .clip(PillShape)
                    .background(OsdGlass),
            ) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(fraction(progress.bufferedMs, duration)).background(GlacierColors.GlassBorder2))
                Box(Modifier.fillMaxHeight().fillMaxWidth(fraction(progress.positionMs, duration)).background(accent))
                // Known segments (intro, credits …) as hatched stretches (design).
                if (progress.durationMs > 0) {
                    segments.forEach { segment ->
                        Box(
                            Modifier
                                .offset(x = trackWidth * fraction(segment.startMs, duration))
                                .width(trackWidth * (fraction(segment.endMs, duration) - fraction(segment.startMs, duration)))
                                .fillMaxHeight()
                                .clip(PillShape)
                                .drawBehind { drawRect(SegmentHatch) },
                        )
                    }
                }
            }
            // Chapter starts as small gaps in the track (design: ticks).
            if (progress.durationMs > 0) {
                chapters.drop(1).forEach { chapter ->
                    Box(
                        Modifier
                            .offset(x = maxWidth * fraction(chapter.startMs, duration) - 1.dp)
                            .size(2.dp, if (focused) 18.dp else 13.dp)
                            .background(Color(0xD90A1420)),
                    )
                }
            }
            val knob = if (focused) 26.dp else 18.dp
            // Focused, a glow ring of 8 around the knob shows where the keys go (design).
            val ring = if (focused) 8.dp else 0.dp
            if (progress.durationMs > 0) Box(
                Modifier
                    .offset(x = maxWidth * fraction(shown, duration) - knob / 2 - ring)
                    .size(knob + ring * 2)
                    .background(if (focused) accent.copy(alpha = 0.3f) else Color.Transparent, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(knob)
                        .shadow(if (focused) 0.dp else 8.dp, CircleShape)
                        .clip(CircleShape)
                        .background(accent),
                )
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(formatTime(shown), style = GlacierText.mono(20), color = GlacierColors.Ice)
            Text(
                chapterAt(chapters, progress.positionMs)?.name.orEmpty(),
                style = GlacierText.mono(20),
                color = OsdSecondary,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 24.dp),
            )
            if (progress.durationMs > 0) {
                Text("−" + formatTime(progress.durationMs - progress.positionMs), style = GlacierText.mono(20), color = OsdSecondary)
            }
        }
    }
}

/** OSD buttons that open an overlay; the overlay hands focus back to them when it closes. */
enum class OsdButton { Audio, Subtitles, Chapters, Info }

private fun chapterAt(chapters: List<Chapter>, positionMs: Long): Chapter? = chapters.lastOrNull { it.startMs <= positionMs }

/**
 * Trickplay preview (design: 340×191) with the chapter, the target time and
 * the jump from the current position; above the timeline, following the knob.
 */
@Composable
private fun ScrubPreview(
    positionMs: Long,
    deltaMs: Long,
    fraction: Float,
    chapter: String?,
    trickplay: Trickplay?,
    imageHeaders: Map<String, String>,
) {
    val accent = LocalAccent.current.main
    val shape = RoundedCornerShape(GlacierShapes.RadiusMd)
    BoxWithConstraints(Modifier.fillMaxWidth().height(if (trickplay != null) 240.dp else 40.dp)) {
        val half = 170.dp
        val center = (maxWidth * fraction).coerceIn(half, maxWidth - half)
        Column(
            Modifier
                .offset(x = center - half)
                .width(340.dp)
                .align(Alignment.BottomStart),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (trickplay != null) {
                Box(
                    Modifier
                        .size(340.dp, 191.dp)
                        .clip(shape)
                        .background(GlacierColors.Deep)
                        .border(2.dp, GlacierColors.GlassBorder2, shape),
                ) {
                    TrickplayThumb(trickplay, positionMs, imageHeaders, Modifier.fillMaxSize())
                    if (chapter != null) {
                        Box(
                            Modifier
                                .align(Alignment.BottomStart)
                                .padding(start = 12.dp, bottom = 10.dp)
                                .height(30.dp)
                                .clip(PillShape)
                                .background(Color(0xB8050910))
                                .padding(horizontal = 12.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(chapter, style = GlacierText.body(15), color = GlacierColors.Ice, maxLines = 1)
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(formatTime(positionMs), style = GlacierText.mono(24), color = GlacierColors.Ice)
                Text((if (deltaMs >= 0) "+" else "−") + formatTime(kotlin.math.abs(deltaMs)), style = GlacierText.mono(18), color = accent)
            }
        }
    }
}

/** Pill with icon and label, 62 high (design: Audio, Subtitles, Chapters; the trailer's "Play movie" is [primary]). */
@Composable
internal fun LabelButton(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false) {
    val accent = LocalAccent.current
    GlacierClickable(onClick = onClick, shape = PillShape, modifier = modifier, contentAlignment = Alignment.Center) { focused ->
        Row(
            Modifier
                .height(62.dp)
                .clip(PillShape)
                .background(if (focused) accent.main else if (primary) accent.deep else OsdGlass)
                .border(2.dp, if (focused) accent.main else if (primary) accent.deep else GlacierColors.GlassBorder2, PillShape)
                .padding(horizontal = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val color = if (focused || primary) GlacierColors.Void else GlacierColors.Ice
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(23.dp))
            Text(label, style = GlacierText.body(19, FontWeight.SemiBold), color = color)
        }
    }
}

/** Round OSD button: 62 across, the play button 78 (design). */
@Composable
internal fun ControlButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    big: Boolean = false,
) {
    val accent = LocalAccent.current.main
    val size = if (big) 78.dp else 62.dp
    GlacierClickable(onClick = onClick, shape = CircleShape, modifier = modifier, contentAlignment = Alignment.Center) { focused ->
        Box(
            Modifier
                .size(size)
                .clip(CircleShape)
                .background(if (focused) accent else OsdGlass)
                .border(2.dp, if (focused) accent else GlacierColors.GlassBorder2, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = description, tint = if (focused) GlacierColors.Void else GlacierColors.Ice, modifier = Modifier.size(if (big) 30.dp else 24.dp))
        }
    }
}

/** Diagonal stripes, 5 px ice and 5 px clear (design: repeating 115° gradient). */
private val SegmentHatch = Brush.linearGradient(
    0f to Color(0x57E8F4F7), 0.5f to Color(0x57E8F4F7), 0.5f to Color.Transparent, 1f to Color.Transparent,
    start = Offset.Zero,
    end = Offset(9f, -4.2f),
    tileMode = TileMode.Repeated,
)

/**
 * Glass behind OSD controls: the design's light glass, a step stronger. The
 * contrast on bright pictures comes from the darker scrims ([osdScrim]).
 */
internal val OsdGlass = GlacierColors.GlassFill2

/** Secondary OSD text; lighter than Mist so it holds up over the picture. */
internal val OsdSecondary = Color(0xFFC4D4DC)

private fun fraction(value: Long, total: Long): Float = (value.toFloat() / total).coerceIn(0f, 1f)

private val ClockFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** 1:02:03 or 12:34. */
internal fun formatTime(ms: Long): String {
    val total = (ms.coerceAtLeast(0) / 1000)
    val h = total / 3600
    val m = total % 3600 / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

