package io.github.glacier_jellyfin.androidtv.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
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
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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
import io.github.glacier_jellyfin.androidtv.core.data.media.LibrarySort
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaItem
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierIcons
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierShapes
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.glacier_jellyfin.androidtv.core.designsystem.LocalAccent
import io.github.glacier_jellyfin.androidtv.core.designsystem.PillButton
import io.github.glacier_jellyfin.androidtv.core.designsystem.PillShape
import io.github.glacier_jellyfin.androidtv.core.designsystem.SpinningDiamond
import io.github.glacier_jellyfin.androidtv.core.designsystem.focusScale
import io.github.glacier_jellyfin.androidtv.ui.CardShape
import io.github.glacier_jellyfin.androidtv.ui.CollectEvents
import io.github.glacier_jellyfin.androidtv.ui.FilterChip
import io.github.glacier_jellyfin.androidtv.ui.GridCard
import io.github.glacier_jellyfin.androidtv.ui.LocalCardSizes
import io.github.glacier_jellyfin.androidtv.ui.NavTarget
import io.github.glacier_jellyfin.androidtv.ui.TopNav
import io.github.glacier_jellyfin.androidtv.ui.UiEvent
import kotlinx.coroutines.launch
import kotlin.math.abs

private const val HEADER_ITEMS = 2
private const val ROW_PIVOT = 330
private const val ROW_TOLERANCE = 48

