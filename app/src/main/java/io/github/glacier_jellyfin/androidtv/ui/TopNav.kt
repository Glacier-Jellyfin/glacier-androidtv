package io.github.glacier_jellyfin.androidtv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
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
 */
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
) {
    val activeFocus = remember { FocusRequester() }
    val profileFocus = remember { FocusRequester() }
    val miniFocus = remember { FocusRequester() }
    val nowPlaying = LocalNowPlaying.current
    val middle = LocalNavTargets.current ?: (listOf(NavTarget.Home) + kinds.map(NavTarget::Library) + NavTarget.Favorites)
    val targets = listOf(NavTarget.Search) + middle + listOf(NavTarget.Settings, NavTarget.Profile)
    // The page's own entry may be switched off: entering from below lands on search then.
    val focusTarget = active.takeIf { it in targets } ?: NavTarget.Search
    Box(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                // Entering from below lands on the active entry, not the geometrically nearest one.
                .focusProperties { onEnter = { activeFocus.requestFocus() } }
                .focusGroup()
                .dropShadow(PillShape, Shadow(radius = 40.dp, spread = (-14).dp, color = Color.Black.copy(alpha = 0.55f), offset = DpOffset(0.dp, 18.dp)))
                .clip(PillShape)
                .background(GlacierColors.Deep.copy(alpha = 0.72f))
                .background(GlacierColors.GlassFill2)
                .border(1.dp, GlacierColors.GlassBorder2, PillShape)
                .padding(7.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            targets.forEach { target ->
                NavItem(
                    target = target,
                    active = target == active,
                    userName = userName,
                    onClick = { onSelect(target) },
                    down = down,
                    right = miniFocus.takeIf { target == NavTarget.Profile && nowPlaying != null },
                    modifier = when (target) {
                        focusTarget -> Modifier.focusRequester(activeFocus)
                        NavTarget.Profile -> Modifier.focusRequester(profileFocus)
                        else -> Modifier
                    },
                )
            }
        }
        if (nowPlaying != null) {
            MiniPlayer(
                nowPlaying = nowPlaying,
                onClick = { onSelect(NavTarget.NowPlaying) },
                modifier = Modifier
                    // Where the toast shows, which moves below it meanwhile.
                    .align(Alignment.TopEnd)
                    .padding(end = 96.dp)
                    .focusRequester(miniFocus)
                    .focusProperties {
                        left = profileFocus
                        if (down != null) this.down = down
                    },
            )
        }
    }
}

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
            is NavTarget.Library -> NavLabel(stringResource(target.kind.navTitle), foreground)
            NavTarget.NowPlaying -> Unit
        }
    }
}

@Composable
private fun NavLabel(text: String, color: Color) {
    Text(text, style = GlacierText.body(19, FontWeight.SemiBold), color = color)
}

/** Ids of the entries Settings › Appearance arranges, in their default order. */
fun navEntryIds(kinds: List<LibraryKind>): List<String> = listOf(NAV_HOME) + kinds.map { it.name } + NAV_FAVORITES

fun navTargetOf(id: String): NavTarget? = when (id) {
    NAV_HOME -> NavTarget.Home
    NAV_FAVORITES -> NavTarget.Favorites
    else -> LibraryKind.entries.firstOrNull { it.name == id }?.let(NavTarget::Library)
}

/** The entries between search and settings, as [navigation] arranges them. */
fun navTargets(navigation: NavigationSettings, kinds: List<LibraryKind>): List<NavTarget> =
    navigation.entries.arranged(navEntryIds(kinds)).filter { it.shown }.mapNotNull { navTargetOf(it.id) }

const val NAV_HOME = "Home"
const val NAV_FAVORITES = "Favorites"
