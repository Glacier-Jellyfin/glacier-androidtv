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
 * The accents, in spectrum order (red to violet, then the colourless Firn) as
 * the settings show them. The five from the design are joined by four more
 * for the hues they leave out, at the same lightness. An accent overrides exactly
 * two colours; adding one is two values and a contrast check against
 * [GlacierColors.Void], which is also the text on both of them.
 */
enum class Accent(val main: Color, val deep: Color) {
    Alpenglow(Color(0xFFF2909A), Color(0xFFBD6670)),
    Dawn(Color(0xFFF5AC7E), Color(0xFFBF7A4E)),
    MidnightSun(Color(0xFFF0D47C), Color(0xFFB39745)),
    Aurora(Color(0xFF5FE3C0), Color(0xFF2F9E86)),
    Crevasse(Color(0xFF7DC7D9), Color(0xFF4E93A8)),
    BlueIce(Color(0xFF38B6E0), Color(0xFF2186B0)),
    PolarNight(Color(0xFF9FB8F0), Color(0xFF657BBA)),
    Twilight(Color(0xFFC4A9F2), Color(0xFF8B70C2)),
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
