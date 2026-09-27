package io.github.glacier_jellyfin.androidtv.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.data.media.ItemKind
import io.github.glacier_jellyfin.androidtv.core.data.media.Library
import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryKind
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaItem
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.glacier_jellyfin.androidtv.core.designsystem.PillButton
import io.github.glacier_jellyfin.androidtv.core.designsystem.SpinningDiamond
import io.github.glacier_jellyfin.androidtv.ui.CardSize
import io.github.glacier_jellyfin.androidtv.ui.CollectEvents
import io.github.glacier_jellyfin.androidtv.ui.ContinueCard
import io.github.glacier_jellyfin.androidtv.ui.LibraryCard
import io.github.glacier_jellyfin.androidtv.ui.NavTarget
import io.github.glacier_jellyfin.androidtv.ui.PosterCard
import io.github.glacier_jellyfin.androidtv.ui.TopNav
import io.github.glacier_jellyfin.androidtv.ui.UiEvent
import kotlinx.coroutines.delay

/** Where a focused row settles vertically, and a focused card horizontally (design `sync()`). */
private const val ROW_PIVOT = 330
private const val CARD_PIVOT = 120
private const val EDGE = 80

@OptIn(ExperimentalFoundationApi::class) // LocalBringIntoViewSpec
@Composable
fun HomeScreen(
    onNavigate: (UiEvent.Navigate) -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    CollectEvents(viewModel.events, onNavigate)

    val playFocus = remember { FocusRequester() }
    var spotIndex by remember { mutableIntStateOf(0) }
    var spotlightFocused by remember { mutableStateOf(false) }
    val spotlight = state.spotlight

    // Rotate the spotlight, pausing while focus is inside it; a manual pick restarts the timer.
    LaunchedEffect(spotlight.size, spotlightFocused, spotIndex, state.settings.rotateSeconds) {
        val seconds = state.settings.rotateSeconds
        if (spotlight.size < 2 || spotlightFocused || seconds <= 0) return@LaunchedEffect
        delay(seconds * 1000L)
        spotIndex = (spotIndex + 1) % spotlight.size
    }
    // Focus "Play" once, when the spotlight first appears (not when returning later).
    var initialFocusDone by rememberSaveable { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        val content = state.content
        when {
            state.loading && content == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { SpinningDiamond(110) }
            state.failed && content == null -> ErrorState(onRetry = viewModel::load)
            content != null -> {
                val listState = rememberLazyListState()
                CompositionLocalProvider(LocalBringIntoViewSpec provides rememberRowPivotSpec(listState)) {
                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        item(key = "spotlight") {
                            if (spotlight.isNotEmpty()) {
                                Spotlight(
                                    items = spotlight,
                                    index = spotIndex.coerceIn(0, spotlight.lastIndex),
                                    onSelect = { spotIndex = it },
                                    onPlay = viewModel::play,
                                    onInfo = viewModel::openDetails,
                                    onFavorite = viewModel::toggleFavorite,
                                    playFocus = playFocus,
                                    modifier = Modifier.onFocusChanged { spotlightFocused = it.hasFocus },
                                )
                                LaunchedEffect(Unit) {
                                    if (!initialFocusDone) {
                                        // Wait one frame so the button is attached before focusing it.
                                        withFrameNanos { }
                                        initialFocusDone = runCatching { playFocus.requestFocus() }.isSuccess
                                    }
                                }
                            } else {
                                Box(Modifier.padding(top = 160.dp))
                            }
                        }
                        if (content.continueWatching.isNotEmpty()) {
                            item(key = "continue") {
                                MediaRow(title = stringResource(R.string.home_continue_watching)) {
                                    items(content.continueWatching, key = { it.id }) { item ->
                                        ContinueCard(
                                            title = if (item.kind == ItemKind.Episode) item.parentTitle ?: item.title else item.title,
                                            subtitle = continueSubtitle(item),
                                            imageUrl = item.thumbUrl ?: item.posterUrl,
                                            progress = item.progress,
                                            onClick = { viewModel.openContinueWatching(item) },
                                        )
                                    }
                                }
                            }
                        }
                        content.latest.forEach { (library, items) ->
                            item(key = "latest-${library.id}") {
                                MediaRow(
                                    title = stringResource(R.string.home_new_in, library.name),
                                    subtitle = libraryCount(library),
                                ) {
                                    items(items, key = { it.id }) { item -> PosterFor(item, onClick = { viewModel.openDetails(item) }) }
                                }
                            }
                        }
                        if (content.libraries.isNotEmpty()) {
                            item(key = "libraries") {
                                MediaRow(title = stringResource(R.string.home_my_media), bottomPadding = 90) {
                                    items(content.libraries, key = { it.id }) { library ->
                                        // Jellyfin's generated library images carry the name already;
                                        // a backdrop from the library reads like the design's photo.
                                        val newest = content.latest.firstOrNull { it.first.id == library.id }?.second?.firstOrNull()
                                        LibraryCard(
                                            name = library.name,
                                            count = libraryCount(library),
                                            imageUrl = newest?.backdropUrl ?: newest?.posterUrl ?: library.imageUrl,
                                            onClick = { viewModel.openLibrary(library) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        TopNav(
            active = NavTarget.Home,
            kinds = state.kinds,
            userName = state.userName,
            onSelect = viewModel::onNav,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 34.dp),
        )
    }
}

/**
 * Vertical scrolling of the home list: anything in the spotlight scrolls the
 * page back to the top; a focused row settles with its cards [ROW_PIVOT] from the top.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun rememberRowPivotSpec(listState: LazyListState): BringIntoViewSpec {
    val density = LocalDensity.current
    return remember(listState, density) {
        val pivot = with(density) { ROW_PIVOT.dp.toPx() }
        val spotlight = with(density) { SpotlightHeight.dp.toPx() }
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                if (listState.firstVisibleItemIndex == 0) {
                    val scrolled = listState.firstVisibleItemScrollOffset.toFloat()
                    if (offset + scrolled < spotlight) return -scrolled
                }
                return offset - pivot
            }
        }
    }
}

/** Horizontal counterpart: the focused card settles [CARD_PIVOT] from the left edge. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun rememberCardPivotSpec(): BringIntoViewSpec {
    val density = LocalDensity.current
    return remember(density) {
        val pivot = with(density) { CARD_PIVOT.dp.toPx() }
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = offset - pivot
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaRow(
    title: String,
    subtitle: String? = null,
    bottomPadding: Int = 46,
    content: LazyListScope.() -> Unit,
) {
    // The row's 26px vertical content padding leaves room for focus scale and ring;
    // pulling it up by 8px keeps the design's 18px between title and cards.
    Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = (bottomPadding - 26).dp)) {
        Row(
            Modifier.padding(start = EDGE.dp, end = EDGE.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(title, style = GlacierText.display(28), color = GlacierColors.Ice)
            if (subtitle != null) Text(subtitle, style = GlacierText.body(17), color = GlacierColors.Mist)
        }
        CompositionLocalProvider(LocalBringIntoViewSpec provides rememberCardPivotSpec()) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = EDGE.dp, vertical = 26.dp),
                horizontalArrangement = Arrangement.spacedBy(CardSize.ROW_GAP.dp),
                modifier = Modifier.offset(y = (-8).dp),
                content = content,
            )
        }
    }
}

@Composable
private fun PosterFor(item: MediaItem, onClick: () -> Unit) {
    if (item.kind == ItemKind.Album) {
        PosterCard(
            imageUrl = item.posterUrl,
            caption = item.parentTitle.orEmpty(),
            title = item.title,
            square = true,
            onClick = onClick,
        )
    } else {
        PosterCard(
            imageUrl = item.posterUrl,
            caption = listOfNotNull(item.year?.toString(), item.genres.firstOrNull()).joinToString(" · ").ifEmpty { item.title },
            badge = item.unwatchedCount,
            onClick = onClick,
        )
    }
}

@Composable
private fun continueSubtitle(item: MediaItem): String {
    val season = item.seasonNumber
    val episode = item.episodeNumber
    val left = item.remainingMinutes
    return when {
        item.kind == ItemKind.Episode && season != null && episode != null && left != null ->
            stringResource(R.string.episode_minutes_left, season, episode, left)
        item.kind == ItemKind.Episode && season != null && episode != null ->
            listOfNotNull(stringResource(R.string.episode_short, season, episode), item.title).joinToString(" · ")
        left != null -> stringResource(R.string.minutes_left, left)
        else -> item.year?.toString().orEmpty()
    }
}

@Composable
private fun libraryCount(library: Library): String? {
    val count = library.itemCount ?: return null
    return if (library.kind == LibraryKind.Music) {
        pluralStringResource(R.plurals.count_albums, count, count)
    } else {
        pluralStringResource(R.plurals.count_titles, count, count)
    }
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
        Text(stringResource(R.string.home_error), style = GlacierText.display(30), color = GlacierColors.Ice)
        PillButton(stringResource(R.string.action_retry), onClick = onRetry, primary = true, modifier = Modifier.focusRequester(focus))
    }
}
