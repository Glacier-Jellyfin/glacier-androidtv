package io.github.glacier_jellyfin.androidtv.core.designsystem

import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color

/**
 * Line icons on a 24-unit grid, drawn like the design's inline SVGs
 * (round caps and joins). Tint them with `Icon(tint = …)`.
 */
object GlacierIcons {
    val Backspace = icon(
        "M10 5a2 2 0 0 0-1.344.519l-6.328 5.74a1 1 0 0 0 0 1.481l6.328 5.741A2 2 0 0 0 10 19h10a2 2 0 0 0 2-2V7a2 2 0 0 0-2-2z",
        "m12 9 6 6",
        "m18 9-6 6",
    )
    val Keyboard = icon(
        "M4 4h16a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z",
        "M6 8h.01", "M10 8h.01", "M14 8h.01", "M18 8h.01",
        "M8 12h.01", "M12 12h.01", "M16 12h.01",
        "M7 16h10",
    )
    val Shift = icon("M9 18v-6H5l7-7 7 7h-4v6H9z")
    val Server = icon(
        "M5 3h14a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z",
        "M5 14h14a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-3a2 2 0 0 1 2-2z",
        "M7 6.5h.01",
        "M7 17.5h.01",
    )
    val Plus = icon("M5 12h14", "M12 5v14")
    val Globe = icon(
        "M12 3a9 9 0 1 1 0 18 9 9 0 0 1 0-18z",
        "M3 12h18",
        "M12 3a14 14 0 0 1 0 18a14 14 0 0 1 0-18",
        strokeWidth = 1.9f,
    )
    val User = icon("M12 4a4 4 0 1 1 0 8 4 4 0 0 1 0-8z", "M4 21a8 8 0 0 1 16 0", strokeWidth = 1.9f)
    val Password = icon(
        "M6 10h12a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2v-7a2 2 0 0 1 2-2z",
        "M8 10V7a4 4 0 0 1 8 0v3",
        strokeWidth = 1.9f,
    )
    val Bolt = icon("M13 2 4 14h7l-1 8 9-12h-7z", strokeWidth = 2.2f)
    val ChevronRight = icon("m9 18 6-6-6-6")
    val Lock = icon(
        "M5 11h14a2 2 0 0 1 2 2v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-7a2 2 0 0 1 2-2z",
        "M7 11V7a5 5 0 0 1 10 0v4",
    )

