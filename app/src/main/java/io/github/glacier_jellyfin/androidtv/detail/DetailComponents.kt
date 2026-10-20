package io.github.glacier_jellyfin.androidtv.detail

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.data.media.CastMember
import io.github.glacier_jellyfin.androidtv.core.data.media.Languages
import io.github.glacier_jellyfin.androidtv.core.data.media.TrackMark
import io.github.glacier_jellyfin.androidtv.core.data.media.describeTrack
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaItem
import io.github.glacier_jellyfin.androidtv.core.data.media.Track
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierCard
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierIcons
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierShapes
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.glacier_jellyfin.androidtv.core.designsystem.LocalAccent
import io.github.glacier_jellyfin.androidtv.core.designsystem.PillShape
import io.github.glacier_jellyfin.androidtv.core.designsystem.focusFrame
import io.github.glacier_jellyfin.androidtv.core.designsystem.focusScale
import io.github.glacier_jellyfin.androidtv.ui.Artwork
import io.github.glacier_jellyfin.androidtv.ui.LocalCardSizes
import io.github.glacier_jellyfin.androidtv.ui.MarqueeText
import io.github.glacier_jellyfin.androidtv.ui.ProgressBar

/**
 * One line of [TrackPanel]: [label] with its [flag], the quieter [facts]
 * below it and the [marks] as pills; [full] is the name for the
 * confirmation toast.
 */
data class TrackRow(
    val label: String,
    val flag: String?,
    val facts: String? = null,
    val marks: List<String> = emptyList(),
    val full: String = label,
)

/** Rows for [TrackPanel] and the stream index behind each; subtitles start with "Off" (index null). */
data class TrackOptions(val rows: List<TrackRow>, val indices: List<Int?>)

@Composable
fun trackOptions(tracks: List<Track>, subtitle: Boolean): TrackOptions {
    val rows = (if (subtitle) listOf(TrackRow(stringResource(R.string.track_off), null)) else emptyList()) +
        tracks.map { trackRow(it, subtitle) }
    val indices = (if (subtitle) listOf<Int?>(null) else emptyList()) + tracks.map { it.index }
    return TrackOptions(rows, indices)
}

/** A track as the web plugin shows it: "Deutsch", below it "DTS-HD MA 5.1 · Standard", pills "Kommentar". */
@Composable
fun trackRow(track: Track, subtitle: Boolean): TrackRow {
    val description = describeTrack(track, subtitle, listOf(LocalConfiguration.current.locales[0]))
    val marks = description.marks.map { stringResource(it.label) }
    val facts = description.facts +
        listOfNotNull(
            stringResource(R.string.track_external).takeIf { track.external },
            stringResource(R.string.track_default).takeIf { track.default },
        )
    return TrackRow(
        label = description.name,
        flag = Languages.flag(track.language, track.region),
        facts = facts.joinToString(" · ").ifEmpty { null },
        marks = marks,
        full = (listOf(description.name) + marks).joinToString(" · "),
    )
}

private val TrackMark.label: Int
    get() = when (this) {
        TrackMark.Forced -> R.string.track_forced
        TrackMark.HearingImpaired -> R.string.track_sdh
        TrackMark.ClosedCaptions -> R.string.track_cc
        TrackMark.SingAlong -> R.string.track_sing_along
        TrackMark.Commentary -> R.string.track_commentary
        TrackMark.AudioDescription -> R.string.track_audio_description
    }

/** The name and its marks: "Deutsch · Erzwungen". */
@Composable
fun trackLabel(track: Track, subtitle: Boolean): String = trackRow(track, subtitle).full

/** What a track chip shows: the language (or the track's name) with its flag, the marks as icons. */
data class TrackChipContent(val label: String, val flag: String?, val marks: List<TrackMark> = emptyList())

@Composable
fun trackChipContent(track: Track?, subtitle: Boolean): TrackChipContent {
    if (track == null) return TrackChipContent(stringResource(R.string.track_off), null)
    val description = describeTrack(track, subtitle, listOf(LocalConfiguration.current.locales[0]))
    return TrackChipContent(description.name, Languages.flag(track.language, track.region), description.marks)
}

private val TrackMark.icon: ImageVector
    get() = when (this) {
        TrackMark.Forced -> GlacierIcons.Signpost
        TrackMark.HearingImpaired -> GlacierIcons.EarOff
        TrackMark.ClosedCaptions -> GlacierIcons.ClosedCaption
        TrackMark.SingAlong -> GlacierIcons.MicVocal
        TrackMark.Commentary -> GlacierIcons.Comment
        TrackMark.AudioDescription -> GlacierIcons.AudioDescription
    }

/**
 * A language's flag in the design's style (27×18 in a rounded mist outline),
 * from the flag-icons set in the app's assets (MIT, assets/flags/LICENSE).
 * The flag itself stays square: inside the outline's radius and the gap, a
 * matching inner radius would be under 1.
 */
