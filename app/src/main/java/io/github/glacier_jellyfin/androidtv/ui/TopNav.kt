package io.github.glacier_jellyfin.androidtv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryKind
import io.github.glacier_jellyfin.androidtv.core.data.settings.NavigationSettings
import io.github.glacier_jellyfin.androidtv.core.data.settings.arranged
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierIcons
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.glacier_jellyfin.androidtv.core.designsystem.LocalAccent
import io.github.glacier_jellyfin.androidtv.core.designsystem.PillShape
import io.github.glacier_jellyfin.androidtv.core.designsystem.focusScale

/** The library kinds the server has (HomeRepository.kinds): one navigation entry each. */
val LocalLibraryKinds = staticCompositionLocalOf { listOf(LibraryKind.Movies, LibraryKind.Shows, LibraryKind.Music) }

/** The server offers Live TV (HomeRepository.liveTv): the navigation gets its entry. */
val LocalLiveTv = staticCompositionLocalOf { false }

/** The entries between search and settings as Settings › Appearance arranges them; null before they are known. */
val LocalNavTargets = staticCompositionLocalOf<List<NavTarget>?> { null }

/** A newer app version is known (UpdateManager): the settings gear carries a dot. */
val LocalUpdatePending = staticCompositionLocalOf { false }

/** The signed-in profile's picture; the avatar shows the initial without one. */
val LocalProfileImage = staticCompositionLocalOf<String?> { null }

sealed interface NavTarget {
    data object Search : NavTarget
    data object Home : NavTarget
    data class Library(val kind: LibraryKind) : NavTarget
    /** The channels of the server's Live TV. */
    data object LiveTv : NavTarget
    /** Everything marked with the heart. */
    data object Favorites : NavTarget
    data object Settings : NavTarget
    data object Profile : NavTarget
    /** The full music player, opened from the [MiniPlayer]. */
    data object NowPlaying : NavTarget
}

/**
 * The floating navigation pill (design: "TOP NAV PILL"): search, home, one
 * entry per library kind the server has, favorites, settings and the profile avatar.
 * While music is loaded the [MiniPlayer] sits at the right edge of the same
 * row; Right from the avatar goes there.
 *
 * The pill leaves the top right corner free for a toast. When the entries need
 * more room, the ones between search and settings scroll; search, settings and
 * the avatar always stay in view.
 */