    val Search = icon("M11 3a8 8 0 1 1 0 16 8 8 0 0 1 0-16z", "m21 21-4.3-4.3")
    val Settings = icon(
        "M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2z",
        "M12 9a3 3 0 1 1 0 6 3 3 0 0 1 0-6z",
    )
    private const val HEART = "M12 20.3C10.3 19 3.6 14.4 3.6 9.6 3.6 6.9 5.7 5 8.1 5c1.6 0 3 .8 3.9 2.1C12.9 5.8 14.3 5 15.9 5c2.4 0 4.5 1.9 4.5 4.6 0 4.8-6.7 9.4-8.4 10.7z"
    val Heart = icon(HEART, strokeWidth = 1.8f)
    val HeartFilled = filled(HEART, strokeWidth = 1.8f)
    val Play = filled("M6 3 20 12 6 21z")
    val Pause = filled("M7 4h2a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1H7a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1zM15 4h2a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1h-2a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1z")
    val Replay = icon("M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8", "M3 3v5h5")
    val Forward = icon("M21 12a9 9 0 1 1-9-9c2.52 0 4.93 1 6.74 2.74L21 8", "M21 3v5h-5")
    /** Previous and next episode (design: triangle with a bar). */
    val SkipBack = filled("M19 20 9 12 19 4z", "M5 19V5", strokeWidth = 2f)
    val SkipForward = filled("M5 4 15 12 5 20z", "M19 5v14", strokeWidth = 2f)
    val Close = icon("M18 6 6 18", "m6 6 12 12")
    val Info = icon("M12 3a9 9 0 1 1 0 18 9 9 0 0 1 0-18z", "M12 16v-4", "M12 8h.01")
    val More = icon("M5 12h.01", "M12 12h.01", "M19 12h.01", strokeWidth = 3.4f)
    val Shuffle = icon(
        "m18 14 4 4-4 4", "m18 2 4 4-4 4",
        "M2 18h1.973a4 4 0 0 0 3.3-1.7l5.454-7.6a4 4 0 0 1 3.3-1.7H22",
        "M2 6h1.972a4 4 0 0 1 3.126 1.5l.302.5",
        "M22 18h-6.041a4 4 0 0 1-3.3-1.8l-.359-.45",
    )
    private val REPEAT = arrayOf("m17 2 4 4-4 4", "M3 11v-1a4 4 0 0 1 4-4h14", "m7 22-4-4 4-4", "M21 13v1a4 4 0 0 1-4 4H3")
    val Repeat = icon(*REPEAT)
    val RepeatOne = icon(*REPEAT, "M11 10h1v4")
    /** Lyrics (a singer's microphone). */
    val Lyrics = icon(
        "m11 7.601-5.994 8.19a1 1 0 0 0 .1 1.298l.817.818a1 1 0 0 0 1.314.087L15.09 12",
        "M16.5 21.174C15.5 20.5 14.372 20 13 20c-2.058 0-3.928 2.356-6 2-2.072-.356-2.775-3.369-1.5-4.5",
        "M16 2a5 5 0 1 1 0 10 5 5 0 0 1 0-10z",
    )
    val Film = icon(
        "M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2z",
        "M7 3v18", "M3 7.5h4", "M3 12h18", "M3 16.5h4", "M17 3v18", "M17 7.5h4", "M17 16.5h4",
        strokeWidth = 2.2f,
    )
    val Chapters = icon("M3 5h.01", "M3 12h.01", "M3 19h.01", "M8 5h13", "M8 12h13", "M8 19h13")
    val Check = icon("M20 6 9 17l-5-5", strokeWidth = 3.4f)
    val SortLines = icon("M4 7h16", "M7 12h10", "M10 17h4", strokeWidth = 2.2f)
    val ArrowDown = icon("M12 5v14", "m6 13 6 6 6-6", strokeWidth = 2.4f)
    val ChevronDown = icon("m6 9 6 6 6-6", strokeWidth = 2.4f)
    val ChevronLeft = icon("m15 18-6-6 6-6", strokeWidth = 2.4f)
    val Speaker = icon(
        "M11 4.7a.7.7 0 0 0-1.2-.5L6.4 7.6A1.4 1.4 0 0 1 5.4 8H3a1 1 0 0 0-1 1v6a1 1 0 0 0 1 1h2.4a1.4 1.4 0 0 1 1 .4l3.4 3.4a.7.7 0 0 0 1.2-.5z",
        "M16 9a5 5 0 0 1 0 6",
        "M19.4 18.4a9 9 0 0 0 0-12.8",
    )
    val Subtitles = icon(
        "M5 5h14a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2z",
        "M7 13h4", "M15 13h2", "M7 9h2", "M13 9h4",
    )
    private const val CIRCLE = "M12 3a9 9 0 1 1 0 18 9 9 0 0 1 0-18z"
    private const val SEEN_CHECK = "M8.2 12.3l2.5 2.6 5.1-5.4"
    val Seen = icon(CIRCLE, SEEN_CHECK, strokeWidth = 1.9f)
    val SeenFilled = filled(CIRCLE, strokeWidth = 1.8f)
    /** Drawn over [SeenFilled] in the button's background colour. */
    val SeenMark = icon(SEEN_CHECK, strokeWidth = 2.1f)
    val Star = filled("M12 2l3.09 6.26L22 9.27l-5 4.87 1.18 6.88L12 17.77l-6.18 3.25L7 14.14 2 9.27l6.91-1.01z")

    private fun icon(vararg paths: String, strokeWidth: Float = 2f): ImageVector =
        build(paths.toList(), fill = false, strokeWidth = strokeWidth)

    /** Solid shape; tinted the same way as line icons. */
    private fun filled(vararg paths: String, strokeWidth: Float = 0f): ImageVector =
        build(paths.toList(), fill = true, strokeWidth = strokeWidth)

    private fun build(paths: List<String>, fill: Boolean, strokeWidth: Float): ImageVector =
        ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .apply {
                paths.forEach { data ->
                    addPath(
                        pathData = addPathNodes(data),
                        fill = if (fill) SolidColor(Color.White) else null,
                        stroke = if (strokeWidth > 0f) SolidColor(Color.White) else null,
                        strokeLineWidth = strokeWidth,
                        strokeLineCap = StrokeCap.Round,
                        strokeLineJoin = StrokeJoin.Round,
                    )
                }
            }
            .build()
}
