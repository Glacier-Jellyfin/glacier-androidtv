package io.github.glacier_jellyfin.androidtv.library

import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.key.key
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.data.media.FolderContent
import io.github.glacier_jellyfin.androidtv.core.data.media.FolderMedia
import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryKind
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaFolder
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaItem
import io.github.glacier_jellyfin.androidtv.core.data.media.railLetter
import io.github.glacier_jellyfin.androidtv.core.data.settings.FolderAspect
import io.github.glacier_jellyfin.androidtv.core.data.settings.FolderSettings
import io.github.glacier_jellyfin.androidtv.core.data.settings.FolderShow
import io.github.glacier_jellyfin.androidtv.core.data.settings.FolderSort
import io.github.glacier_jellyfin.androidtv.core.data.settings.FolderView
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
import io.github.glacier_jellyfin.androidtv.detail.Crumb
import io.github.glacier_jellyfin.androidtv.ui.Artwork
import io.github.glacier_jellyfin.androidtv.ui.CollectEvents
import io.github.glacier_jellyfin.androidtv.ui.ContinueCard
import io.github.glacier_jellyfin.androidtv.ui.FilterChip
import io.github.glacier_jellyfin.androidtv.ui.LocalCardSizes
import io.github.glacier_jellyfin.androidtv.ui.LocalLibraryKinds
import io.github.glacier_jellyfin.androidtv.ui.MarqueeText
import io.github.glacier_jellyfin.androidtv.ui.NavTarget
import io.github.glacier_jellyfin.androidtv.ui.PageEdge
import io.github.glacier_jellyfin.androidtv.ui.RowPivot
import io.github.glacier_jellyfin.androidtv.ui.SortButton
import io.github.glacier_jellyfin.androidtv.ui.SortMenu
import io.github.glacier_jellyfin.androidtv.ui.TopNav
import io.github.glacier_jellyfin.androidtv.ui.UiEvent
import io.github.glacier_jellyfin.androidtv.ui.rememberEdgeSpec
import io.github.glacier_jellyfin.androidtv.ui.runtimeText
import io.github.glacier_jellyfin.androidtv.ui.showsLock
import io.github.glacier_jellyfin.androidtv.ui.title
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/**
 * A folder of a home video library under the way down to it: its subfolders as low tiles, then
 * its videos and photos, apart or mixed (toolbar). As a grid they are cards, sorted by date with a
 * heading for each month. As a gallery, moving down to them brings a large preview to the front
 * with a row of small pictures below to click through. OK on a folder opens it, on a photo shows
 * it full screen, on a video plays it and the folder's next ones; Back goes up a folder.
 */
