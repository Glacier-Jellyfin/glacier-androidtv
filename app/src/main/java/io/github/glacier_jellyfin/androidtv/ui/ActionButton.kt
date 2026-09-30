package io.github.glacier_jellyfin.androidtv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.glacier_jellyfin.androidtv.core.designsystem.LocalAccent
import io.github.glacier_jellyfin.androidtv.core.designsystem.PillShape
import io.github.glacier_jellyfin.androidtv.core.designsystem.focusFrame
import io.github.glacier_jellyfin.androidtv.core.designsystem.focusScale

/**
 * Action button on detail and spotlight surfaces (`actBtn()` in the design):
 * accent-deep when [primary], tinted when [on], solid accent when focused.
 * Without [label] it is a round icon button, e.g. favourite.
 */
@Composable
fun ActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    icon: ImageVector? = null,
    primary: Boolean = false,
    on: Boolean = false,
    contentDescription: String? = label,
    /** Second icon drawn over [icon] in the button's background colour, e.g. a check in a filled circle. */
    iconMark: ImageVector? = null,
    enabled: Boolean = true,
) {
    val accent = LocalAccent.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val background = when {
        focused -> accent.main
        primary -> accent.deep
        on -> accent.main.copy(alpha = 0.26f)
        else -> accent.main.copy(alpha = 0.12f)
    }
    val border = when {
        focused -> accent.main
        primary -> accent.deep
        on -> accent.main
        else -> accent.main.copy(alpha = 0.4f)
    }
    val foreground = if (focused || primary) GlacierColors.Void else GlacierColors.Ice
    Row(
        modifier = modifier
            .focusScale(focused)
            .focusFrame(focused, PillShape, unfocusedBorder = border)
            .height(62.dp)
            .defaultMinSize(minWidth = 62.dp)
            .clip(PillShape)
            .background(background)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = if (label != null) 32.dp else 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) {
            val iconSize = if (label != null) 20.dp else 26.dp
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = if (label == null) contentDescription else null, tint = foreground, modifier = Modifier.size(iconSize))
                if (iconMark != null) {
                    Icon(iconMark, contentDescription = null, tint = if (focused) accent.main else GlacierColors.Void, modifier = Modifier.size(iconSize))
                }
            }
        }
        if (label != null) Text(label, style = GlacierText.body(21, FontWeight.SemiBold), color = foreground)
    }
}
