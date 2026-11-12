package io.github.glacier_jellyfin.androidtv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierIcons
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.glacier_jellyfin.androidtv.core.designsystem.LocalAccent
import io.github.glacier_jellyfin.androidtv.core.designsystem.PillShape
import io.github.glacier_jellyfin.androidtv.core.designsystem.focusScale

/** Edge to the check (start) or the hold mark (end); a little more than to the label, so neither crowds the rounded edge. */
private val CHECK_EDGE = 14.dp

/** Check or hold mark to the label. */
private val CHECK_GAP = 10.dp

/**
 * Filter or season chip (`pill()` with an active state): glass, tinted when active, accent when focused.
 * With [onLongClick], holding OK does that instead. [checked] puts a watched check before the label.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FilterChip(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Int = 52,
    fontSize: Int = 19,
    onLongClick: (() -> Unit)? = null,
    checked: Boolean = false,
) {
    val accent = LocalAccent.current.main
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val sidePadding = if (height >= 52) 26.dp else 24.dp
    val holdMark = onLongClick != null && focused
    val labelColor = when {
        focused -> GlacierColors.Void
        active -> GlacierColors.Ice
        else -> GlacierColors.Mist
    }
    Box(
        modifier
            .focusScale(focused)
            .height(height.dp)
            .clip(PillShape)
            .background(
                when {
                    focused -> accent
                    active -> accent.copy(alpha = 0.18f)
                    else -> GlacierColors.GlassFill
                },
            )
            .border(2.dp, if (focused) accent else if (active) accent.copy(alpha = 0.45f) else Color.Transparent, PillShape)
            .combinedClickable(interactionSource = interaction, indication = null, onLongClick = onLongClick, onClick = onClick)
            // A check at the start and the hold mark at the end sit the same way: a little further from the edge than from the label.
            .padding(start = if (checked) CHECK_EDGE else sidePadding, end = if (holdMark) CHECK_EDGE else sidePadding),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CHECK_GAP)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CHECK_GAP)) {
                // A watched check in the label's own colour.
                if (checked) Icon(GlacierIcons.Check, contentDescription = null, tint = labelColor, modifier = Modifier.size(16.dp))
                Text(label, style = GlacierText.body(fontSize, FontWeight.SemiBold), color = labelColor)
            }
            // With options, the focused chip shows the mark that says holding OK opens them; unfocused it takes no room.
            if (holdMark) HoldOkMark(true, GlacierColors.Void, size = 22)
        }
    }
}
