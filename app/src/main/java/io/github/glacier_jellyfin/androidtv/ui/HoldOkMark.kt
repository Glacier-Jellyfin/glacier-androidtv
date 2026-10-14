package io.github.glacier_jellyfin.androidtv.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierIcons

/**
 * Says that holding OK (or the menu key) opens options here: the options
 * symbol in a ring, at the end of the focused element. It keeps its place
 * while [visible] is false, so nothing moves when the focus arrives.
 */
@Composable
fun HoldOkMark(visible: Boolean, tint: Color, modifier: Modifier = Modifier, size: Int = 30) {
    Box(modifier.size(size.dp), contentAlignment = Alignment.Center) {
        if (!visible) return@Box
        Box(Modifier.size(size.dp).border(1.5.dp, tint.copy(alpha = 0.7f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(GlacierIcons.More, contentDescription = stringResource(R.string.hold_ok_hint), tint = tint, modifier = Modifier.size((size * 0.6f).dp))
        }
    }
}
