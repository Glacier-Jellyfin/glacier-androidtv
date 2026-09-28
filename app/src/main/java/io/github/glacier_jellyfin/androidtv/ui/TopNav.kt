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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
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
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryKind
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierIcons
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.glacier_jellyfin.androidtv.core.designsystem.LocalAccent
import io.github.glacier_jellyfin.androidtv.core.designsystem.PillShape
import io.github.glacier_jellyfin.androidtv.core.designsystem.focusScale

sealed interface NavTarget {
    data object Search : NavTarget
    data object Home : NavTarget
    data class Library(val kind: LibraryKind) : NavTarget
    data object Settings : NavTarget
    data object Profile : NavTarget
}

/**
 * The floating navigation pill (design: "TOP NAV PILL"): search, home, one
 * entry per library kind the server has, settings and the profile avatar.
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
    val targets = listOf(NavTarget.Search, NavTarget.Home) + kinds.map(NavTarget::Library) + listOf(NavTarget.Settings, NavTarget.Profile)
    Row(
        modifier = modifier
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
                modifier = if (target == active) Modifier.focusRequester(activeFocus) else Modifier,
            )
        }
    }
}

@Composable
private fun NavItem(target: NavTarget, active: Boolean, userName: String, onClick: () -> Unit, down: FocusRequester?, modifier: Modifier) {
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
            .focusProperties { if (down != null) this.down = down }
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = if (iconOnly) 15.dp else 26.dp),
        contentAlignment = Alignment.Center,
    ) {
        when (target) {
            NavTarget.Search -> Icon(GlacierIcons.Search, stringResource(R.string.nav_search), tint = foreground, modifier = Modifier.size(22.dp))
            NavTarget.Settings -> Icon(GlacierIcons.Settings, stringResource(R.string.nav_settings), tint = foreground, modifier = Modifier.size(22.dp))
            NavTarget.Profile -> Box(
                Modifier
                    .semantics { contentDescription = profileLabel }
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(accent.main, accent.deep))),
                contentAlignment = Alignment.Center,
            ) {
                Text(userName.take(1).uppercase(), style = GlacierText.display(16), color = GlacierColors.Void)
            }
            NavTarget.Home -> NavLabel(stringResource(R.string.nav_home), foreground)
            is NavTarget.Library -> NavLabel(
                stringResource(
                    when (target.kind) {
                        LibraryKind.Movies -> R.string.nav_movies
                        LibraryKind.Shows -> R.string.nav_shows
                        LibraryKind.Music -> R.string.nav_music
                    },
                ),
                foreground,
            )
        }
    }
}

@Composable
private fun NavLabel(text: String, color: Color) {
    Text(text, style = GlacierText.body(19, FontWeight.SemiBold), color = color)
}