@Composable
fun LanguageFlag(flag: String, modifier: Modifier = Modifier, width: Int = 24, height: Int = 16) {
    Box(
        modifier
            .padding(2.dp)
            .border(1.5.dp, GlacierColors.Mist, RoundedCornerShape(4.dp))
            .padding(2.dp)
            .size(width.dp, height.dp)
            .clipToBounds(),
    ) {
        AsyncImage(
            model = "file:///android_asset/flags/$flag.svg",
            contentDescription = null,
            // 4:3 artwork in a 3:2 frame: trim top and bottom, as flags usually are.
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * Audio or subtitle chip after the action buttons: quiet until focused, but on
 * light glass with a clear edge so it stays readable over a bright backdrop.
 */
@Composable
fun TrackChip(content: TrackChipContent, icon: ImageVector?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val accent = LocalAccent.current.main
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val foreground = if (focused) GlacierColors.Void else GlacierColors.Ice
    Row(
        modifier
            .focusScale(focused)
            .height(46.dp)
            .clip(PillShape)
            .background(if (focused) accent else GlacierColors.GlassFill2)
            .border(1.5.dp, if (focused) accent else GlacierColors.GlassBorder2, PillShape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        icon?.let { Icon(it, contentDescription = null, tint = foreground, modifier = Modifier.size(19.dp)) }
        content.flag?.let { LanguageFlag(it) }
        // Episode pages leave the chips little room: one line, cut short, the mark icons keep their place.
        Text(
            content.label,
            style = GlacierText.body(17),
            color = foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        content.marks.forEach { mark ->
            Icon(mark.icon, contentDescription = stringResource(mark.label), tint = foreground, modifier = Modifier.size(19.dp))
        }
    }
}

/** Thin vertical divider between action buttons and track chips. */
@Composable
fun ActionDivider() {
    Box(Modifier.padding(start = 8.dp, end = 4.dp).width(1.dp).height(28.dp).background(GlacierColors.GlassBorder))
}

/** The design's track sheet: pick an audio or subtitle stream; Back closes without change. */
@Composable
fun TrackPanel(
    title: String,
    subtitle: String,
    options: List<TrackRow>,
    selected: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
    /** The trailer screen moves the sheet aside, clear of the YouTube player. */
    alignment: Alignment = Alignment.Center,
) {
    BackHandler(onBack = onDismiss)
    val focus = remember { FocusRequester() }
    // A long track list keeps a margin to the screen edges and scrolls inside the sheet.
    Box(
        Modifier.fillMaxSize().background(Color(0xA805090F)).padding(horizontal = 80.dp, vertical = 54.dp),
        contentAlignment = alignment,
    ) {
        val shape = RoundedCornerShape(GlacierShapes.RadiusLg)
        Column(
            Modifier
                .width(560.dp)
                .clip(shape)
                .background(GlacierColors.Deep)
                .border(1.dp, GlacierColors.GlassBorder2, shape)
                .padding(32.dp)
                .focusProperties { onExit = { cancelFocusChange() } }
                .focusGroup(),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, style = GlacierText.display(27), color = GlacierColors.Ice)
                Text(subtitle, style = GlacierText.body(17), color = GlacierColors.Mist)
            }
            // Lazy: a title with 30 subtitles would build every row, flag and all, before the sheet shows.
            // It opens a few rows above the current track, so that one is built and gets the focus.
            val list = rememberLazyListState(initialFirstVisibleItemIndex = (selected - 3).coerceAtLeast(0))
            LazyColumn(
                Modifier.weight(1f, fill = false),
                state = list,
                // The focused option grows by 2 %; the vertical padding keeps it from being clipped by the list.
                contentPadding = PaddingValues(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                itemsIndexed(options) { index, row ->
                    TrackOption(
                        row = row,
                        selected = index == selected,
                        onClick = { onPick(index) },
                        modifier = if (index == selected) Modifier.focusRequester(focus) else Modifier,
                    )
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        // The list builds its rows while measuring, after this effect starts.
        withFrameNanos { }
        runCatching { focus.requestFocus() }
    }
}

@Composable
private fun TrackOption(row: TrackRow, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val accent = LocalAccent.current.main
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(GlacierShapes.RadiusMd)
    val scale by animateFloatAsState(if (focused) 1.02f else 1f, label = "option")
    val foreground = when {
        focused -> GlacierColors.Void
        selected -> accent
        else -> GlacierColors.Ice
    }
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 62.dp)
            .scale(scale)
            .clip(shape)
            .background(if (focused) accent else GlacierColors.GlassFill)
            .border(2.dp, if (focused) accent else GlacierColors.GlassBorder, shape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (row.flag != null) LanguageFlag(row.flag, width = 27, height = 18)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(row.label, style = GlacierText.body(19), color = foreground)
            row.facts?.let {
                Text(
                    it,
                    style = GlacierText.body(15),
                    color = if (focused) GlacierColors.Void.copy(alpha = 0.7f) else GlacierColors.Mist,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        row.marks.forEach { TrackBadge(it, focused) }
        // The check keeps its place on every row, so the badges line up whether a row is selected or not.
        Box(Modifier.size(22.dp)) {
            if (selected) Icon(GlacierIcons.Check, contentDescription = null, tint = foreground, modifier = Modifier.size(22.dp))
        }
    }
}

/** A mark of a track ("Erzwungen", "Kommentar") at the end of its row. */
@Composable
private fun TrackBadge(text: String, focused: Boolean) {
    Box(
        Modifier
            .height(30.dp)
            .clip(PillShape)
            .background(if (focused) GlacierColors.Void.copy(alpha = 0.12f) else GlacierColors.GlassFill2)
            .border(1.dp, if (focused) GlacierColors.Void.copy(alpha = 0.3f) else GlacierColors.GlassBorder, PillShape)
            .padding(horizontal = 11.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = GlacierText.body(15, FontWeight.SemiBold), color = if (focused) GlacierColors.Void else GlacierColors.Mist, maxLines = 1)
    }
}

/** Episode card (340×192): still, episode number, runtime, watched state, title and synopsis. */
@Composable
fun EpisodeCard(
    episode: MediaItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    current: Boolean = false,
) {
    val accent = LocalAccent.current
    val shape = RoundedCornerShape(GlacierShapes.RadiusMd)
    val sizes = LocalCardSizes.current
    GlacierCard(onClick = onClick, modifier = modifier.width(sizes.episodeWidth.dp)) { focused ->
        Box(
            Modifier
                .size(sizes.episodeWidth.dp, sizes.episodeHeight.dp)
                .focusFrame(focused, shape, unfocusedBorder = if (current) accent.deep else Color.Transparent)
                .clip(shape),
        ) {
            Artwork(episode.thumbUrl, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.4f to Color.Transparent, 1f to Color(0xCC05090F))))
            if (episode.played) Box(Modifier.fillMaxSize().background(Color(0x8505090F)))
            episode.episodeNumber?.let {
                Pill(
                    stringResource(R.string.episode_badge, it),
                    Modifier.align(Alignment.TopStart).padding(start = 16.dp, top = 14.dp),
                    background = if (current) accent.main else GlacierColors.GlassFill2,
                    color = if (current) GlacierColors.Void else GlacierColors.Ice,
                    bold = true,
                )
            }
            episode.runtimeMinutes?.takeIf { it > 0 }?.let {
                Pill(stringResource(R.string.runtime_minutes, it), Modifier.align(Alignment.TopEnd).padding(end = 16.dp, top = 14.dp))
            }
            when {
                episode.played -> Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp)
                        .size(34.dp)
                        .clip(PillShape)
                        .background(accent.main),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(GlacierIcons.Check, contentDescription = null, tint = GlacierColors.Void, modifier = Modifier.size(18.dp))
                }
                episode.progress != null -> ProgressBar(
                    episode.progress ?: 0f,
                    track = Color(0x42E8F4F7),
                    modifier = Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = 16.dp).height(5.dp),
                )
            }
        }
        Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            MarqueeText(episode.title, style = GlacierText.body(19, FontWeight.SemiBold), color = if (focused) accent.main else GlacierColors.Ice, active = focused)
            Text(
                episode.overview.orEmpty(),
                style = GlacierText.body(16).copy(lineHeight = 24.sp),
                color = GlacierColors.Mist,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.height(48.dp),
            )
        }
    }
}

@Composable
private fun Pill(text: String, modifier: Modifier, background: Color = GlacierColors.GlassFill, color: Color = GlacierColors.Ice, bold: Boolean = false) {
    Box(
        modifier
            .height(32.dp)
            .clip(PillShape)
            .background(background)
            .border(1.dp, if (bold) GlacierColors.GlassBorder2 else GlacierColors.GlassBorder, PillShape)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = GlacierText.body(15, if (bold) FontWeight.Bold else FontWeight.Normal), color = color)
    }
}

/** Cast member: round portrait (or initial), name and character. */
@Composable
fun CastCard(person: CastMember, onClick: () -> Unit) = CastCard(person.name, person.role, person.imageUrl, onClick)

@Composable
fun CastCard(name: String, role: String?, imageUrl: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val accent = LocalAccent.current
    val size = LocalCardSizes.current.castSize
    GlacierCard(onClick = onClick, modifier = modifier.width((size + 18).dp)) { focused ->
        Box(
            Modifier
                .align(Alignment.CenterHorizontally)
                .size(size.dp)
                .focusFrame(focused, PillShape)
                .clip(PillShape)
                .background(Brush.linearGradient(listOf(accent.deep, GlacierColors.Void))),
            contentAlignment = Alignment.Center,
        ) {
            Text(name.take(1).uppercase(), style = GlacierText.display(38), color = GlacierColors.Ice)
            if (imageUrl != null) Artwork(imageUrl, Modifier.fillMaxSize())
        }
        Text(
            name,
            style = GlacierText.body(17, FontWeight.SemiBold),
            color = if (focused) accent.main else GlacierColors.Ice,
            textAlign = TextAlign.Center,
            maxLines = 2,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        )
        role?.let {
            Text(it, style = GlacierText.body(15), color = GlacierColors.Mist, textAlign = TextAlign.Center, maxLines = 2, modifier = Modifier.fillMaxWidth())
        }
    }
}
