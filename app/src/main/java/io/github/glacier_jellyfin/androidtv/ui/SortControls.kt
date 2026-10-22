package io.github.glacier_jellyfin.androidtv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierIcons
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierShapes
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.glacier_jellyfin.androidtv.core.designsystem.LocalAccent
import io.github.glacier_jellyfin.androidtv.core.designsystem.PillShape
import io.github.glacier_jellyfin.androidtv.core.designsystem.focusScale

/**
 * The sort button at the right of a toolbar: the current sort, its direction when it has
 * one ([descending] null: none), and a chevron for the [SortMenu] it opens.
 */
@Composable
fun SortButton(label: String, open: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, descending: Boolean? = null) {
    val accent = LocalAccent.current.main
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val foreground = if (focused) GlacierColors.Void else GlacierColors.Ice
    Row(
        modifier
            .focusScale(focused)
            .height(52.dp)
            .clip(PillShape)
            .background(if (focused) accent else if (open) accent.copy(alpha = 0.18f) else GlacierColors.GlassFill2)
            .border(2.dp, if (focused) accent else GlacierColors.GlassBorder2, PillShape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(GlacierIcons.SortLines, contentDescription = null, tint = foreground, modifier = Modifier.size(20.dp))
        Text(label, style = GlacierText.body(19, FontWeight.SemiBold), color = foreground)
        if (descending != null) {
            Icon(
                GlacierIcons.ArrowDown,
                contentDescription = stringResource(if (descending) R.string.sort_descending else R.string.sort_ascending),
                tint = foreground,
                modifier = Modifier.size(19.dp).rotate(if (descending) 0f else 180f),
            )
        }
        Icon(GlacierIcons.ChevronDown, contentDescription = null, tint = foreground, modifier = Modifier.size(18.dp).rotate(if (open) 180f else 0f))
    }
}

/**
 * The design's sort dropdown, 14px below the sort button and right-aligned
 * with it ([anchor]: the button's bounds in the root). Drawn in the screen itself
 * rather than a popup window, which would not share the theme's 1920-wide density.
 * Focus starts on the current sort and stays inside while it is open.
 */
@Composable
fun <T> SortMenu(
    sorts: List<T>,
    current: T,
    label: @Composable (T) -> String,
    anchor: Rect,
    onSort: (T) -> Unit,
    onDismiss: () -> Unit,
    descending: Boolean? = null,
) {
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
        sorts.forEach { sort ->
            SortOption(
                label = label(sort),
                active = sort == current,
                descending = descending,
                onClick = { onSort(sort) },
                modifier = if (sort == current) Modifier.focusRequester(firstFocus) else Modifier,
            )
        }
    }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }
}

@Composable
private fun SortOption(label: String, active: Boolean, descending: Boolean?, onClick: () -> Unit, modifier: Modifier) {
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
        if (active && descending != null) {
            Icon(GlacierIcons.ArrowDown, contentDescription = null, tint = foreground, modifier = Modifier.size(20.dp).rotate(if (descending) 0f else 180f))
        }
    }
}
