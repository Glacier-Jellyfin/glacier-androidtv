package io.github.glacier_jellyfin.androidtv.detail

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.data.media.ItemDetails
import io.github.glacier_jellyfin.androidtv.core.data.media.ItemKind
import io.github.glacier_jellyfin.androidtv.core.data.media.Languages
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaItem
import io.github.glacier_jellyfin.androidtv.core.data.media.channelLayout
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierIcons
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierShapes
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.glacier_jellyfin.androidtv.core.designsystem.LocalAccent
import io.github.glacier_jellyfin.androidtv.core.designsystem.PillButton
import io.github.glacier_jellyfin.androidtv.core.designsystem.PillShape
import io.github.glacier_jellyfin.androidtv.core.designsystem.SpinningDiamond
import io.github.glacier_jellyfin.androidtv.ui.ActionButton
import io.github.glacier_jellyfin.androidtv.ui.Artwork
import io.github.glacier_jellyfin.androidtv.ui.CollectEvents
import io.github.glacier_jellyfin.androidtv.ui.ComingSoonScreen
import io.github.glacier_jellyfin.androidtv.ui.DetailBackdrop
import io.github.glacier_jellyfin.androidtv.ui.FactsRow
import io.github.glacier_jellyfin.androidtv.ui.FilterChip
import io.github.glacier_jellyfin.androidtv.ui.GridCard
import io.github.glacier_jellyfin.androidtv.ui.MediaRow
import io.github.glacier_jellyfin.androidtv.ui.PageEdge
import io.github.glacier_jellyfin.androidtv.ui.ProgressBar
import io.github.glacier_jellyfin.androidtv.ui.UiEvent
import io.github.glacier_jellyfin.androidtv.ui.qualityText
import io.github.glacier_jellyfin.androidtv.ui.rememberRowPivotSpec
import io.github.glacier_jellyfin.androidtv.ui.runtimeText
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.time.format.FormatStyle

private const val MEDIA_BACKDROP = 760
private const val EPISODE_BACKDROP = 720

/** Height of the part above the first row; focus there scrolls the page to the top. */
private const val HEADER_REGION = 700