@Composable
fun FolderScreen(
    onNavigate: (UiEvent.Navigate) -> Unit,
    viewModel: FolderViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by viewModel.folderSettings.collectAsStateWithLifecycle()
    CollectEvents(viewModel.events, onNavigate)
    // The gallery's preview does not play on behind another screen.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onStop() }
    val pageFocus = remember { FocusRequester() }
    // At the top, Back on the page first goes up to the navigation bar. In a folder it goes up a folder.
    val navFocus = remember { FocusRequester() }
    var onPage by remember { mutableStateOf(false) }
    // The gallery's preview and rows have the focus and fill the screen.
    var stageInFront by remember { mutableStateOf(false) }
    BackHandler(enabled = onPage && viewModel.inNavBar) { runCatching { navFocus.requestFocus() } }
    var inToolbar by remember { mutableStateOf(false) }
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    // Back from a video, photo or subfolder lands on its card again; the first time on the first one.
    var lastFocus by rememberSaveable { mutableStateOf<String?>(null) }
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    val remembered: (String) -> Modifier = { key ->
        Modifier
            .focusRequester(requesters.getOrPut(key) { FocusRequester() })
            .onFocusChanged { if (it.isFocused) lastFocus = key }
    }
    // A dropdown takes the focus with it when it closes; it goes back to its button.
    val anchors = remember { mutableStateMapOf<FolderMenu, Rect>() }
    val menuButtons = remember { FolderMenu.entries.associateWith { FocusRequester() } }
    // The toolbar's active filter (or the view's button): where moving into the toolbar lands, and Back from the content.
    val activeView = remember { FocusRequester() }
    var openedMenu by remember { mutableStateOf<FolderMenu?>(null) }
    LaunchedEffect(state.menu) {
        val closed = openedMenu
        openedMenu = state.menu
        if (state.menu == null && closed != null) {
            withFrameNanos { }
            runCatching { menuButtons.getValue(closed).requestFocus() }
        }
    }
    val content = state.content
    val empty = content?.isEmpty == true
    val gallery = settings.view == FolderView.Gallery
    // Back among the folders and pictures first goes up to the view's chip; from there to the bar or the folder above.
    BackHandler(enabled = onPage && !inToolbar && content?.media?.isNotEmpty() == true) {
        scope.launch {
            // Scrolled away, the toolbar is not composed and cannot take the focus yet.
            if (!gallery) gridState.scrollToItem(0)
            withFrameNanos { }
            runCatching { activeView.requestFocus() }
        }
    }
    LaunchedEffect(content != null) {
        if (content == null || empty) return@LaunchedEffect
        withFrameNanos { }
        fun focus(key: String?) = key?.let { requesters[it] }?.let { runCatching { it.requestFocus() }.getOrDefault(false) } == true
        // The first folder or picture, not the toolbar above them.
        val first = content.folders.firstOrNull()?.id ?: mediaSections(content, settings).firstOrNull()?.media?.firstOrNull()?.id
        if (!focus(lastFocus) && !focus(first?.toString())) runCatching { pageFocus.requestFocus() }
    }
    // Switched between grid and gallery in its dropdown: the new view starts at its top, with the
    // focus back on the dropdown's button, as after any other dropdown.
    var shownView by remember { mutableStateOf(settings.view) }
    LaunchedEffect(settings.view) {
        if (settings.view == shownView) return@LaunchedEffect
        shownView = settings.view
        if (!gallery) gridState.scrollToItem(0)
        withFrameNanos { }
        runCatching { menuButtons.getValue(FolderMenu.View).requestFocus() }
    }
    val title = viewModel.title ?: stringResource(LibraryKind.HomeVideos.title)

    Box(Modifier.fillMaxSize()) {
        when {
            state.loading && content == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { SpinningDiamond(110) }
            state.failed && content == null -> Message(
                text = stringResource(R.string.library_error),
                button = stringResource(R.string.action_retry),
                onClick = viewModel::load,
            )
            content == null -> Unit
            empty -> Message(text = stringResource(R.string.folder_empty))
            else -> {
                val sections = remember(content, settings) { mediaSections(content, settings) }
                val header: @Composable () -> Unit = {
                    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        // In a folder: the way down to it, from the kind's name. Too long, it loses its start.
                        if (!viewModel.inNavBar) {
                            Crumb((listOf(stringResource(LibraryKind.HomeVideos.title)) + content.trail).joinToString(" › "), overflow = TextOverflow.StartEllipsis)
                        }
                        Text(title, style = GlacierText.display(50), color = GlacierColors.Ice, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                val toolbar: (@Composable () -> Unit)? = if (content.media.isEmpty()) null else {
                    {
                        Box(Modifier.onFocusChanged { inToolbar = it.hasFocus }) {
                            Toolbar(
                                settings = settings,
                                canFilter = content.canFilter,
                                menu = state.menu,
                                onShow = viewModel::setShow,
                                onMenu = viewModel::toggleMenu,
                                onAnchor = { menu, rect -> anchors[menu] = rect },
                                menuButtons = menuButtons,
                                active = activeView,
                            )
                        }
                    }
                }
                val page = Modifier
                    .fillMaxSize()
                    .onFocusChanged { onPage = it.hasFocus }
                    .focusRequester(pageFocus)
                CompositionLocalProvider(LocalFolderAspect provides settings.aspect) {
                    if (gallery) {
                        GalleryLayout(content, sections, header, toolbar, viewModel, remembered, page, onStage = { stageInFront = it })
                    } else {
                        GridLayout(content, sections, settings, header, toolbar, viewModel, remembered, { requesters.getOrPut(it) { FocusRequester() } }, page, gridState)
                    }
                }
            }
        }

        when (state.menu) {
            FolderMenu.Sort -> anchors[FolderMenu.Sort]?.let { anchor ->
                SortMenu(
                    sorts = FolderSort.entries,
                    current = settings.sort,
                    label = { stringResource(it.label) },
                    anchor = anchor,
                    onSort = viewModel::setSort,
                    onDismiss = { viewModel.toggleMenu(null) },
                    descending = settings.descending,
                )
            }
            FolderMenu.View -> anchors[FolderMenu.View]?.let { anchor ->
                SortMenu(
                    sorts = FolderView.entries,
                    current = settings.view,
                    label = { stringResource(it.label) },
                    anchor = anchor,
                    onSort = viewModel::setView,
                    onDismiss = { viewModel.toggleMenu(null) },
                )
            }
            null -> Unit
        }

        // The gallery's stage takes the whole screen: the bar slides up out of its way, and back with the folders.
        AnimatedVisibility(
            visible = !(gallery && stageInFront),
            enter = slideInVertically(tween(GLIDE_MS, easing = FastOutSlowInEasing)) { -it } + fadeIn(tween(GLIDE_MS)),
            exit = slideOutVertically(tween(GLIDE_MS, easing = FastOutSlowInEasing)) { -it } + fadeOut(tween(GLIDE_MS)),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            TopNav(
                active = NavTarget.Library(LibraryKind.HomeVideos),
                kinds = LocalLibraryKinds.current,
                userName = state.userName,
                onSelect = viewModel::onNav,
                // Down goes back to the card left last. Not a focus restorer on the page: it would also
                // take a focus request meant for another card, such as Left from the A–Z rail.
                down = lastFocus?.let { key -> requesters.getOrPut(key) { FocusRequester() } } ?: pageFocus,
                modifier = Modifier.padding(top = 34.dp).focusRequester(navFocus),
                backHome = viewModel.inNavBar,
            )
        }
    }
}

/**
 * Folder tiles, then the media as cards, a heading over the media (and the folders above them) and over each month by
 * date. By name, an A–Z rail at the right jumps to a letter, like a library's.
 */
@OptIn(ExperimentalFoundationApi::class) // LocalBringIntoViewSpec
@Composable
private fun GridLayout(
    content: FolderContent,
    sections: List<MediaSection>,
    settings: FolderSettings,
    header: @Composable () -> Unit,
    toolbar: (@Composable () -> Unit)?,
    viewModel: FolderViewModel,
    remembered: (String) -> Modifier,
    requester: (String) -> FocusRequester,
    modifier: Modifier,
    gridState: LazyGridState,
) {
    val sizes = LocalCardSizes.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    // Videos and photos always carry their heading ("Videos", "Photos", "Videos and photos"); folders
    // carry theirs only above media, alone they need none.
    val headed = sections.isNotEmpty()
    val headerItems = 1 + (if (toolbar != null) 1 else 0) + (if (headed) 1 else 0)
    val media = sections.firstOrNull()?.media.orEmpty()
    val showRail = settings.sort == FolderSort.Name && media.isNotEmpty()
    val letters = remember(media) { media.mapTo(mutableSetOf()) { railLetter(it.name) } }
    val railFocus = remember { FocusRequester() }
    // Level with the top of the content, as in a library; it stays there while the grid scrolls.
    var railTop by rememberSaveable { mutableIntStateOf(-1) }
    var currentLetter by remember { mutableStateOf<Char?>(null) }
    // The card a letter jumped to; Left from the rail goes there.
    var jumpTarget by remember { mutableStateOf<String?>(null) }
    // Sorted by name there are no month headings: the first card follows the folders and the section's heading.
    val firstMedia = 1 + (if (toolbar != null) 1 else 0) + (if (headed && content.folders.isNotEmpty()) 1 else 0) +
        content.folders.size + (if (headed) 1 else 0)
    // The first thing below the toolbar, folders or cards, so the rail sits as high in every folder as in a library.
    val firstBelowToolbar = 1 + (if (toolbar != null) 1 else 0)
    LaunchedEffect(gridState, firstBelowToolbar) {
        snapshotFlow { firstRowHome(gridState, firstBelowToolbar) }.collect { top -> if (top != null) railTop = top }
    }
    Row(modifier) {
        CompositionLocalProvider(LocalBringIntoViewSpec provides rememberGridPivotSpec(gridState, headerItems)) {
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.FixedSize(sizes.continueWidth.dp),
                contentPadding = PaddingValues(start = PageEdge.dp, end = if (showRail) 22.dp else PageEdge.dp, top = 150.dp, bottom = 120.dp),
                horizontalArrangement = Arrangement.spacedBy(sizes.gridGap.dp),
                modifier = Modifier.weight(1f).fillMaxHeight(),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "title") { Box(Modifier.padding(bottom = 24.dp)) { header() } }
                if (toolbar != null) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "toolbar") {
                        // Coming up from the cards, the page scrolls back to its top with the title.
                        Box(Modifier.padding(bottom = 34.dp).onFocusChanged { if (it.hasFocus) scope.launch { gridState.animateScrollToItem(0) } }) { toolbar() }
                    }
                }
                if (headed && content.folders.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "folders") { SectionHeading(stringResource(R.string.folder_section_folders)) }
                }
                content.folders.forEach { folder ->
                    item(key = folder.id) {
                        FolderTile(
                            folder,
                            onClick = { viewModel.open(folder) },
                            modifier = remembered(folder.id.toString())
                                .padding(bottom = sizes.gridGap.dp)
                                .then(if (folder == content.folders.last()) Modifier.focusProperties { right = FocusRequester.Cancel } else Modifier),
                        )
                    }
                }
                sections.forEachIndexed { index, section ->
                    if (headed) {
                        item(span = { GridItemSpan(maxLineSpan) }, key = "section-${section.kind}") {
                            val first = index == 0 && content.folders.isEmpty()
                            SectionHeading(stringResource(section.kind.label), Modifier.padding(top = if (first) 0.dp else 24.dp))
                        }
                    }
                    mediaCards(
                        section,
                        settings,
                        onOpen = viewModel::open,
                        onFocus = { if (showRail) currentLetter = railLetter(it.name) },
                        remembered = remembered,
                        // By name, the last card's Right goes to the rail like every row's end.
                        lastRight = if (showRail) railFocus else FocusRequester.Cancel,
                    )
                }
            }
        }
        if (showRail) {
            AlphabetRail(
                letters = letters,
                descending = settings.descending,
                current = currentLetter,
                focus = railFocus,
                onLetter = { letter ->
                    val index = media.indexOfFirst { railLetter(it.name) == letter }
                    if (index < 0) return@AlphabetRail
                    currentLetter = letter
                    jumpTarget = media[index].id.toString()
                    // Only the grid moves; the focus stays on the rail for the next letter.
                    scope.launch {
                        gridState.scrollToItem(firstMedia + index)
                        withFrameNanos { }
                        val row = gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == firstMedia + index } ?: return@launch
                        val top = row.offset.y - gridState.layoutInfo.viewportStartOffset
                        gridState.scrollBy(top - with(density) { RowPivot.dp.toPx() })
                    }
                },
                // Left lands on the card the letter jumped to, not the geometrically nearest one.
                onLeft = { jumpTarget?.let { runCatching { requester(it).requestFocus() }.getOrDefault(false) } == true },
                modifier = Modifier
                    .padding(end = 22.dp)
                    .offset { IntOffset(0, if (railTop >= 0) railTop else RAIL_TOP.dp.roundToPx()) },
            )
        }
    }
}

