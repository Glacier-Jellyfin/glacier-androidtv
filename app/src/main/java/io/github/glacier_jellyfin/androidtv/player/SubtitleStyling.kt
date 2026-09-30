package io.github.glacier_jellyfin.androidtv.player

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.accessibility.CaptioningManager
import androidx.annotation.OptIn
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.font.resolveAsTypeface
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import io.github.glacier_jellyfin.androidtv.core.data.settings.SubtitleEdge
import io.github.glacier_jellyfin.androidtv.core.data.settings.SubtitleFont
import io.github.glacier_jellyfin.androidtv.core.data.settings.SubtitlePosition
import io.github.glacier_jellyfin.androidtv.core.data.settings.SubtitleStyle
import io.github.glacier_jellyfin.androidtv.core.data.settings.SubtitleStyleMode
import io.github.glacier_jellyfin.androidtv.core.data.settings.SubtitleWeight
import io.github.glacier_jellyfin.androidtv.core.designsystem.GoogleSans

/** "Auto" follows the Android caption settings: native when the user switched them on (agreed). */
fun SubtitleStyle.usesNative(context: Context): Boolean = when (mode) {
    SubtitleStyleMode.Native -> true
    SubtitleStyleMode.Custom -> false
    SubtitleStyleMode.Auto -> context.getSystemService(CaptioningManager::class.java)?.isEnabled == true
}

/** Text subtitles in the chosen style; ASS keeps its own, drawn by libass. */
@OptIn(UnstableApi::class)
fun SubtitleView.applyStyle(style: SubtitleStyle) {
    if (style.usesNative(context)) {
        setUserDefaultStyle()
        setUserDefaultTextSize()
        return
    }
    setStyle(
        CaptionStyleCompat(
            style.color.argb.toInt(),
            style.background.argb.toInt(),
            Color.TRANSPARENT,
            when (style.edge) {
                SubtitleEdge.None -> CaptionStyleCompat.EDGE_TYPE_NONE
                SubtitleEdge.Raised -> CaptionStyleCompat.EDGE_TYPE_RAISED
                SubtitleEdge.Depressed -> CaptionStyleCompat.EDGE_TYPE_DEPRESSED
                SubtitleEdge.Outline -> CaptionStyleCompat.EDGE_TYPE_OUTLINE
                SubtitleEdge.Shadow -> CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
            },
            Color.BLACK,
            subtitleTypeface(context, style.font, style.weight),
        ),
    )
    setFractionalTextSize(style.size.fraction)
}

/** The typeface the player draws with; the settings preview uses the same one. */
fun subtitleTypeface(context: Context, font: SubtitleFont, weight: SubtitleWeight): Typeface {
    val family = font.family ?: return createFontFamilyResolver(context)
        .resolveAsTypeface(GoogleSans, FontWeight(weight.value)).value
    return Typeface.create(Typeface.create(family, Typeface.NORMAL), weight.value, false)
}

/**
 * Text cues without a position of their own go to the chosen line: negative
 * lines count up from the bottom (-1 is the usual place), line 1 is the top.
 * Positioned cues (signs, PGS pictures) stay where the file puts them.
 */
@OptIn(UnstableApi::class)
internal fun Cue.placed(position: SubtitlePosition): Cue {
    if (bitmap != null || line != Cue.DIMEN_UNSET || position == SubtitlePosition.Bottom1) return this
    // Media3 counts positive lines from 0 at the top.
    val line = if (position.line > 0) position.line - 1 else position.line
    return buildUpon().setLine(line.toFloat(), Cue.LINE_TYPE_NUMBER).build()
}
