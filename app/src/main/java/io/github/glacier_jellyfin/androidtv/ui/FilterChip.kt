package io.github.glacier_jellyfin.androidtv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.glacier_jellyfin.androidtv.core.designsystem.LocalAccent
import io.github.glacier_jellyfin.androidtv.core.designsystem.PillShape
import io.github.glacier_jellyfin.androidtv.core.designsystem.focusScale

/** Filter or season chip (`pill()` with an active state): glass, tinted when active, accent when focused. */
@Composable
fun FilterChip(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Int = 52,
    fontSize: Int = 19,
) {
    val accent = LocalAccent.current.main
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
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
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = if (height >= 52) 26.dp else 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = GlacierText.body(fontSize, FontWeight.SemiBold),
            color = when {
                focused -> GlacierColors.Void
                active -> GlacierColors.Ice
                else -> GlacierColors.Mist
            },
        )
    }
}
