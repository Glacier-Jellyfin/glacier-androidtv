package io.github.glacier_jellyfin.androidtv.core.designsystem

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
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

object GlacierText {
    /** Display headings, e.g. "Willkommen" (60) or screen titles (44). */
    fun display(size: Int) = TextStyle(
        fontFamily = GoogleSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = size.sp,
        letterSpacing = 0.005.em,
    )

    fun body(size: Int, weight: FontWeight = FontWeight.Normal) =
        TextStyle(fontFamily = GoogleSans, fontWeight = weight, fontSize = size.sp)

    /** Small uppercase labels ("Server address", step chips). */
    fun label(size: Int, spacing: Double = 0.06) = TextStyle(
        fontFamily = GoogleSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = size.sp,
        letterSpacing = spacing.em,
    )

    fun mono(size: Int) = TextStyle(fontFamily = GlacierMono, fontSize = size.sp)
}

internal val GlacierTypography = Typography().let { base ->
    Typography(
        displayLarge = base.displayLarge.copy(fontFamily = GoogleSans),
        displayMedium = base.displayMedium.copy(fontFamily = GoogleSans),
        displaySmall = base.displaySmall.copy(fontFamily = GoogleSans),
        headlineLarge = base.headlineLarge.copy(fontFamily = GoogleSans),
        headlineMedium = base.headlineMedium.copy(fontFamily = GoogleSans),
        headlineSmall = base.headlineSmall.copy(fontFamily = GoogleSans),
        titleLarge = base.titleLarge.copy(fontFamily = GoogleSans),
        titleMedium = base.titleMedium.copy(fontFamily = GoogleSans),
        titleSmall = base.titleSmall.copy(fontFamily = GoogleSans),
        bodyLarge = base.bodyLarge.copy(fontFamily = GoogleSans),
        bodyMedium = base.bodyMedium.copy(fontFamily = GoogleSans),
        bodySmall = base.bodySmall.copy(fontFamily = GoogleSans),
        labelLarge = base.labelLarge.copy(fontFamily = GoogleSans),
        labelMedium = base.labelMedium.copy(fontFamily = GoogleSans),
        labelSmall = base.labelSmall.copy(fontFamily = GoogleSans),
    )
}
