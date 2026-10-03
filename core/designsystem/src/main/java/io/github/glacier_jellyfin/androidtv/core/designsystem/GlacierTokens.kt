package io.github.glacier_jellyfin.androidtv.core.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Glacier primitives, mirrored from the design's glacier.css (layer 1).
 *
 * Ground, glass, ink and ice light are Glacier's identity and never vary.
 * Only [Accent] may be swapped by the user.
 */
object GlacierColors {
    /** Page ground. */
    val Void = Color(0xFF0A1420)

    /** Raised sheet. */
    val Deep = Color(0xFF12202E)

    val GlassFill = Color(red = 173, green = 214, blue = 224, alpha = (0.09f * 255).toInt())
    val GlassBorder = Color(red = 196, green = 226, blue = 234, alpha = (0.18f * 255).toInt())
    val GlassFill2 = Color(red = 173, green = 214, blue = 224, alpha = (0.13f * 255).toInt())
    val GlassBorder2 = Color(red = 196, green = 226, blue = 234, alpha = (0.28f * 255).toInt())

    val Ice = Color(0xFFE8F4F7)
    val Mist = Color(0xFF8FA6B3)
}

/**
 * The five accents from the design. An accent overrides exactly two colours;
 * adding one is two values and a contrast check against [GlacierColors.Void].
 */
enum class Accent(val main: Color, val deep: Color) {
    Crevasse(Color(0xFF7DC7D9), Color(0xFF4E93A8)),
    BlueIce(Color(0xFF38B6E0), Color(0xFF2186B0)),
    Aurora(Color(0xFF5FE3C0), Color(0xFF2F9E86)),
    PolarNight(Color(0xFF9FB8F0), Color(0xFF657BBA)),
    Firn(Color(0xFFCFE4EC), Color(0xFF8FB3C2)),
}

object GlacierShapes {
    val RadiusSm = 8.dp
    val RadiusMd = 14.dp
    val RadiusLg = 22.dp
}

object GlacierElevation {
    val Blur1 = 14.dp
    val Blur2 = 28.dp
}