/** A section's cards; sorted by date, with a heading at the start of each month. */
private fun LazyGridScope.mediaCards(
    section: MediaSection,
    settings: FolderSettings,
    onOpen: (FolderMedia) -> Unit,
    onFocus: (FolderMedia) -> Unit,
    remembered: (String) -> Modifier,
    lastRight: FocusRequester,
) {
    val groups = if (settings.sort == FolderSort.Date) section.media.byMonth() else listOf(null to section.media)
    groups.forEach { (month, media) ->
        if (settings.sort == FolderSort.Date) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "month-${section.kind}-${month ?: "none"}") { MonthHeading(month) }
        }
        media.forEach { item ->
            item(key = item.id) {
                val modifier = remembered(item.id.toString())
                    .padding(bottom = CARD_BOTTOM.dp)
                    .onFocusChanged { if (it.isFocused) onFocus(item) }
                    // A month's last row is often short: Right at its end stays, not down to another month.
                    .then(if (item == media.last()) Modifier.focusProperties { right = lastRight } else Modifier)
                when (item) {
                    is FolderMedia.Video -> ContinueCard(
                        title = item.name,
                        subtitle = videoLine(item.item),
                        imageUrl = item.item.thumbUrl,
                        progress = item.item.progress,
                        onClick = { onOpen(item) },
                        modifier = modifier,
                        locked = item.item.showsLock(),
                        imageHeight = cardHeight(),
                    )
                    is FolderMedia.Photo -> PhotoCard(item, onClick = { onOpen(item) }, modifier = modifier)
                }
            }
        }
    }
}