@OptIn(ExperimentalFoundationApi::class) // LocalBringIntoViewSpec
@Composable
fun LibraryScreen(
    onNavigate: (UiEvent.Navigate) -> Unit,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    CollectEvents(viewModel.events, onNavigate)
    val sizes = LocalCardSizes.current

    val gridState = rememberLazyGridState()
    val firstCardFocus = remember { FocusRequester() }
    val jumpFocus = remember { FocusRequester() }
    val gridFocus = remember { FocusRequester() }
    var initialFocusDone by rememberSaveable { mutableStateOf(false) }
    // Back from a detail page the screen is composed anew; focus goes back to the card the
    // user left from (the grid keeps its scroll), not to the nav on top.
    var lastFocusId by rememberSaveable { mutableStateOf<String?>(null) }
    val restoreFocus = remember { FocusRequester() }
    var focusedIndex by remember { mutableIntStateOf(-1) }
    var jumpTarget by remember { mutableStateOf<Int?>(null) }
    var sortAnchor by remember { mutableStateOf<Rect?>(null) }

    // Load the next page when the grid gets within three rows of the end.
    LaunchedEffect(gridState, state.items.size) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { last -> if (last >= HEADER_ITEMS + state.items.size - 3 * sizes.gridColumns) viewModel.loadMore() }
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
        if (gridState.layoutInfo.visibleItemsInfo.none { it.index == HEADER_ITEMS + index }) {
            gridState.scrollToItem(HEADER_ITEMS + index)
            withFrameNanos { }
        }
        runCatching { restoreFocus.requestFocus() }
    }
    LaunchedEffect(state.jumpTo) {
        val index = state.jumpTo ?: return@LaunchedEffect
        jumpTarget = index
        gridState.scrollToItem(HEADER_ITEMS + index)
        withFrameNanos { }
        runCatching { jumpFocus.requestFocus() }
        viewModel.jumpHandled()
    }

    Box(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize()) {
            CompositionLocalProvider(LocalBringIntoViewSpec provides rememberGridPivotSpec(gridState)) {
                LazyVerticalGrid(
                    state = gridState,
                    // Cells as wide as the cards, packed from the left like the design's rows.
                    columns = GridCells.FixedSize(sizes.posterWidth.dp),
                    contentPadding = PaddingValues(start = 80.dp, end = 22.dp, top = 150.dp, bottom = 120.dp),
                    horizontalArrangement = Arrangement.spacedBy(sizes.gridGap.dp),
                    verticalArrangement = Arrangement.spacedBy(38.dp),
                    modifier = Modifier.weight(1f).fillMaxSize().focusRequester(gridFocus).focusRestorer(),
                ) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "title") { Header(state) }
                    item(span = { GridItemSpan(maxLineSpan) }, key = "chips") {
                        val scope = rememberCoroutineScope()
                        Toolbar(
                            modifier = Modifier.onFocusChanged { if (it.hasFocus) scope.launch { gridState.animateScrollToItem(0) } },
                            state = state,
                            onScope = viewModel::setScope,
                            onSortMenu = viewModel::toggleSortMenu,
                            onSortAnchor = { sortAnchor = it },
                        )
                    }
                    itemsIndexed(state.items, key = { _, item -> item.id }) { index, item ->
                        val id = item.id.toString()
                        val focus = when {
                            index == jumpTarget -> Modifier.focusRequester(jumpFocus)
                            index == 0 -> Modifier.focusRequester(firstCardFocus)
                            else -> Modifier
                        }.then(if (id == lastFocusId) Modifier.focusRequester(restoreFocus) else Modifier)
                        GridCard(
                            imageUrl = item.posterUrl,
                            title = item.title,
                            caption = captionFor(item),
                            onClick = { viewModel.open(item) },
                            shape = when (item.kind) {
                                ItemKind.Album, ItemKind.Playlist -> CardShape.Square
                                ItemKind.Artist -> CardShape.Round
                                else -> CardShape.Poster
                            },
                            watched = item.played && item.kind in setOf(ItemKind.Movie, ItemKind.Series, ItemKind.Episode),
                            count = item.childCount.takeIf { item.kind == ItemKind.Collection },
                            modifier = focus.onFocusChanged {
                                if (it.isFocused) {
                                    focusedIndex = index
                                    lastFocusId = id
                                }
                            },
                        )
                    }
                    when {
                        state.failed -> item(span = { GridItemSpan(maxLineSpan) }, key = "error") {
                            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                                Text(stringResource(R.string.library_error), style = GlacierText.body(21), color = GlacierColors.Mist)
                                PillButton(stringResource(R.string.action_retry), onClick = viewModel::retry, primary = true)
                            }
                        }
                        state.loading && state.items.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }, key = "loading") {
                            Box(Modifier.fillMaxWidth().padding(top = 80.dp), contentAlignment = Alignment.Center) { SpinningDiamond(90) }
                        }
                        state.complete && state.items.isEmpty() -> item(span = { GridItemSpan(maxLineSpan) }, key = "empty") {
                            Text(
                                stringResource(
                                    when (state.query.scope) {
                                        LibraryScope.Favorites -> R.string.library_empty_favorites
                                        LibraryScope.Unwatched -> R.string.library_empty_unwatched
                                        else -> R.string.library_empty
                                    },
                                ),
                                style = GlacierText.body(21),
                                color = GlacierColors.Mist,
                                modifier = Modifier.width(720.dp),
                            )
                        }
                        else -> Unit
                    }
                }
            }
            if (state.query.alphabetical && state.letters.isNotEmpty()) {
                val current = state.items.getOrNull(focusedIndex)?.let(::letterOf)
                AlphabetRail(
                    letters = state.letters,
                    descending = state.query.descending,
                    current = current,
                    onLetter = viewModel::jumpToLetter,
                    modifier = Modifier.padding(top = 150.dp, end = 22.dp),
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

        TopNav(
            active = NavTarget.Library(state.query.kind),
            kinds = LibraryKind.entries,
            userName = state.userName,
            onSelect = viewModel::onNav,
            down = gridFocus,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 34.dp),
        )
    }
}

@Composable
private fun Header(state: LibraryState) {
    val title = state.title ?: stringResource(
        when (state.query.kind) {
            LibraryKind.Movies -> R.string.library_movies
            LibraryKind.Shows -> R.string.library_shows
            LibraryKind.Music -> R.string.library_music
        },
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = GlacierText.display(50), color = GlacierColors.Ice)
        val total = state.total
        if (total != null) {
            val count = countText(state.query.scope, state.query.kind, total)
            // Genres and artists are always listed by name, so there is no sort to report.
            val sorted = state.query.scope !in setOf(LibraryScope.Genres, LibraryScope.Artists)
            Text(
                if (!sorted) count else stringResource(
                    R.string.library_summary,
                    count,
                    stringResource(state.query.sort.label),
                    stringResource(if (state.query.descending) R.string.sort_descending else R.string.sort_ascending),
                ),
                style = GlacierText.body(19),
                color = GlacierColors.Mist,
            )
        }
    }
}

