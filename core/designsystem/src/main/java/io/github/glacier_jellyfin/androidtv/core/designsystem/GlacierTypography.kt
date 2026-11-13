package io.github.glacier_jellyfin.androidtv.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Typography

private fun googleSans(weight: FontWeight) = Font(
    resId = R.font.google_sans,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

/** Google Sans, variable weight 400–700. One family: hierarchy comes from weight. */
val GoogleSans = FontFamily(
    googleSans(FontWeight.Normal),
    googleSans(FontWeight.Medium),
    googleSans(FontWeight.SemiBold),
    googleSans(FontWeight.Bold),
)

/** Monospace for addresses, versions and codes. */
val GlacierMono = FontFamily.Monospace

/*
 * Every text reads in the direction of its own first letter, as Android's views do. A right-to-left
 * layout then still shows an English title, "2 h 4 min" or "0.1.0-beta.1" the right way round.
 */
private val OwnDirection = TextDirection.Content

/*
 * The text lines up with the layout, not with its own direction: an English title in a right-to-left
 * layout still starts at the right edge, next to the rest of the row. Inside [LeftToRight] that is the left.
 */
@Composable
@ReadOnlyComposable
private fun layoutAlign(): TextAlign = if (LocalLayoutDirection.current ==
    LayoutDirection.Rtl
) {
    TextAlign.Right
} else {
    TextAlign.Left
}

object GlacierText {
    /** Display headings, e.g. "Willkommen" (60) or screen titles (44). */
    @Composable
    @ReadOnlyComposable
    fun display(size: Int) = TextStyle(
        fontFamily = GoogleSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = size.sp,
        letterSpacing = 0.005.em,
        textDirection = OwnDirection,
        textAlign = layoutAlign(),
    )

    @Composable
    @ReadOnlyComposable
    fun body(size: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
        fontFamily = GoogleSans,
        fontWeight = weight,
        fontSize = size.sp,
        textDirection = OwnDirection,
        textAlign = layoutAlign(),
    )

    /** Small uppercase labels ("Server address", step chips). */
    @Composable
    @ReadOnlyComposable
    fun label(size: Int, spacing: Double = 0.06) = TextStyle(
        fontFamily = GoogleSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = size.sp,
        letterSpacing = spacing.em,
        textDirection = OwnDirection,
        textAlign = layoutAlign(),
    )

    @Composable
    @ReadOnlyComposable
    fun mono(size: Int) = TextStyle(
        fontFamily = GlacierMono,
        fontSize = size.sp,
        textDirection = OwnDirection,
        textAlign = layoutAlign(),
    )
}

internal val GlacierTypography = Typography().let { base ->
    Typography(
        displayLarge = base.displayLarge.copy(fontFamily = GoogleSans, textDirection = OwnDirection),
        displayMedium = base.displayMedium.copy(fontFamily = GoogleSans, textDirection = OwnDirection),
        displaySmall = base.displaySmall.copy(fontFamily = GoogleSans, textDirection = OwnDirection),
        headlineLarge = base.headlineLarge.copy(fontFamily = GoogleSans, textDirection = OwnDirection),
        headlineMedium = base.headlineMedium.copy(fontFamily = GoogleSans, textDirection = OwnDirection),
        headlineSmall = base.headlineSmall.copy(fontFamily = GoogleSans, textDirection = OwnDirection),
        titleLarge = base.titleLarge.copy(fontFamily = GoogleSans, textDirection = OwnDirection),
        titleMedium = base.titleMedium.copy(fontFamily = GoogleSans, textDirection = OwnDirection),
        titleSmall = base.titleSmall.copy(fontFamily = GoogleSans, textDirection = OwnDirection),
        bodyLarge = base.bodyLarge.copy(fontFamily = GoogleSans, textDirection = OwnDirection),
        bodyMedium = base.bodyMedium.copy(fontFamily = GoogleSans, textDirection = OwnDirection),
        bodySmall = base.bodySmall.copy(fontFamily = GoogleSans, textDirection = OwnDirection),
        labelLarge = base.labelLarge.copy(fontFamily = GoogleSans, textDirection = OwnDirection),
        labelMedium = base.labelMedium.copy(fontFamily = GoogleSans, textDirection = OwnDirection),
        labelSmall = base.labelSmall.copy(fontFamily = GoogleSans, textDirection = OwnDirection),
    )
}
