package io.github.glacier_jellyfin.androidtv.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

val LocalAccent = staticCompositionLocalOf { Accent.Crevasse }

/**
 * Maps the Glacier tokens onto the TV Material theme. Dark only: the design
 * has no light variant.
 */
@Composable
fun GlacierTheme(
    accent: Accent = Accent.Crevasse,
    content: @Composable () -> Unit,
) {
    val colors = darkColorScheme(
        primary = accent.main,
        onPrimary = GlacierColors.Void,
        secondary = accent.main,
        onSecondary = GlacierColors.Void,
        background = GlacierColors.Void,
        onBackground = GlacierColors.Ice,
        surface = GlacierColors.Deep,
        onSurface = GlacierColors.Ice,
        onSurfaceVariant = GlacierColors.Mist,
        border = GlacierColors.GlassBorder,
    )
    CompositionLocalProvider(LocalAccent provides accent) {
        MaterialTheme(colorScheme = colors, content = content)
    }
}