@OptIn(ExperimentalFoundationApi::class) // LocalBringIntoViewSpec
@Composable
fun TopNav(
    active: NavTarget,
    kinds: List<LibraryKind>,
    userName: String,
    onSelect: (NavTarget) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Where Down goes. The page scrolls underneath the pill, so its list starts
     * above it and focus search never finds it below; the page names its list.
     */
    down: FocusRequester? = null,
    /**
     * Back on the bar goes to the home screen. Off on the home screen itself and on a page
     * opened from another page, where Back goes back to that page.
     */
    backHome: Boolean = active != NavTarget.Home,
) {
    var focused by remember { mutableStateOf(false) }
    BackHandler(enabled = focused && backHome) { onSelect(NavTarget.Home) }
    val activeFocus = remember { FocusRequester() }
    val profileFocus = remember { FocusRequester() }
    val miniFocus = remember { FocusRequester() }
    val nowPlaying = LocalNowPlaying.current
    val middle = LocalNavTargets.current ?: (listOf(NavTarget.Home) + kinds.map(NavTarget::Library) + NavTarget.Favorites)
    // The page's own entry may be switched off: entering from below lands on search then.
    val focusTarget = active.takeIf { it in middle || it == NavTarget.Settings } ?: NavTarget.Search
    val scroll = rememberScrollState()
    var activeBounds by remember { mutableStateOf<IntRange?>(null) }
    val inset = with(LocalDensity.current) { NAV_SCROLL_INSET.dp.roundToPx() }
    // Show the page's entry on arrival, even when it sits beyond the scrolled part.
    LaunchedEffect(activeBounds, scroll.viewportSize) {
        val bounds = activeBounds ?: return@LaunchedEffect
        val viewport = scroll.viewportSize.takeIf { it > 0 } ?: return@LaunchedEffect
        if (bounds.first < scroll.value || bounds.last > scroll.value + viewport) {
            scroll.scrollTo((bounds.first + bounds.last) / 2 - viewport / 2)
        }
    }

    @Composable
    fun Entry(target: NavTarget, modifier: Modifier = Modifier) = NavItem(
        target = target,
        active = target == active,
        userName = userName,
        onClick = { onSelect(target) },
        down = down,
        // Right from the avatar goes to the mini player, or nowhere: not down to the page (the A–Z rail).
        right = if (target == NavTarget.Profile) (if (nowPlaying != null) miniFocus else FocusRequester.Cancel) else null,
        modifier = modifier.then(
            when (target) {
                focusTarget -> Modifier.focusRequester(activeFocus)
                NavTarget.Profile -> Modifier.focusRequester(profileFocus)
                else -> Modifier
            },
        ),
    )

    // The pill sits in the middle while there is room. With many entries it moves left, away from the
    // toast corner and the mini player, but not past the page edge; beyond that its middle scrolls.
    Layout(
        modifier = modifier.fillMaxWidth().onFocusChanged { focused = it.hasFocus },
        content = {
            Row(
                modifier = Modifier
                    // Entering from below lands on the active entry, not the geometrically nearest one.
                    .focusProperties { onEnter = { activeFocus.requestFocus() } }
                    .focusGroup()
                    .dropShadow(PillShape, Shadow(radius = 40.dp, spread = (-14).dp, color = Color.Black.copy(alpha = 0.55f), offset = DpOffset(0.dp, 18.dp)))
                    .clip(PillShape)
                    .background(GlacierColors.Deep.copy(alpha = 0.72f))
                    .background(GlacierColors.GlassFill2)
                    .border(1.dp, GlacierColors.GlassBorder2, PillShape)
                    // The middle adds the rest vertically, so its fade leaves room for the focus scale.
                    .padding(horizontal = 7.dp, vertical = (7 - NAV_SCROLL_INSET).dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Entry(NavTarget.Search)
                CompositionLocalProvider(LocalBringIntoViewSpec provides rememberEdgeSpec(NAV_FADE)) {
                    Row(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .edgeFade(scroll, NAV_FADE.dp)
                            .padding(vertical = NAV_SCROLL_INSET.dp)
                            .horizontalScroll(scroll)
                            // Also the spacing to search and settings, which the pill row leaves out.
                            .padding(horizontal = NAV_SCROLL_INSET.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        middle.forEach { target ->
                            Entry(
                                target,
                                if (target == active) {
                                    // Relative to the row's padded content; add the padding for scroll offsets.
                                    Modifier.onPlaced { activeBounds = (it.positionInParent().x.toInt() + inset).let { x -> x..x + it.size.width } }
                                } else {
                                    Modifier
                                },
                            )
                        }
                    }
                }
                Entry(NavTarget.Settings)
                Spacer(Modifier.width(6.dp))
                Entry(NavTarget.Profile)
            }
            if (nowPlaying != null) {
                MiniPlayer(
                    nowPlaying = nowPlaying,
                    onClick = { onSelect(NavTarget.NowPlaying) },
                    modifier = Modifier
                        .focusRequester(miniFocus)
                        .focusProperties {
                            left = profileFocus
                            if (down != null) this.down = down
                        },
                )
            }
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val edge = PageEdge.dp.roundToPx()
        val gap = NAV_MINI_GAP.dp.roundToPx()
        // Where the toast shows, which moves below the mini player meanwhile.
        val miniEnd = width - MINI_PLAYER_END.dp.roundToPx()
        val toast = TOAST_WIDTH.dp.roundToPx()
        val pill = measurables[0].measure(loose.copy(maxWidth = (miniEnd - toast - gap - edge).coerceAtLeast(0)))
        val mini = measurables.getOrNull(1)?.measure(loose.copy(maxWidth = (miniEnd - edge - pill.width - gap).coerceAtLeast(0)))
        val centred = (width - pill.width) / 2
        val corner = maxOf(toast, mini?.width ?: 0)
        val pillX = minOf(centred, miniEnd - corner - gap - pill.width).coerceAtLeast(edge)
        layout(width, maxOf(pill.height, mini?.height ?: 0)) {
            pill.place(pillX, 0)
            mini?.place(miniEnd - mini.width, 0)
        }
    }
}

/** Space between the navigation pill and the mini player or toast when they come close. */
private const val NAV_MINI_GAP = 24

/** Room around the scrolling entries for their focus scale; also their spacing to search and settings. */
private const val NAV_SCROLL_INSET = 6

/** How far the scrolling entries fade out at an edge with more behind it; a focused entry stays clear of it. */
private const val NAV_FADE = 40

/** Fades the content out at the left and right edge while [scroll] has more to show there. */
private fun Modifier.edgeFade(scroll: ScrollState, width: Dp): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val fade = width.toPx().coerceAtMost(size.width / 2)
        if (scroll.value > 0) {
            drawRect(
                Brush.horizontalGradient(0f to Color.Transparent, 1f to Color.Black, startX = 0f, endX = fade),
                size = Size(fade, size.height),
                blendMode = BlendMode.DstIn,
            )
        }
        if (scroll.value < scroll.maxValue) {
            drawRect(
                Brush.horizontalGradient(0f to Color.Black, 1f to Color.Transparent, startX = size.width - fade, endX = size.width),
                topLeft = Offset(size.width - fade, 0f),
                size = Size(fade, size.height),
                blendMode = BlendMode.DstIn,
            )
        }
    }

/** The mini player's distance from the right edge of the screen: the same as from the top. */
const val MINI_PLAYER_END = MINI_PLAYER_TOP

@Composable
private fun NavItem(
    target: NavTarget,
    active: Boolean,
    userName: String,
    onClick: () -> Unit,
    down: FocusRequester?,
    right: FocusRequester?,
    modifier: Modifier,
) {
    val accent = LocalAccent.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val profileLabel = stringResource(R.string.nav_profile)
    val iconOnly = target is NavTarget.Search || target is NavTarget.Settings || target is NavTarget.Profile
    val background = when {
        focused -> accent.main
        active -> accent.main.copy(alpha = 0.18f)
        else -> Color.Transparent
    }
    val foreground = when {
        focused -> GlacierColors.Void
        active -> GlacierColors.Ice
        else -> GlacierColors.Mist
    }
    val border = when {
        focused -> accent.main
        active -> accent.main.copy(alpha = 0.45f)
        else -> Color.Transparent
    }
    Box(
        modifier = modifier
            .focusScale(focused)
            .height(54.dp)
            .defaultMinSize(minWidth = 54.dp)
            .clip(PillShape)
            .background(background)
            .border(2.dp, border, PillShape)
            .focusProperties {
                if (down != null) this.down = down
                if (right != null) this.right = right
            }
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = if (iconOnly) 15.dp else 26.dp),
        contentAlignment = Alignment.Center,
    ) {
        when (target) {
            NavTarget.Search -> Icon(GlacierIcons.Search, stringResource(R.string.nav_search), tint = foreground, modifier = Modifier.size(22.dp))
            NavTarget.Settings -> Box {
                Icon(GlacierIcons.Settings, stringResource(R.string.nav_settings), tint = foreground, modifier = Modifier.size(22.dp))
                if (LocalUpdatePending.current) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 4.dp, y = (-5).dp)
                            .size(11.dp)
                            .clip(CircleShape)
                            .background(accent.main)
                            .border(2.dp, GlacierColors.Void, CircleShape),
                    )
                }
            }
            NavTarget.Profile -> Box(
                Modifier
                    .semantics { contentDescription = profileLabel }
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(accent.main, accent.deep))),
                contentAlignment = Alignment.Center,
            ) {
                val image = LocalProfileImage.current
                var imageLoaded by remember(image) { mutableStateOf(false) }
                if (!imageLoaded) Text(userName.take(1).uppercase(), style = GlacierText.display(16), color = GlacierColors.Void)
                if (image != null) {
                    AsyncImage(
                        model = image,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        onSuccess = { imageLoaded = true },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            NavTarget.Home -> NavLabel(stringResource(R.string.nav_home), foreground)
            NavTarget.Favorites -> NavLabel(stringResource(R.string.nav_favorites), foreground)
            NavTarget.LiveTv -> NavLabel(stringResource(R.string.nav_live_tv), foreground)
            is NavTarget.Library -> NavLabel(stringResource(target.kind.navTitle), foreground)
            NavTarget.NowPlaying -> Unit
        }
    }
}

