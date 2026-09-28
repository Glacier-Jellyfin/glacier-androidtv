package io.github.glacier_jellyfin.androidtv.player

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.accessibility.CaptioningManager
import androidx.annotation.OptIn
import androidx.core.content.res.ResourcesCompat
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import io.github.glacier_jellyfin.androidtv.core.data.settings.SubtitleEdge
import io.github.glacier_jellyfin.androidtv.core.data.settings.SubtitleFont
import io.github.glacier_jellyfin.androidtv.core.data.settings.SubtitlePosition
import io.github.glacier_jellyfin.androidtv.core.data.settings.SubtitleStyle
import io.github.glacier_jellyfin.androidtv.core.data.settings.SubtitleStyleMode
import io.github.glacier_jellyfin.androidtv.core.designsystem.R as DesignR

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
            Color.TRANSPARENT,
            Color.TRANSPARENT,
            when (style.edge) {
                SubtitleEdge.None -> CaptionStyleCompat.EDGE_TYPE_NONE
                SubtitleEdge.Raised -> CaptionStyleCompat.EDGE_TYPE_RAISED
                SubtitleEdge.Depressed -> CaptionStyleCompat.EDGE_TYPE_DEPRESSED
                SubtitleEdge.Outline -> CaptionStyleCompat.EDGE_TYPE_OUTLINE
                SubtitleEdge.Shadow -> CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
            },
            Color.BLACK,
            subtitleTypeface(context, style.font, style.bold),
        ),
    )
    setFractionalTextSize(style.size.fraction)
}

private fun subtitleTypeface(context: Context, font: SubtitleFont, bold: Boolean): Typeface {
    val base = when (font) {
        SubtitleFont.Default -> ResourcesCompat.getFont(context, DesignR.font.google_sans) ?: Typeface.SANS_SERIF
        SubtitleFont.Serif -> Typeface.SERIF
        SubtitleFont.Monospace -> Typeface.MONOSPACE
    }
    return Typeface.create(base, if (bold) 700 else 400, false)
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
