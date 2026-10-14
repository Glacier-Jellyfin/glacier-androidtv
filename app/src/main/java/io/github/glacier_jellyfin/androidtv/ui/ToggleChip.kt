package io.github.glacier_jellyfin.androidtv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.glacier_jellyfin.androidtv.core.designsystem.LocalAccent
import io.github.glacier_jellyfin.androidtv.core.designsystem.PillShape
import io.github.glacier_jellyfin.androidtv.core.designsystem.focusScale

/**
 * A filter that is on or off, at the right of a toolbar: "Favorites", "Unwatched".
 * While on, its pill is tinted and the icon ([iconOn]) takes the accent.
 */
@Composable
fun ToggleChip(
    label: String,
    on: Boolean,
    icon: ImageVector,
    iconOn: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = LocalAccent.current.main
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val foreground = when {
        focused -> GlacierColors.Void
        on -> GlacierColors.Ice
        else -> GlacierColors.Mist
    }
    Row(
        modifier
            .focusScale(focused)
            .height(52.dp)
            .clip(PillShape)
            .background(
                when {
                    focused -> accent
                    on -> accent.copy(alpha = 0.18f)
                    else -> GlacierColors.GlassFill
                },
            )
            .border(2.dp, if (focused) accent else if (on) accent.copy(alpha = 0.45f) else Color.Transparent, PillShape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            if (on) iconOn else icon,
            contentDescription = null,
            tint = if (on && !focused) accent else foreground,
            modifier = Modifier.size(20.dp),
        )
        Text(label, style = GlacierText.body(19, FontWeight.SemiBold), color = foreground)
    }
}