/**
 * The gallery: the focused picture fills the screen behind everything, with a row of small pictures to
 * click through along the bottom, one row per kind when apart. Title, toolbar and folders lie over the
 * dimmed picture. Moving down into the row ([onStage]) fades them and the navigation bar away: the
 * picture comes to the front with its name above the row, a video plays. Up to the folders brings them
 * back. Nothing scrolls, so the row is in sight from the start, like the spotlight on the home screen.
 */
@Composable
private fun GalleryLayout(
    content: FolderContent,
    sections: List<MediaSection>,
    header: @Composable () -> Unit,
    toolbar: (@Composable () -> Unit)?,
    viewModel: FolderViewModel,
    remembered: (String) -> Modifier,
    modifier: Modifier,
    onStage: (Boolean) -> Unit,
) {
    val sizes = LocalCardSizes.current
    var preview by remember(content, sections) { mutableStateOf(sections.firstOrNull()?.media?.firstOrNull()) }
    val previewVideo by viewModel.previewVideo.collectAsStateWithLifecycle()
    var onRow by remember { mutableStateOf(false) }
    // The name and the row along the bottom; the picture in front keeps clear of them.
    var rowHeight by remember { mutableIntStateOf(0) }
    // 0 with the title and folders over the dimmed picture, 1 with the picture in front; in step with the navigation bar.
    val front by animateFloatAsState(if (onRow) 1f else 0f, tween(GLIDE_MS, easing = FastOutSlowInEasing), label = "front")
    DisposableEffect(Unit) { onDispose { onStage(false) } }
    Box(modifier) {
        // In front the picture narrows to the space above its name and the row, so all of it shows.
        val density = LocalDensity.current
        val below = with(density) { rowHeight.toDp() } + PICTURE_GAP.dp
        Backdrop(
            preview,
            previewVideo,
            playSign = onRow,
            modifier = Modifier
                .fillMaxSize()
                .padding(start = PageEdge.dp * front, end = PageEdge.dp * front, top = PICTURE_TOP.dp * front, bottom = below * front)
                .clip(RoundedCornerShape(GlacierShapes.RadiusLg * front)),
        )
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = 1f - front }.background(Color(0xC705090F)))
        // Dark along the bottom for the row over the full picture; in front the picture keeps clear of the row anyway.
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = 1f - front }.background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color(0xF205090F))))
        Column(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .graphicsLayer { alpha = 1f - front }
                    .verticalScroll(rememberScrollState())
                    .padding(start = PageEdge.dp, end = PageEdge.dp, top = 150.dp, bottom = 24.dp),
            ) {
                Box(Modifier.padding(bottom = 24.dp)) { header() }
                if (toolbar != null) Box(Modifier.padding(bottom = 34.dp)) { toolbar() }
                if (content.folders.isNotEmpty()) {
                    if (sections.isNotEmpty()) SectionHeading(stringResource(R.string.folder_section_folders))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(sizes.gridGap.dp), verticalArrangement = Arrangement.spacedBy(sizes.gridGap.dp)) {
                        content.folders.forEach { folder ->
                            FolderTile(folder, onClick = { viewModel.open(folder) }, modifier = remembered(folder.id.toString()).width(sizes.continueWidth.dp))
                        }
                    }
                }
            }
            if (sections.isNotEmpty()) {
                Column(
                    Modifier
                        .onFocusChanged {
                            onRow = it.hasFocus
                            onStage(it.hasFocus)
                            // Up to the folders the video stops; back in the row it starts again.
                            viewModel.showPreview(if (it.hasFocus) preview else null)
                        }
                        .onSizeChanged { rowHeight = it.height }
                        .padding(bottom = 40.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    preview?.let { Caption(it, Modifier.graphicsLayer { alpha = front }.padding(horizontal = PageEdge.dp)) }
                    sections.forEach { section ->
                        Strip(
                            section = section,
                            labelled = sections.size > 1,
                            onOpen = viewModel::open,
                            onFocus = {
                                preview = it
                                viewModel.showPreview(it)
                            },
                            remembered = remembered,
                        )
                    }
                }
            }
        }
        // How far the video in front has played, along the bottom edge as in the viewer.
        val shown = preview
        if (onRow && shown is FolderMedia.Video && previewVideo.id == shown.id && previewVideo.player != null) {
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth(previewVideo.progress)
                    .height(4.dp)
                    .background(LocalAccent.current.main),
            )
        }
    }
}

