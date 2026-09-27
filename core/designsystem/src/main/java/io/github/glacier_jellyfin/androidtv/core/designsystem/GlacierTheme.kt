package io.github.glacier_jellyfin.androidtv.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Density
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

val LocalAccent = staticCompositionLocalOf { Accent.Crevasse }

/** "Reduce motion": focus without scaling, border and sheen only. */
val LocalReduceMotion = staticCompositionLocalOf { false }

/** Width of the design's layout grid; see [GlacierTheme]. */
const val DesignWidth = 1920f

/**
 * Maps the Glacier tokens onto the TV Material theme. Dark only: the design
 * has no light variant.
 *
 * The design is laid out on a 1920×1080 grid. The theme rescales density so
 * that one dp equals one design pixel on every screen, whatever density the
 * TV reports, and every value from the design can be used unchanged.
 */
@Composable
fun GlacierTheme(
    accent: Accent = Accent.Crevasse,
    reduceMotion: Boolean = false,
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
    val windowWidth = LocalWindowInfo.current.containerSize.width
    val density = if (windowWidth > 0) Density(density = windowWidth / DesignWidth, fontScale = 1f) else LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides density,
        LocalAccent provides accent,
        LocalReduceMotion provides reduceMotion,
    ) {
        MaterialTheme(colorScheme = colors, typography = GlacierTypography, content = content)
    }
}
