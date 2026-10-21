package io.github.glacier_jellyfin.androidtv.library

import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.data.media.AlphabetLetters
import io.github.glacier_jellyfin.androidtv.core.data.media.ItemKind
import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryKind
import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryScope
import io.github.glacier_jellyfin.androidtv.core.data.media.ListGroup
import io.github.glacier_jellyfin.androidtv.core.data.media.LibrarySort
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaItem
import io.github.glacier_jellyfin.androidtv.core.data.media.NameOrder
import io.github.glacier_jellyfin.androidtv.core.data.media.railLetter
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierIcons
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierShapes
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.glacier_jellyfin.androidtv.core.designsystem.LocalAccent
import io.github.glacier_jellyfin.androidtv.core.designsystem.PillButton
import io.github.glacier_jellyfin.androidtv.core.designsystem.PillShape
import io.github.glacier_jellyfin.androidtv.core.designsystem.SpinningDiamond
import io.github.glacier_jellyfin.androidtv.core.designsystem.focusScale
import io.github.glacier_jellyfin.androidtv.music.MusicActionsSheet
import io.github.glacier_jellyfin.androidtv.player.formatTime
import io.github.glacier_jellyfin.androidtv.ui.CardShape
import io.github.glacier_jellyfin.androidtv.ui.CollectEvents
import io.github.glacier_jellyfin.androidtv.ui.FilterChip
import io.github.glacier_jellyfin.androidtv.ui.GridCard
import io.github.glacier_jellyfin.androidtv.ui.LocalCardSizes
import io.github.glacier_jellyfin.androidtv.ui.LocalLibraryKinds
import io.github.glacier_jellyfin.androidtv.ui.NavTarget
import io.github.glacier_jellyfin.androidtv.ui.SONG_ROW_GAP
import io.github.glacier_jellyfin.androidtv.ui.SongRow
import io.github.glacier_jellyfin.androidtv.ui.ToggleChip
import io.github.glacier_jellyfin.androidtv.ui.TopNav
import io.github.glacier_jellyfin.androidtv.ui.UiEvent
import io.github.glacier_jellyfin.androidtv.ui.ratingText
import io.github.glacier_jellyfin.androidtv.ui.runtimeText
import io.github.glacier_jellyfin.androidtv.ui.showsLock
import io.github.glacier_jellyfin.androidtv.ui.title
import io.github.glacier_jellyfin.androidtv.ui.yearText
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlinx.coroutines.launch

private const val HEADER_ITEMS = 2
private const val ROW_PIVOT = 330
private const val ROW_TOLERANCE = 48
private const val RAIL_TOP = 150