@Composable
private fun NavLabel(text: String, color: Color) {
    Text(text, style = GlacierText.body(19, FontWeight.SemiBold), color = color)
}

/** Ids of the entries Settings › Appearance arranges, in their default order; Live TV only when the server has it. */
fun navEntryIds(kinds: List<LibraryKind>, liveTv: Boolean): List<String> =
    listOf(NAV_HOME) + kinds.map { it.name } + listOfNotNull(NAV_LIVE_TV.takeIf { liveTv }) + NAV_FAVORITES

fun navTargetOf(id: String): NavTarget? = when (id) {
    NAV_HOME -> NavTarget.Home
    NAV_FAVORITES -> NavTarget.Favorites
    NAV_LIVE_TV -> NavTarget.LiveTv
    else -> LibraryKind.entries.firstOrNull { it.name == id }?.let(NavTarget::Library)
}

/** The entries between search and settings, as [navigation] arranges them. */
fun navTargets(navigation: NavigationSettings, kinds: List<LibraryKind>, liveTv: Boolean): List<NavTarget> =
    navigation.entries.arranged(navEntryIds(kinds, liveTv)).filter { it.shown }.mapNotNull { navTargetOf(it.id) }

const val NAV_HOME = "Home"
const val NAV_FAVORITES = "Favorites"
const val NAV_LIVE_TV = "LiveTv"