/** One kind's small pictures in a row to click through, its name above when apart. */
@OptIn(ExperimentalFoundationApi::class) // LocalBringIntoViewSpec
@Composable
private fun Strip(
    section: MediaSection,
    labelled: Boolean,
    onOpen: (FolderMedia) -> Unit,
    onFocus: (FolderMedia) -> Unit,
    remembered: (String) -> Modifier,
) {
    Column {
        if (labelled) SectionHeading(stringResource(section.kind.label), Modifier.padding(start = PageEdge.dp))
        CompositionLocalProvider(LocalBringIntoViewSpec provides rememberEdgeSpec(PageEdge)) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = PageEdge.dp),
                horizontalArrangement = Arrangement.spacedBy(STRIP_GAP.dp),
                modifier = Modifier.focusRestorer(),
            ) {
                items(section.media.size, key = { section.media[it].id }) { index ->
                    val item = section.media[index]
                    GalleryTile(
                        item,
                        onClick = { onOpen(item) },
                        modifier = remembered(item.id.toString()).onFocusChanged { if (it.isFocused) onFocus(item) },
                    )
                }
            }
        }
    }
}

/**
 * The focused picture whole, as large as the screen allows; a video plays in it once [video] has its
 * stream, with the play sign until then when [playSign].
 */
@androidx.annotation.OptIn(UnstableApi::class) // ContentFrame
@Composable
private fun Backdrop(media: FolderMedia?, video: InlineVideoState, playSign: Boolean, modifier: Modifier) {
    Box(modifier.background(GlacierColors.Void)) {
        Crossfade(targetState = media, label = "preview") { shown ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                when (shown) {
                    is FolderMedia.Photo -> AsyncImage(
                        model = shown.photo.fullUrl,
                        contentDescription = shown.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                    is FolderMedia.Video -> {
                        val player = video.player.takeIf { video.id == shown.id }
                        AsyncImage(
                            model = shown.item.thumbUrl,
                            contentDescription = shown.name,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                        if (player != null) {
                            ContentFrame(player = player, modifier = Modifier.fillMaxSize(), surfaceType = SURFACE_TYPE_TEXTURE_VIEW, contentScale = ContentScale.Fit)
                        } else if (playSign) {
                            Box(
                                Modifier.size(84.dp).clip(PillShape).background(LocalAccent.current.main),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(GlacierIcons.Play, contentDescription = null, tint = GlacierColors.Void, modifier = Modifier.size(32.dp))
                            }
                        }
                    }
                    null -> Unit
                }
            }
        }
    }
}

/** The picture in front: its name, date and length above the row. */
@Composable
private fun Caption(media: FolderMedia, modifier: Modifier) {
    val length = (media as? FolderMedia.Video)?.item?.runtimeMinutes?.takeIf { it > 0 }?.let { runtimeText(it) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(media.name, style = GlacierText.display(34), color = GlacierColors.Ice, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val facts = listOfNotNull(takenText(media.date), length).joinToString(" · ")
        if (facts.isNotEmpty()) Text(facts, style = GlacierText.body(19), color = GlacierColors.Mist)
    }
}

/** Runs of the same month, in the list's order; undated media are their own run. */
private fun List<FolderMedia>.byMonth(): List<Pair<YearMonth?, List<FolderMedia>>> {
    val runs = mutableListOf<Pair<YearMonth?, MutableList<FolderMedia>>>()
    forEach { media ->
        val month = media.date?.let(YearMonth::from)
        val last = runs.lastOrNull()
        if (last != null && last.first == month) last.second += media else runs += month to mutableListOf(media)
    }
    return runs
}

/**
 * What to show at the left (all, videos or photos, only when the folder has both); the view and the
 * order at the right as dropdowns, like a library's sort. Moving into it lands on [active]: the
 * active filter, or the view's button without filters.
 */
@Composable
private fun Toolbar(
    settings: FolderSettings,
    canFilter: Boolean,
    menu: FolderMenu?,
    onShow: (FolderShow) -> Unit,
    onMenu: (FolderMenu) -> Unit,
    onAnchor: (FolderMenu, Rect) -> Unit,
    menuButtons: Map<FolderMenu, FocusRequester>,
    active: FocusRequester,
) {
    Row(
        Modifier
            .fillMaxWidth()
            // A request for one control (a dropdown's button after it closes) is left alone.
            .focusProperties { onEnter = { if (requestedFocusDirection != FocusDirection.Enter) active.requestFocus() } }
            .focusGroup(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (canFilter) {
            FolderShow.entries.forEach { show ->
                val shown = show == settings.show
                FilterChip(
                    label = stringResource(show.label),
                    active = shown,
                    onClick = { onShow(show) },
                    modifier = if (shown) Modifier.focusRequester(active) else Modifier,
                )
            }
        }
        Spacer(Modifier.weight(1f))
        SortButton(
            label = stringResource(settings.view.label),
            open = menu == FolderMenu.View,
            onClick = { onMenu(FolderMenu.View) },
            icon = GlacierIcons.Grid,
            modifier = Modifier
                .focusRequester(menuButtons.getValue(FolderMenu.View))
                .then(if (canFilter) Modifier else Modifier.focusRequester(active))
                .onGloballyPositioned { onAnchor(FolderMenu.View, it.boundsInRoot()) },
        )
        SortButton(
            label = stringResource(settings.sort.label),
            open = menu == FolderMenu.Sort,
            descending = settings.descending,
            onClick = { onMenu(FolderMenu.Sort) },
            modifier = Modifier
                .focusRequester(menuButtons.getValue(FolderMenu.Sort))
                .onGloballyPositioned { onAnchor(FolderMenu.Sort, it.boundsInRoot()) },
        )
    }
}

private val FolderShow.label: Int
    get() = when (this) {
        FolderShow.All -> R.string.folder_show_all
        FolderShow.Videos -> R.string.folder_show_videos
        FolderShow.Photos -> R.string.folder_show_photos
    }

private val FolderView.label: Int
    get() = when (this) {
        FolderView.Grid -> R.string.folder_view_grid
        FolderView.Gallery -> R.string.folder_view_gallery
    }

private val FolderSort.label: Int
    get() = when (this) {
        FolderSort.Date -> R.string.folder_sort_date
        FolderSort.Name -> R.string.folder_sort_name
    }

private val MediaKind.label: Int
    get() = when (this) {
        MediaKind.Videos -> R.string.folder_section_videos
        MediaKind.Photos -> R.string.folder_section_photos
        MediaKind.Mixed -> R.string.folder_section_mixed
    }

/** Time left of a started video, else its length. */
@Composable
private fun videoLine(video: MediaItem): String {
    val left = video.remainingMinutes
    val length = video.runtimeMinutes?.takeIf { it > 0 }
    return when {
        left != null && video.progress != null -> stringResource(R.string.minutes_left, left)
        length != null -> runtimeText(length)
        else -> ""
    }
}

/** The small heading over the folders, videos or photos, like a group heading in a library. */
@Composable
private fun SectionHeading(text: String, modifier: Modifier = Modifier) {
    Text(text, style = GlacierText.body(19, FontWeight.SemiBold), color = GlacierColors.Mist, modifier = modifier.padding(bottom = 16.dp))
}

/** "August 2024" over a month's media, smaller than a section's heading. */
@Composable
private fun MonthHeading(month: YearMonth?) {
    val locale = LocalLocale.current.platformLocale
    val text = month?.format(DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "LLLLyyyy"), locale))
        ?: stringResource(R.string.folder_undated)
    Text(text, style = GlacierText.body(17), color = GlacierColors.Mist, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
}

/** "12 Aug 2024" in the app language, for the gallery's preview and the viewer. */
@Composable
internal fun takenText(date: LocalDateTime?): String? {
    val locale = LocalLocale.current.platformLocale
    return date?.format(DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "dMMMyyyy"), locale))
}