@OptIn(ExperimentalFoundationApi::class) // LocalBringIntoViewSpec
@Composable
fun LibraryScreen(
    onNavigate: (UiEvent.Navigate) -> Unit,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    CollectEvents(viewModel.events, onNavigate)
    val sizes = LocalCardSizes.current
    val density = LocalDensity.current

    val gridState = rememberLazyGridState()
    val firstCardFocus = remember { FocusRequester() }
    val jumpFocus = remember { FocusRequester() }
    val railFocus = remember { FocusRequester() }
    val gridFocus = remember { FocusRequester() }
    var initialFocusDone by rememberSaveable { mutableStateOf(false) }
    // Back from a detail page the screen is composed anew; focus goes back to the card the
    // user left from (the grid keeps its scroll), not to the nav on top.
    var lastFocusId by rememberSaveable { mutableStateOf<String?>(null) }
    // Where the first row of cards sits with the grid at its top; the A–Z rail stays level with it.
    var railTop by rememberSaveable { mutableIntStateOf(-1) }
    LaunchedEffect(gridState) {
        snapshotFlow { firstRowHome(gridState) }.collect { top -> if (top != null) railTop = top }
    }
    val restoreFocus = remember { FocusRequester() }
    // By id: titles loaded in before them (on the way back up after a jump) move them down.
    var focusedId by remember { mutableStateOf<String?>(null) }
    var jumpTarget by remember { mutableStateOf<String?>(null) }
    var sortAnchor by remember { mutableStateOf<Rect?>(null) }
    // The menu takes the focus with it when it closes; it goes back to the sort button.
    val sortButtonFocus = remember { FocusRequester() }
    var sortMenuWasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(state.sortMenuOpen) {
        if (state.sortMenuOpen) {
            sortMenuWasOpen = true
        } else if (sortMenuWasOpen) {
            sortMenuWasOpen = false
            withFrameNanos { }
            runCatching { sortButtonFocus.requestFocus() }
        }
    }
    // A song's options sheet takes the focus with it when it closes; it goes back to the song.
    val sheet by viewModel.musicActions.sheet.collectAsStateWithLifecycle()
    var sheetWasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(sheet == null) {
        if (sheet != null) {
            sheetWasOpen = true
        } else if (sheetWasOpen) {
            sheetWasOpen = false
            withFrameNanos { }
            runCatching { restoreFocus.requestFocus() }
        }
    }
    val songsTitle = stringResource(R.string.scope_songs)
    // Songs are a one-column list: title, artist and album need the width.
    val songList = state.query.scope == LibraryScope.Songs
    val columns = if (songList) 1 else sizes.gridColumns
    // Where each title sits in the grid: after the title and toolbar, and after the heading of its group and those before.
    val positions = remember(state.items) { gridPositions(state.items) }
    val gridCount = HEADER_ITEMS + state.items.size + (positions.lastOrNull()?.let { it - HEADER_ITEMS - state.items.lastIndex } ?: 0)
    val showRail = state.query.railed && state.letters.isNotEmpty()
    // Back among the cards or on the A–Z rail first goes up to the tabs. Pressed again, it leaves the page.
    val activeChip = remember { FocusRequester() }
    var inContent by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    BackHandler(enabled = inContent) {
        scope.launch {
            // After a jump the list starts at the letter: under the toolbar it starts at the top again.
            viewModel.toStart()
            // The toolbar is a grid item and not composed while the grid is scrolled down.
            gridState.scrollToItem(0)
            withFrameNanos { }
            runCatching { activeChip.requestFocus() }.onFailure { runCatching { sortButtonFocus.requestFocus() } }
        }
    }

    // Load the next page when the grid gets within three rows of the end.
    LaunchedEffect(gridState, gridCount, songList) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { last -> if (last >= gridCount - (if (songList) SONG_LOOKAHEAD else 3 * columns)) viewModel.loadMore() }
    }

    // Once per composition: a filter change later empties and refills the grid, and the
    // focus stays on the chip then.
    var focusPlaced by remember { mutableStateOf(false) }
    LaunchedEffect(state.items.isNotEmpty()) {
        if (state.items.isEmpty() || focusPlaced) return@LaunchedEffect
        focusPlaced = true
        withFrameNanos { }
        if (!initialFocusDone) {
            initialFocusDone = runCatching { firstCardFocus.requestFocus() }.isSuccess
            return@LaunchedEffect
        }
        val index = state.items.indexOfFirst { it.id.toString() == lastFocusId }
        if (index < 0) {
            runCatching { firstCardFocus.requestFocus() }
            return@LaunchedEffect
        }
        if (gridState.layoutInfo.visibleItemsInfo.none { it.index == positions[index] }) {
            gridState.scrollToItem(positions[index])
            withFrameNanos { }
        }
        runCatching { restoreFocus.requestFocus() }
    }
    LaunchedEffect(state.jumpTo) {
        val index = state.jumpTo ?: return@LaunchedEffect
        // Only the grid moves: the focus stays on the rail so the user can pick the next
        // letter, and Left then enters the grid at the first title of this one.
        jumpTarget = state.items.getOrNull(index)?.id?.toString()
        focusedId = jumpTarget
        // A group's first title: its heading comes into view with it.
        val position = positions.getOrNull(index)?.let { if (startsGroup(state.items, index)) it - 1 else it } ?: return@LaunchedEffect
        gridState.scrollToItem(position)
        withFrameNanos { }
        // Settle the row where focused rows sit, not under the nav.
        val row = gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == position }
        if (row != null) {
            val top = row.offset.y - gridState.layoutInfo.viewportStartOffset
            gridState.scrollBy((top - with(density) { ROW_PIVOT.dp.toPx() }))
        }
        viewModel.jumpHandled()
    }

    Box(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize()) {
            CompositionLocalProvider(LocalBringIntoViewSpec provides rememberGridPivotSpec(gridState)) {
                LazyVerticalGrid(
                    state = gridState,
                    // Cells as wide as the cards, packed from the left like the design's rows.
                    // The song list is as wide as the cards' rows, so the toolbar ends in the same place on every tab.
                    columns = GridCells.FixedSize(if (songList) (sizes.gridColumns * sizes.posterWidth + (sizes.gridColumns - 1) * sizes.gridGap).dp else sizes.posterWidth.dp),
                    // Without the A–Z rail the list keeps its width, so the toolbar and songs stay where they are.
                    contentPadding = PaddingValues(start = 80.dp, end = if (showRail) 22.dp else RAIL_SPACE.dp, top = 150.dp, bottom = 120.dp),
                    horizontalArrangement = Arrangement.spacedBy(sizes.gridGap.dp),
                    verticalArrangement = Arrangement.spacedBy(if (songList) SONG_ROW_GAP.dp else 38.dp),
                    modifier = Modifier.weight(1f).fillMaxSize().focusRequester(gridFocus).focusRestorer(),
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "title") { Header(state) }
                    item(span = { GridItemSpan(maxLineSpan) }, key = "chips") {
                        val toolbarScope = rememberCoroutineScope()
                        Toolbar(
                            // The song rows sit closer together than cards: the toolbar keeps the cards' distance.
                            modifier = Modifier.padding(vertical = if (songList) (38 - SONG_ROW_GAP).dp else 0.dp).onFocusChanged {
                                if (it.hasFocus) {
                                    inContent = false
                                    toolbarScope.launch { gridState.animateScrollToItem(0) }
                                }
                            },
                            state = state,
                            onScope = viewModel::setScope,
                            onFavoritesOnly = viewModel::toggleFavoritesOnly,
                            onUnwatchedOnly = viewModel::toggleUnwatchedOnly,
                            onSortMenu = viewModel::toggleSortMenu,
                            onSortAnchor = { sortAnchor = it },
                            sortButtonFocus = sortButtonFocus,
                            activeChip = activeChip,
                        )
                    }
                    state.items.forEachIndexed { index, item ->
                        val group = item.group
                        if (group != null && startsGroup(state.items, index)) {
                            item(span = { GridItemSpan(maxLineSpan) }, key = "group-$group") {
                                GroupHeading(group, state.query.sort, cards = !songList)
                            }
                        }
                        item(key = item.id) {
                            val id = item.id.toString()
                            val focus = when {
                                id == jumpTarget -> Modifier.focusRequester(jumpFocus)
                                index == 0 -> Modifier.focusRequester(firstCardFocus)
                                else -> Modifier
                            }.then(if (id == lastFocusId) Modifier.focusRequester(restoreFocus) else Modifier)
                                // A group's last row is often short: Right goes to the rail, not up to the row above.
                                .then(if (showRail && group != null && (index == state.items.lastIndex || startsGroup(state.items, index + 1))) Modifier.focusProperties { right = railFocus } else Modifier)
                            val onFocus = Modifier.onFocusChanged {
                                if (it.isFocused) {
                                    inContent = true
                                    focusedId = id
                                    lastFocusId = id
                                    // After a jump far down the list starts there: the titles before it load on the way back up.
                                    if (state.offset > 0 && index < (if (songList) SONG_LOOKAHEAD else 3 * columns)) viewModel.loadBefore(columns)
                                }
                            }
                            if (item.kind == ItemKind.Song) {
                                SongRow(
                                    title = item.title,
                                    durationMs = item.runtimeMs,
                                    coverUrl = item.posterUrl,
                                    showCover = true,
                                    artist = item.parentTitle,
                                    album = item.album,
                                    showArtist = true,
                                    showAlbum = true,
                                    onClick = { viewModel.play(item, songsTitle) },
                                    onOptions = { viewModel.songOptions(item) },
                                    modifier = focus.then(onFocus),
                                )
                            } else GridCard(
                                imageUrl = item.posterUrl,
                                title = item.title,
                                caption = captionFor(item, sort = state.query.sort.takeUnless { state.query.listedByName }),
                                onClick = { viewModel.open(item) },
                                shape = when (item.kind) {
                                    ItemKind.Album, ItemKind.Playlist -> CardShape.Square
                                    ItemKind.Artist -> CardShape.Round
                                    else -> CardShape.Poster
                                },
                                watched = item.played && item.kind in setOf(ItemKind.Movie, ItemKind.Series, ItemKind.Episode, ItemKind.MusicVideo, ItemKind.Collection),
                                // Collections and shows count what is left to watch; a collection's size is in the caption.
                                count = item.unwatchedCount.takeIf { item.kind == ItemKind.Collection || item.kind == ItemKind.Series },
                                locked = item.showsLock(),
                                modifier = focus.then(onFocus),
                            )
                        }
                    }
                    when {
                        state.failed -> item(span = { GridItemSpan(maxLineSpan) }, key = "error") {
                            // Where the titles would be; below titles already there it only needs its own height.
                            InFreeSpace(gridState, "error", fill = state.items.isEmpty()) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                                    Text(stringResource(R.string.library_error), style = GlacierText.body(21), color = GlacierColors.Mist, textAlign = TextAlign.Center)
                                    PillButton(stringResource(R.string.action_retry), onClick = viewModel::retry, primary = true)
                                }
                            }
                        }
                        state.loading && state.items.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }, key = "loading") {
                            InFreeSpace(gridState, "loading", screenCentred = true) { SpinningDiamond(90) }
                        }
                        state.complete && state.items.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }, key = "empty") {
                            InFreeSpace(gridState, "empty", screenCentred = true) {
                                Text(
                                    stringResource(
                                        when {
                                            state.query.favoritesOnly && state.query.kind == LibraryKind.Music -> R.string.library_empty_music_favorites
                                            state.query.favoritesOnly -> R.string.library_empty_favorites
                                            state.query.unwatchedOnly -> R.string.library_empty_unwatched
                                            else -> R.string.library_empty
                                        },
                                    ),
                                    style = GlacierText.body(21),
                                    color = GlacierColors.Mist,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.width(720.dp),
                                )
                            }
                        }
                        else -> Unit
                    }
                }
            }
            if (showRail) {
                val current = state.items.firstOrNull { it.id.toString() == focusedId }?.let(::letterOf)
                AlphabetRail(
                    letters = state.letters,
                    descending = state.query.orderDescending,
                    current = current,
                    focus = railFocus,
                    onLetter = { viewModel.jumpToLetter(it, columns) },
                    modifier = Modifier
                        .onFocusChanged { if (it.hasFocus) inContent = true }
                        .focusProperties {
                            val target = jumpTarget
                            // Visible items carry the titles' ids as keys.
                            if (target != null && gridState.layoutInfo.visibleItemsInfo.any { it.key.toString() == target }) {
                                left = jumpFocus
                            }
                        }
                        .padding(end = 22.dp)
                        .offset { IntOffset(0, if (railTop >= 0) railTop else RAIL_TOP.dp.roundToPx()) },
                )
            }
        }

        val anchor = sortAnchor
        if (state.sortMenuOpen && anchor != null) {
            SortMenu(
                state = state,
                anchor = anchor,
                onSort = viewModel::setSort,
                onDismiss = { viewModel.toggleSortMenu(false) },
            )
        }

        MusicActionsSheet(viewModel.musicActions)

        TopNav(
            active = NavTarget.Library(state.query.kind),
            kinds = LocalLibraryKinds.current,
            userName = state.userName,
            onSelect = viewModel::onNav,
            down = gridFocus,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 34.dp)
                .onFocusChanged { if (it.hasFocus) inContent = false },
        )
    }
}