@Composable
fun DetailScreen(
    onNavigate: (UiEvent.Navigate) -> Unit,
    onBack: () -> Unit,
    viewModel: DetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    CollectEvents(viewModel.events, onNavigate)
    val details = state.details

    Box(Modifier.fillMaxSize()) {
        when {
            details == null && state.failed -> ErrorState(onRetry = viewModel::load)
            details == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { SpinningDiamond(110) }
            details.item.kind == ItemKind.Movie || details.item.kind == ItemKind.Series -> MediaDetail(state, details, viewModel)
            details.item.kind == ItemKind.Episode -> EpisodeDetail(state, details, viewModel)
            // Collections come with the next step, albums with the music section.
            else -> ComingSoonScreen(details.item.title, onBack = onBack)
        }
        val panel = state.trackPanel
        if (panel != null && details != null) TrackSheet(panel, state, details, viewModel)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaDetail(state: DetailState, details: ItemDetails, viewModel: DetailViewModel) {
    val item = details.item
    val listState = rememberLazyListState()
    val playFocus = remember { FocusRequester() }
    var initialFocusDone by rememberSaveable(item.id) { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        ScrollingBackdrop(item.backdropUrl, MEDIA_BACKDROP, listState)
        CompositionLocalProvider(LocalBringIntoViewSpec provides rememberRowPivotSpec(listState, HEADER_REGION)) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                item(key = "header") {
                    Column(
                        Modifier.padding(start = PageEdge.dp, end = PageEdge.dp, top = 130.dp),
                        verticalArrangement = Arrangement.spacedBy(22.dp),
                    ) {
                        Crumb(
                            listOfNotNull(
                                stringResource(if (item.kind == ItemKind.Series) R.string.library_shows else R.string.library_movies),
                                state.season?.number?.let { stringResource(R.string.season_number, it) }.takeIf { item.kind == ItemKind.Series },
                            ).joinToString(" · "),
                        )
                        Title(item.title, size = 84, maxWidth = 1180)
                        FactsRow(item, mediaFacts(details), tech = techLine(details))
                        item.overview?.let { Overview(it, maxWidth = 900) }
                        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            PlayButtons(state, item, viewModel, playFocus)
                            ActionButton(
                                onClick = viewModel::toggleFavorite,
                                icon = if (item.isFavorite) GlacierIcons.HeartFilled else GlacierIcons.Heart,
                                on = item.isFavorite,
                                contentDescription = stringResource(if (item.isFavorite) R.string.action_unfavorite else R.string.action_favorite),
                            )
                            SeenButton(item.played, onClick = viewModel::togglePlayed)
                            if (details.trailers.any) ActionButton(onClick = viewModel::playTrailer, label = stringResource(R.string.detail_trailer))
                            if (item.kind == ItemKind.Movie) TrackChips(state, details, viewModel)
                        }
                    }
                    LaunchedEffect(item.id) {
                        if (!initialFocusDone) {
                            withFrameNanos { }
                            initialFocusDone = runCatching { playFocus.requestFocus() }.isSuccess
                        }
                    }
                }
                if (item.kind == ItemKind.Series && state.seasons.isNotEmpty()) {
                    item(key = "seasons") {
                        SeasonChips(state, viewModel)
                    }
                    item(key = "episodes") {
                        EpisodeRow(
                            episodes = state.episodes,
                            focusIndex = state.focusEpisode,
                            currentId = null,
                            onClick = viewModel::openEpisode,
                            modifier = Modifier.padding(top = 4.dp, bottom = 20.dp),
                        )
                    }
                }
                if (details.cast.isNotEmpty()) {
                    item(key = "cast") {
                        MediaRow(title = stringResource(R.string.detail_cast), gap = 30) {
                            items(details.cast, key = { it.id }) { person -> CastCard(person, onClick = { viewModel.openPerson(person) }) }
                        }
                    }
                }
                if (state.similar.isNotEmpty()) {
                    item(key = "similar") {
                        MediaRow(title = stringResource(R.string.detail_similar), bottomPadding = 120) {
                            items(state.similar, key = { it.id }) { similar ->
                                GridCard(
                                    imageUrl = similar.posterUrl,
                                    title = similar.title,
                                    caption = similar.year?.toString(),
                                    onClick = { viewModel.openItem(similar) },
                                    watched = similar.played,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EpisodeDetail(state: DetailState, details: ItemDetails, viewModel: DetailViewModel) {
    val episode = details.item
    val listState = rememberLazyListState()
    val playFocus = remember { FocusRequester() }
    val locale = LocalConfiguration.current.locales[0]

    Box(Modifier.fillMaxSize()) {
        ScrollingBackdrop(episode.backdropUrl, EPISODE_BACKDROP, listState)
        CompositionLocalProvider(LocalBringIntoViewSpec provides rememberRowPivotSpec(listState, HEADER_REGION)) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                item(key = "header") {
                    Row(
                        Modifier.padding(start = PageEdge.dp, end = PageEdge.dp, top = 126.dp),
                        horizontalArrangement = Arrangement.spacedBy(56.dp),
                    ) {
                        EpisodeStill(episode)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                            val season = episode.seasonNumber
                            val number = episode.episodeNumber
                            Crumb(
                                if (season != null && number != null) {
                                    stringResource(R.string.episode_crumb, episode.parentTitle.orEmpty(), season, number)
                                } else {
                                    episode.parentTitle.orEmpty()
                                },
                            )
                            Title(episode.title, size = 64, maxWidth = 1000)
                            val aired = details.premiereDate?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
                            FactsRow(
                                episode,
                                listOfNotNull(episode.runtimeMinutes?.takeIf { it > 0 }?.let { runtimeText(it) }, aired),
                                tech = techLine(details),
                                size = 19,
                            )
                            episode.overview?.let { Overview(it, maxWidth = 760) }
                            val progress = episode.progress
                            val left = episode.remainingMinutes
                            if (progress != null && left != null) {
                                Text(
                                    stringResource(R.string.episode_progress, (progress * 100).toInt(), left),
                                    style = GlacierText.body(18, FontWeight.SemiBold),
                                    color = LocalAccent.current.main,
                                )
                            }
                            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                PlayButtons(state, episode, viewModel, playFocus, episodeLabel = true)
                                SeenButton(episode.played, onClick = viewModel::togglePlayed)
                                ActionButton(
                                    onClick = viewModel::toggleFavorite,
                                    icon = if (episode.isFavorite) GlacierIcons.HeartFilled else GlacierIcons.Heart,
                                    on = episode.isFavorite,
                                    contentDescription = stringResource(if (episode.isFavorite) R.string.action_unfavorite else R.string.action_favorite),
                                )
                                TrackChips(state, details, viewModel)
                            }
                        }
                    }
                    LaunchedEffect(episode.id) {
                        withFrameNanos { }
                        runCatching { playFocus.requestFocus() }
                    }
                }
                if (state.episodes.size > 1) {
                    item(key = "more") {
                        Text(
                            stringResource(R.string.episode_more, episode.seasonNumber ?: 1),
                            style = GlacierText.display(28),
                            color = GlacierColors.Ice,
                            modifier = Modifier.padding(start = PageEdge.dp, top = 44.dp),
                        )
                        EpisodeRow(
                            episodes = state.episodes,
                            focusIndex = state.focusEpisode,
                            currentId = episode.id,
                            onClick = viewModel::showEpisode,
                            modifier = Modifier.padding(bottom = 70.dp),
                        )
                    }
                }
            }
        }
    }
}

/** The backdrop scrolls away with the page, as it sits at the top of the design's scroll container. */
@Composable
private fun ScrollingBackdrop(url: String?, height: Int, listState: LazyListState) {
    DetailBackdrop(
        url = url,
        height = height,
        modifier = Modifier.graphicsLayer {
            translationY = if (listState.firstVisibleItemIndex == 0) -listState.firstVisibleItemScrollOffset.toFloat() else -size.height
        },
    )
}

@Composable
private fun Crumb(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(GlacierIcons.ChevronLeft, contentDescription = null, tint = GlacierColors.Mist, modifier = Modifier.size(16.dp))
        Text(text.uppercase(), style = GlacierText.body(17).copy(letterSpacing = 0.06.em), color = GlacierColors.Mist)
    }
}

@Composable
private fun Title(text: String, size: Int, maxWidth: Int) {
    Text(
        text,
        style = GlacierText.display(size).copy(lineHeight = (size * 1.03).sp, shadow = Shadow(Color(0x99000000), blurRadius = 30f)),
        color = GlacierColors.Ice,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.widthIn(max = maxWidth.dp),
    )
}

@Composable
private fun Overview(text: String, maxWidth: Int) {
    Text(
        text,
        style = GlacierText.body(21).copy(lineHeight = 34.sp),
        color = GlacierColors.Ice,
        maxLines = 4,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.widthIn(max = maxWidth.dp),
    )
}

/** Play or resume, plus "From start" when there is progress (design `detBtnData`). */
@Composable
private fun PlayButtons(
    state: DetailState,
    item: MediaItem,
    viewModel: DetailViewModel,
    focus: FocusRequester,
    episodeLabel: Boolean = false,
) {
    // A show resumes its next episode.
    val target = if (item.kind == ItemKind.Series) state.nextEpisode else item
    val left = target?.remainingMinutes?.takeIf { target.progress != null }
    ActionButton(
        onClick = { viewModel.play() },
        label = when {
            left != null -> stringResource(R.string.detail_resume_left, left)
            episodeLabel -> stringResource(R.string.episode_play)
            else -> stringResource(R.string.hero_play)
        },
        icon = GlacierIcons.Play,
        primary = true,
        modifier = Modifier.focusRequester(focus),
    )
    if (left != null && item.kind != ItemKind.Series) {
        ActionButton(onClick = { viewModel.play(fromStart = true) }, label = stringResource(R.string.detail_from_start))
    }
}

@Composable
private fun SeenButton(played: Boolean, onClick: () -> Unit) {
    ActionButton(
        onClick = onClick,
        icon = if (played) GlacierIcons.SeenFilled else GlacierIcons.Seen,
        iconMark = if (played) GlacierIcons.SeenMark else null,
        on = played,
        contentDescription = stringResource(if (played) R.string.action_mark_unwatched else R.string.action_mark_watched),
    )
}

@Composable
private fun TrackChips(state: DetailState, details: ItemDetails, viewModel: DetailViewModel) {
    val tracks = details.tracks ?: return
    ActionDivider()
    if (tracks.audio.isNotEmpty()) {
        val (label, flag) = trackChipLabel(tracks.audio.firstOrNull { it.index == state.selection?.audio }, subtitle = false)
        TrackChip(label, flag, audio = true, onClick = { viewModel.openTracks(TrackKind.Audio) })
    }
    val (label, flag) = trackChipLabel(tracks.subtitles.firstOrNull { it.index == state.selection?.subtitle }, subtitle = true)
    TrackChip(label, flag, audio = false, onClick = { viewModel.openTracks(TrackKind.Subtitles) })
}

@Composable
private fun TrackSheet(kind: TrackKind, state: DetailState, details: ItemDetails, viewModel: DetailViewModel) {
    val tracks = details.tracks ?: return
    val audio = kind == TrackKind.Audio
    val list = if (audio) tracks.audio else tracks.subtitles
    // Subtitles start with "Off".
    val options = (if (audio) emptyList() else listOf(stringResource(R.string.track_off) to null)) +
        list.map { track ->
            trackLabel(track, subtitle = !audio) to Languages.iso3(track.language)?.takeIf { it in setOf("deu", "eng", "jpn") }
        }
    val indices: List<Int?> = (if (audio) emptyList() else listOf(null)) + list.map { it.index }
    val current = if (audio) state.selection?.audio else state.selection?.subtitle
    val item = details.item
    val prefix = if (item.kind == ItemKind.Episode && item.episodeNumber != null) {
        stringResource(R.string.episode_badge, item.episodeNumber!!) + " · " + item.title
    } else {
        item.title
    }
    TrackPanel(
        title = stringResource(if (audio) R.string.track_audio else R.string.track_subtitles),
        subtitle = prefix + " · " + stringResource(R.string.track_next_playback),
        options = options,
        selected = indices.indexOf(current).coerceAtLeast(0),
        onPick = { i -> viewModel.pickTrack(kind, indices[i], options[i].first) },
        onDismiss = viewModel::closeTracks,
    )
}

@Composable
private fun SeasonChips(state: DetailState, viewModel: DetailViewModel) {
    val active = remember { FocusRequester() }
    Row(
        Modifier
            .padding(start = PageEdge.dp, end = PageEdge.dp, top = 54.dp, bottom = 20.dp)
            .focusProperties { onEnter = { active.requestFocus() } }
            .focusGroup(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        state.seasons.forEach { season ->
            val selected = season.id == state.season?.id
            FilterChip(
                label = season.number?.let { stringResource(R.string.season_number, it) } ?: season.name,
                active = selected,
                onClick = { viewModel.selectSeason(season) },
                height = 50,
                fontSize = 18,
                modifier = if (selected) Modifier.focusRequester(active) else Modifier,
            )
        }
    }
}

/** Episodes of a season; entering the row lands on [focusIndex] (the first unwatched one). */
@Composable
private fun EpisodeRow(
    episodes: List<MediaItem>,
    focusIndex: Int,
    currentId: UUID?,
    onClick: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rowState = rememberLazyListState()
    val target = remember { FocusRequester() }
    LaunchedEffect(episodes, focusIndex) {
        if (episodes.isNotEmpty()) rowState.scrollToItem(focusIndex.coerceIn(0, episodes.lastIndex))
    }
    MediaRow(
        title = null,
        gap = 26,
        state = rowState,
        bottomPadding = 0,
        modifier = modifier
            .focusProperties { onEnter = { target.requestFocus() } }
            .focusGroup(),
    ) {
        itemsIndexed(episodes, key = { _, episode -> episode.id }) { index, episode ->
            EpisodeCard(
                episode = episode,
                onClick = { onClick(episode) },
                current = episode.id == currentId,
                modifier = if (index == focusIndex) Modifier.focusRequester(target) else Modifier,
            )
        }
    }
}

@Composable
private fun EpisodeStill(episode: MediaItem) {
    val accent = LocalAccent.current.main
    val shape = RoundedCornerShape(GlacierShapes.RadiusMd)
    Box(
        Modifier
            .size(620.dp, 349.dp)
            .clip(shape)
            .border(1.dp, GlacierColors.GlassBorder2, shape),
    ) {
        Artwork(episode.thumbUrl, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.42f to Color.Transparent, 1f to Color(0xB805090F))))
        episode.episodeNumber?.let {
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 20.dp, top = 18.dp)
                    .height(36.dp)
                    .clip(PillShape)
                    .background(GlacierColors.GlassFill2)
                    .border(1.dp, GlacierColors.GlassBorder2, PillShape)
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.episode_badge, it), style = GlacierText.body(16, FontWeight.Bold), color = GlacierColors.Ice)
            }
        }
        if (episode.played) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(end = 20.dp, top = 18.dp).size(36.dp).clip(PillShape).background(accent),
                contentAlignment = Alignment.Center,
            ) {
                Icon(GlacierIcons.Check, contentDescription = null, tint = GlacierColors.Void, modifier = Modifier.size(20.dp))
            }
        }
        episode.progress?.let {
            ProgressBar(it, track = Color(0x42E8F4F7), modifier = Modifier.align(Alignment.BottomCenter).padding(start = 20.dp, end = 20.dp, bottom = 20.dp).height(6.dp))
        }
    }
}