/** A subfolder: a low tile with the folder sign and its name, as wide as its cell. */
@Composable
private fun FolderTile(folder: MediaFolder, onClick: () -> Unit, modifier: Modifier) {
    val accent = LocalAccent.current
    val shape = RoundedCornerShape(GlacierShapes.RadiusMd)
    GlacierCard(onClick = onClick, modifier = modifier.fillMaxWidth()) { focused ->
        Row(
            Modifier
                .fillMaxWidth()
                .height(FOLDER_TILE_HEIGHT.dp)
                .focusFrame(focused, shape, unfocusedBorder = GlacierColors.GlassBorder)
                .clip(shape)
                .background(if (focused) GlacierColors.GlassFill2 else GlacierColors.GlassFill)
                .padding(horizontal = 22.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(GlacierIcons.Folder, contentDescription = null, tint = accent.main, modifier = Modifier.size(28.dp))
            MarqueeText(folder.name, style = GlacierText.body(19, FontWeight.SemiBold), color = if (focused) accent.main else GlacierColors.Ice, active = focused)
        }
    }
}

/** The profile's card shape for videos and photos (Settings › Appearance), 16:9 unless set otherwise. */
private val LocalFolderAspect = staticCompositionLocalOf { FolderAspect.Wide }

/** A video's or photo's card height for [LocalFolderAspect] at the width of "Continue watching". */
@Composable
private fun cardHeight(): Int {
    val aspect = LocalFolderAspect.current
    return LocalCardSizes.current.continueWidth * aspect.height / aspect.width
}

/** A photo: the picture cropped to the card shape like a video's card, its name below. */
@Composable
private fun PhotoCard(photo: FolderMedia.Photo, onClick: () -> Unit, modifier: Modifier) {
    val accent = LocalAccent.current
    val shape = RoundedCornerShape(GlacierShapes.RadiusMd)
    val sizes = LocalCardSizes.current
    GlacierCard(onClick = onClick, modifier = modifier.width(sizes.continueWidth.dp)) { focused ->
        Artwork(
            photo.photo.thumbUrl,
            Modifier
                .size(sizes.continueWidth.dp, cardHeight().dp)
                .focusFrame(focused, shape)
                .clip(shape),
        )
        MarqueeText(
            photo.name,
            style = GlacierText.body(19, FontWeight.SemiBold),
            color = if (focused) accent.main else GlacierColors.Ice,
            active = focused,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

/** A small picture of the gallery's row, in the card shape; a video carries the play sign and its length. */
@Composable
private fun GalleryTile(media: FolderMedia, onClick: () -> Unit, modifier: Modifier) {
    val shape = RoundedCornerShape(GlacierShapes.RadiusSm)
    val url = when (media) {
        is FolderMedia.Video -> media.item.thumbUrl
        is FolderMedia.Photo -> media.photo.thumbUrl
    }
    GlacierCard(onClick = onClick, modifier = modifier.width(STRIP_WIDTH.dp)) { focused ->
        Box(
            Modifier
                .size(STRIP_WIDTH.dp, (STRIP_WIDTH * LocalFolderAspect.current.height / LocalFolderAspect.current.width).dp)
                .focusFrame(focused, shape)
                .clip(shape),
        ) {
            Artwork(url, Modifier.fillMaxSize())
            if (media is FolderMedia.Video) {
                Row(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .clip(PillShape)
                        .background(Color(0xB305090F))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(GlacierIcons.Play, contentDescription = null, tint = GlacierColors.Ice, modifier = Modifier.size(12.dp))
                    media.item.runtimeMinutes?.takeIf { it > 0 }?.let { Text(runtimeText(it), style = GlacierText.body(14), color = GlacierColors.Ice) }
                }
            }
        }
    }
}

/** Nothing to show: a line and maybe a button, in the middle of the page. */
@Composable
private fun Message(text: String, button: String? = null, onClick: () -> Unit = {}) {
    val focus = remember { FocusRequester() }
    if (button != null) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
    ) {
        Text(text, style = GlacierText.display(30), color = GlacierColors.Ice, textAlign = TextAlign.Center)
        if (button != null) PillButton(button, onClick = onClick, primary = true, modifier = Modifier.focusRequester(focus))
    }
}

private const val FOLDER_TILE_HEIGHT = 84

/** Space below a video's or photo's card, for its caption to breathe. */
private const val CARD_BOTTOM = 38

/** The gallery's picture coming to the front and back, and the navigation bar sliding along with it. */
private const val GLIDE_MS = 550

/** The gallery's picture in front: its distance to the top edge, and to its name above the row. */
private const val PICTURE_TOP = 40
private const val PICTURE_GAP = 24

/** The gallery row's small pictures; their height follows the card shape. */
private const val STRIP_WIDTH = 204
private const val STRIP_GAP = 16