/**
 * [content] in the middle of the space the titles would fill: from the grid item [key] down to
 * the grid's bottom edge. Without [fill] just in the middle of its own row. [screenCentred] puts it
 * in the middle of the screen across, wherever the grid ends (with or without the A–Z rail).
 */
@Composable
private fun InFreeSpace(gridState: LazyGridState, key: String, fill: Boolean = true, screenCentred: Boolean = false, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    // Read in a derived state: the layout info changes with every scrolled pixel.
    val height by remember(gridState, key) {
        derivedStateOf {
            val info = gridState.layoutInfo
            val top = info.visibleItemsInfo.firstOrNull { it.key == key }?.offset?.y
            top?.let { with(density) { (info.viewportEndOffset - info.afterContentPadding - it).toDp() } }
        }
    }
    val min = if (fill) LOADER_MIN_HEIGHT.dp else 0.dp
    val screenWidth = LocalWindowInfo.current.containerSize.width
    var shift by remember { mutableFloatStateOf(0f) }
    Box(
        Modifier
            .fillMaxWidth()
            .then(if (fill) Modifier.height((height ?: min).coerceAtLeast(min)) else Modifier)
            .then(
                if (screenCentred) {
                    Modifier.onGloballyPositioned { shift = screenWidth / 2f - (it.positionInRoot().x + it.size.width / 2f) }
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) { Box(Modifier.graphicsLayer { translationX = shift }) { content() } }
}

/** The small heading over a group of a list sorted by something else than the title. */
@Composable
private fun GroupHeading(group: ListGroup, sort: LibrarySort, cards: Boolean) {
    Text(
        groupLabel(group, sort),
        style = GlacierText.body(19, FontWeight.SemiBold),
        color = GlacierColors.Mist,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        // The grid keeps its row gap below the heading too: drawn lower, it sits with its cards.
        modifier = if (cards) Modifier.offset(y = GROUP_HEADING_DROP.dp) else Modifier.padding(start = 24.dp, top = 8.dp),
    )
}

@Composable
private fun groupLabel(group: ListGroup, sort: LibrarySort): String = when (group) {
    is ListGroup.Month -> {
        val locale = LocalLocale.current.platformLocale
        YearMonth.of(group.year, group.month).format(DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "LLLLyyyy"), locale))
    }
    is ListGroup.Year -> group.year.toString()
    is ListGroup.Stars -> stringResource(R.string.group_stars, group.stars)
    is ListGroup.Length ->
        if (group.toMinutes <= 60) stringResource(R.string.group_minutes, group.fromMinutes, group.toMinutes)
        else stringResource(R.string.group_hours, hoursText(group.fromMinutes), hoursText(group.toMinutes))
    is ListGroup.Name -> group.name
    ListGroup.Unknown -> stringResource(
        when (sort) {
            LibrarySort.DateAdded -> R.string.group_unknown_date
            LibrarySort.Year -> R.string.group_unknown_year
            LibrarySort.Rating -> R.string.group_unknown_rating
            LibrarySort.Runtime -> R.string.group_unknown_length
            LibrarySort.Artist -> R.string.group_unknown_artist
            LibrarySort.Album, LibrarySort.Title -> R.string.group_unknown_album
        },
    )
}