/** Year · runtime (movies) or seasons and episodes (shows) · genre. */
@Composable
private fun mediaFacts(details: ItemDetails): List<String> {
    val item = details.item
    val middle = if (item.kind == ItemKind.Series) {
        listOfNotNull(
            details.seasonCount?.let { pluralStringResource(R.plurals.count_seasons, it, it) },
            details.episodeCount?.let { pluralStringResource(R.plurals.count_episodes, it, it) },
        ).joinToString(" · ").ifEmpty { null }
    } else {
        item.runtimeMinutes?.takeIf { it > 0 }?.let { runtimeText(it) }
    }
    return listOfNotNull(item.year?.toString(), middle, item.genres.firstOrNull())
}

/** Design `techLine`: "4K HDR · DE/JP · 5.1". */
private fun techLine(details: ItemDetails): String? {
    val audio = details.tracks?.audio.orEmpty()
    val languages = audio.mapNotNull { Languages.iso2(it.language)?.uppercase() }
        .distinct()
        .take(3)
        .joinToString("/")
        .ifEmpty { null }
    val channels = channelLayout(audio.maxOfOrNull { it.channels ?: 0 })
    return listOfNotNull(qualityText(details.item.quality), languages, channels).joinToString(" · ").ifEmpty { null }
}

@Composable
private fun ErrorState(onRetry: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterVertically),
    ) {
        Text(stringResource(R.string.detail_error), style = GlacierText.display(30), color = GlacierColors.Ice)
        PillButton(stringResource(R.string.action_retry), onClick = onRetry, primary = true, modifier = Modifier.focusRequester(focus))
    }
}