@Composable
private fun Toolbar(
    modifier: Modifier,
    state: LibraryState,
    onScope: (LibraryScope) -> Unit,
    onSortMenu: (Boolean) -> Unit,
    onSortAnchor: (Rect) -> Unit,
) {
    val activeChip = remember { FocusRequester() }
    Row(
        modifier
            // Entering the toolbar lands on the active filter, not the geometrically nearest chip.
            .focusProperties { onEnter = { activeChip.requestFocus() } }
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
        // Genres and artists are always listed by name.
        if (state.query.scope !in setOf(LibraryScope.Genres, LibraryScope.Artists)) {
            SortButton(
                state,
                onClick = { onSortMenu(!state.sortMenuOpen) },
                modifier = Modifier.onGloballyPositioned { onSortAnchor(it.boundsInRoot()) },
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
        LibrarySort.entries.forEach { sort ->
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
    onLetter: (Char) -> Unit,
    modifier: Modifier = Modifier,
) {
    val order = if (descending) AlphabetLetters.reversed() else AlphabetLetters
    Column(
        modifier
            .width(48.dp)
            .clip(PillShape)
            .background(GlacierColors.GlassFill)
            .border(1.dp, GlacierColors.GlassBorder, PillShape)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        order.forEach { letter -> RailLetter(letter, available = letter in letters, current = letter == current, onClick = { onLetter(letter) }) }
    }
}

@Composable
private fun RailLetter(letter: Char, available: Boolean, current: Boolean, onClick: () -> Unit) {
    val accent = LocalAccent.current.main
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        Modifier
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

private fun letterOf(item: MediaItem): Char {
    val first = (item.sortName ?: item.title).trim().firstOrNull()?.uppercaseChar() ?: return '#'
    val plain = java.text.Normalizer.normalize(first.toString(), java.text.Normalizer.Form.NFD).first()
    return if (plain in 'A'..'Z') plain else '#'
}

@Composable
private fun captionFor(item: MediaItem): String? = when (item.kind) {
    ItemKind.Album -> item.parentTitle
    ItemKind.Collection, ItemKind.Genre, ItemKind.Playlist -> item.childCount?.let { pluralStringResource(R.plurals.count_titles, it, it) }
    ItemKind.Artist -> item.childCount?.let { pluralStringResource(R.plurals.count_albums, it, it) }
    else -> item.year?.toString()
}

@Composable
private fun countText(scope: LibraryScope, kind: LibraryKind, count: Int): String = when (scope) {
    LibraryScope.Collections -> pluralStringResource(R.plurals.count_collections, count, count)
    LibraryScope.Genres -> pluralStringResource(R.plurals.count_genres, count, count)
    LibraryScope.Artists -> pluralStringResource(R.plurals.count_artists, count, count)
    LibraryScope.Playlists -> pluralStringResource(R.plurals.count_playlists, count, count)
    LibraryScope.Albums -> pluralStringResource(R.plurals.count_albums, count, count)
    else -> if (kind == LibraryKind.Music) pluralStringResource(R.plurals.count_albums, count, count) else pluralStringResource(R.plurals.count_titles, count, count)
}

private val LibraryScope.label: Int
    get() = when (this) {
        LibraryScope.All -> R.string.scope_all
        LibraryScope.Collections -> R.string.scope_collections
        LibraryScope.Genres -> R.string.scope_genres
        LibraryScope.Unwatched -> R.string.scope_unwatched
        LibraryScope.Favorites -> R.string.scope_favorites
        LibraryScope.Albums -> R.string.scope_albums
        LibraryScope.Artists -> R.string.scope_artists
        LibraryScope.Playlists -> R.string.scope_playlists
    }

private val LibrarySort.label: Int
    get() = when (this) {
        LibrarySort.DateAdded -> R.string.sort_date_added
        LibrarySort.Title -> R.string.sort_title
        LibrarySort.Year -> R.string.sort_year
        LibrarySort.Rating -> R.string.sort_rating
        LibrarySort.Runtime -> R.string.sort_runtime
    }