/** 90 minutes as "1:30". */
private fun hoursText(minutes: Int): String = "%d:%02d".format(minutes / 60, minutes % 60)

@Composable
private fun Header(state: LibraryState) {
    val title = state.title ?: stringResource(state.query.kind.title)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = GlacierText.display(50), color = GlacierColors.Ice)
        // The sort button already shows sort and order, so the line only counts. It keeps its
        // height while a filter loads, so the chips below do not jump up.
        val total = state.total
        Text(
            if (total != null) countText(state.query.scope, state.query.kind, total) else " ",
            style = GlacierText.body(19),
            color = GlacierColors.Mist,
        )
    }
}

@Composable
private fun Toolbar(
    modifier: Modifier,
    state: LibraryState,
    onScope: (LibraryScope) -> Unit,
    onFavoritesOnly: () -> Unit,
    onUnwatchedOnly: () -> Unit,
    onSortMenu: (Boolean) -> Unit,
    onSortAnchor: (Rect) -> Unit,
    sortButtonFocus: FocusRequester,
    activeChip: FocusRequester,
) {
    Row(
        modifier
            // Moving into the toolbar lands on the active filter, not the geometrically nearest chip.
            // A request for one control (the sort button after its menu closes) is left alone.
            .focusProperties { onEnter = { if (requestedFocusDirection != FocusDirection.Enter) activeChip.requestFocus() } }
            .focusGroup(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        state.scopes.forEach { scope ->
            val active = scope == state.query.scope
            FilterChip(
                label = stringResource(scope.label),
                active = active,
                onClick = { onScope(scope) },
                modifier = if (active) Modifier.focusRequester(activeChip) else Modifier,
            )
        }
        Spacer(Modifier.weight(1f))
        // The filters narrow every tab, so they sit apart from the tabs, by the sort.
        if (state.query.filterable) {
            if (state.query.kind != LibraryKind.Music) {
                ToggleChip(
                    label = stringResource(R.string.scope_unwatched),
                    on = state.query.unwatchedOnly,
                    icon = GlacierIcons.Seen,
                    iconOn = GlacierIcons.Seen,
                    onClick = onUnwatchedOnly,
                )
            }
            ToggleChip(
                label = stringResource(R.string.scope_favorites),
                on = state.query.favoritesOnly,
                icon = GlacierIcons.Heart,
                iconOn = GlacierIcons.HeartFilled,
                onClick = onFavoritesOnly,
            )
        }
        // Genres are always listed by name.
        if (!state.query.listedByName) {
            SortButton(
                state,
                onClick = { onSortMenu(!state.sortMenuOpen) },
                modifier = Modifier.focusRequester(sortButtonFocus).onGloballyPositioned { onSortAnchor(it.boundsInRoot()) },
            )
        }
    }
}

@Composable
private fun SortButton(state: LibraryState, onClick: () -> Unit, modifier: Modifier) {
    val accent = LocalAccent.current.main
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val foreground = if (focused) GlacierColors.Void else GlacierColors.Ice
    Row(
        modifier
            .focusScale(focused)
            .height(52.dp)
            .clip(PillShape)
            .background(if (focused) accent else if (state.sortMenuOpen) accent.copy(alpha = 0.18f) else GlacierColors.GlassFill2)
            .border(2.dp, if (focused) accent else GlacierColors.GlassBorder2, PillShape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(GlacierIcons.SortLines, contentDescription = null, tint = foreground, modifier = Modifier.size(20.dp))
        Text(stringResource(state.query.sort.label), style = GlacierText.body(19, FontWeight.SemiBold), color = foreground)
        Icon(
            GlacierIcons.ArrowDown,
            contentDescription = stringResource(if (state.query.descending) R.string.sort_descending else R.string.sort_ascending),
            tint = foreground,
            modifier = Modifier.size(19.dp).rotate(if (state.query.descending) 0f else 180f),
        )
        Icon(GlacierIcons.ChevronDown, contentDescription = null, tint = foreground, modifier = Modifier.size(18.dp).rotate(if (state.sortMenuOpen) 180f else 0f))
    }
}

/**
 * The design's sort dropdown, 14px below the sort button and right-aligned
 * with it. Drawn in the screen itself rather than a popup window, which would
 * not share the theme's 1920-wide density. Focus stays inside while it is open.
 */
@Composable
private fun SortMenu(state: LibraryState, anchor: Rect, onSort: (LibrarySort) -> Unit, onDismiss: () -> Unit) {
    BackHandler(onBack = onDismiss)
    val density = LocalDensity.current
    val menuWidth = 430.dp
    val firstFocus = remember { FocusRequester() }
    val shape = RoundedCornerShape(GlacierShapes.RadiusMd)
    val x = with(density) { anchor.right.toDp() } - menuWidth
    val y = with(density) { anchor.bottom.toDp() } + 14.dp
    Column(
        Modifier
            .offset(x = x, y = y)
            .width(menuWidth)
            .clip(shape)
            .background(GlacierColors.Deep)
            .border(1.dp, GlacierColors.GlassBorder2, shape)
            .padding(10.dp)
            .focusProperties { onExit = { cancelFocusChange() } }
            .focusGroup(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        state.query.sorts.forEach { sort ->
            SortOption(
                label = stringResource(sort.label),
                active = sort == state.query.sort,
                descending = state.query.descending,
                onClick = { onSort(sort) },
                modifier = if (sort == state.query.sort) Modifier.focusRequester(firstFocus) else Modifier,
            )
        }
    }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
}

@Composable
private fun SortOption(label: String, active: Boolean, descending: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val accent = LocalAccent.current.main
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val foreground = when {
        focused -> GlacierColors.Void
        active -> accent
        else -> GlacierColors.Ice
    }
    Row(
        modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(GlacierShapes.RadiusSm))
            .background(if (focused) accent else if (active) accent.copy(alpha = 0.16f) else Color.Transparent)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = GlacierText.body(19, FontWeight.SemiBold), color = foreground)
        if (active) {
            Icon(GlacierIcons.ArrowDown, contentDescription = null, tint = foreground, modifier = Modifier.size(20.dp).rotate(if (descending) 0f else 180f))
        }
    }
}

/** A–Z rail next to the grid, shown when sorting by title. Letters without titles are dimmed. */
@Composable
private fun AlphabetRail(
    letters: Set<Char>,
    descending: Boolean,
    current: Char?,
    /** On the current letter, else the first one with titles. */
    focus: FocusRequester,
    onLetter: (Char) -> Unit,
    modifier: Modifier = Modifier,
) {
    val order = if (descending) AlphabetLetters.reversed() else AlphabetLetters
    val focused = current?.takeIf { it in letters } ?: order.firstOrNull { it in letters }
    Column(
        // Coming in from any side lands on the current letter, not the nearest one. Before the
        // caller's modifier, so its focus properties still reach the letters.
        Modifier
            .focusProperties { onEnter = { runCatching { focus.requestFocus() } } }
            .focusGroup()
            .then(modifier)
            .width(48.dp)
            .clip(PillShape)
            .background(GlacierColors.GlassFill)
            .border(1.dp, GlacierColors.GlassBorder, PillShape)
            .padding(vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        order.forEach { letter ->
            RailLetter(
                letter,
                available = letter in letters,
                current = letter == current,
                onClick = { onLetter(letter) },
                modifier = if (letter == focused) Modifier.focusRequester(focus) else Modifier,
            )
        }
    }
}

@Composable
private fun RailLetter(letter: Char, available: Boolean, current: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val accent = LocalAccent.current.main
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        modifier
            .focusScale(focused)
            .size(34.dp, 20.dp)
            .clip(PillShape)
            .background(
                when {
                    focused -> accent
                    current -> accent.copy(alpha = 0.18f)
                    else -> Color.Transparent
                },
            )
            .clickable(interactionSource = interaction, indication = null, enabled = available, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            letter.toString(),
            style = GlacierText.body(14, FontWeight.Bold),
            color = when {
                focused -> GlacierColors.Void
                current -> GlacierColors.Ice
                available -> GlacierColors.Mist
                else -> Color(0x38E8F4F7)
            },
        )
    }
}

/**
 * Vertical scrolling of grid rows: a focused row settles [ROW_PIVOT] from the
 * top, ignoring small offsets within the same row. The header scrolls itself
 * back to the top when it gains focus (see [Toolbar]), so the spec leaves
 * anything above the first row alone.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun rememberGridPivotSpec(gridState: LazyGridState): BringIntoViewSpec {
    val density = LocalDensity.current
    return remember(gridState, density) {
        val pivot = with(density) { ROW_PIVOT.dp.toPx() }
        val tolerance = with(density) { ROW_TOLERANCE.dp.toPx() }
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                if (gridState.firstVisibleItemIndex < HEADER_ITEMS && offset < pivot) return 0f
                val distance = offset - pivot
                return if (abs(distance) < tolerance) 0f else distance
            }
        }
    }
}

/**
 * The first row's top while the grid is scrolled all the way up, else null. The A–Z rail
 * stays there and does not scroll with the grid; [RAIL_TOP] until it is known.
 */
private fun firstRowHome(gridState: LazyGridState): Int? {
    if (gridState.firstVisibleItemIndex != 0 || gridState.firstVisibleItemScrollOffset != 0) return null
    val info = gridState.layoutInfo
    val firstRow = info.visibleItemsInfo.firstOrNull { it.index == HEADER_ITEMS } ?: return null
    return firstRow.offset.y - info.viewportStartOffset
}

/**
 * Filed as the library sorts it: by its artist or album when listed under them, else by its name.
 * A song's sort name starts with its disc and track numbers, so songs go by their title.
 */
private fun letterOf(item: MediaItem): Char? = when (val group = item.group) {
    is ListGroup.Name -> railLetter(group.name)
    null -> railLetter(if (item.kind == ItemKind.Song) item.title else NameOrder.title(item.title, item.sortName))
    else -> null
}

/** Whether the title at [index] is the first of its group, so its heading goes before it. */
private fun startsGroup(items: List<MediaItem>, index: Int): Boolean =
    items[index].group != null && items.getOrNull(index - 1)?.group != items[index].group

/** Each title's index in the grid: after the title and toolbar, and after the group headings up to it. */
private fun gridPositions(items: List<MediaItem>): IntArray {
    var headings = 0
    return IntArray(items.size) { index ->
        if (startsGroup(items, index)) headings++
        HEADER_ITEMS + headings + index
    }
}

/** The card's caption, plus the value the grid is sorted by when the caption does not show it yet. */
@Composable
private fun captionFor(item: MediaItem, sort: LibrarySort?): String? {
    val showsYear = item.kind in setOf(ItemKind.Movie, ItemKind.Series, ItemKind.Episode, ItemKind.MusicVideo)
    val sorted = when (sort) {
        LibrarySort.Year -> item.year?.takeUnless { showsYear }?.toString()
        LibrarySort.Runtime ->
            if (item.kind == ItemKind.Song) item.runtimeMs?.let(::formatTime)
            else item.runtimeMinutes?.takeIf { it > 0 }?.let { runtimeText(it) }
        LibrarySort.Rating -> item.communityRating?.let { "★ ${ratingText(it)}" }
        else -> null
    }
    return listOfNotNull(baseCaption(item), sorted).joinToString(" · ").ifEmpty { null }
}

@Composable
private fun baseCaption(item: MediaItem): String? = when (item.kind) {
    ItemKind.Album, ItemKind.Song -> item.parentTitle
    ItemKind.Collection, ItemKind.Genre, ItemKind.Playlist -> item.childCount?.let { pluralStringResource(R.plurals.count_titles, it, it) }
    ItemKind.Artist -> item.childCount?.let { pluralStringResource(R.plurals.count_albums, it, it) }
    else -> yearText(item)
}

@Composable
private fun countText(scope: LibraryScope, kind: LibraryKind, count: Int): String = when (scope) {
    LibraryScope.Collections -> pluralStringResource(R.plurals.count_collections, count, count)
    LibraryScope.Genres -> pluralStringResource(R.plurals.count_genres, count, count)
    LibraryScope.Artists -> pluralStringResource(R.plurals.count_artists, count, count)
    LibraryScope.Playlists -> pluralStringResource(R.plurals.count_playlists, count, count)
    LibraryScope.Albums -> pluralStringResource(R.plurals.count_albums, count, count)
    LibraryScope.Songs -> pluralStringResource(R.plurals.count_songs, count, count)
    else -> if (kind == LibraryKind.Music) pluralStringResource(R.plurals.count_albums, count, count) else pluralStringResource(R.plurals.count_titles, count, count)
}

private val LibraryScope.label: Int
    get() = when (this) {
        LibraryScope.All -> R.string.scope_all
        LibraryScope.Collections -> R.string.scope_collections
        LibraryScope.Genres -> R.string.scope_genres
        LibraryScope.Albums -> R.string.scope_albums
        LibraryScope.Artists -> R.string.scope_artists
        LibraryScope.Songs -> R.string.scope_songs
        LibraryScope.Playlists -> R.string.scope_playlists
    }

private val LibrarySort.label: Int
    get() = when (this) {
        LibrarySort.DateAdded -> R.string.sort_date_added
        LibrarySort.Title -> R.string.sort_title
        LibrarySort.Artist -> R.string.sort_artist
        LibrarySort.Album -> R.string.sort_album
        LibrarySort.Year -> R.string.sort_year
        LibrarySort.Rating -> R.string.sort_rating
        LibrarySort.Runtime -> R.string.sort_runtime
    }

/** The A–Z rail's width and gap right of it, plus the grid's own 22. */
private const val RAIL_SPACE = 92

/** The song list loads more this many songs before its end. */
private const val SONG_LOOKAHEAD = 20

/** How much lower a group heading over cards is drawn than its place in the grid. */
private const val GROUP_HEADING_DROP = 22

/** The grid's loader is at least this tall before the grid has measured where it sits. */
private const val LOADER_MIN_HEIGHT = 360
