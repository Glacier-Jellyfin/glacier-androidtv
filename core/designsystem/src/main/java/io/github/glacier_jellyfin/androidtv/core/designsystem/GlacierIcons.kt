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
    val Star = filled("M12 2l3.09 6.26L22 9.27l-5 4.87 1.18 6.88L12 17.77l-6.18 3.25L7 14.14 2 9.27l6.91-1.01z")

    private fun icon(vararg paths: String, strokeWidth: Float = 2f): ImageVector =
        build(paths.toList(), fill = false, strokeWidth = strokeWidth)

    /** Solid shape; tinted the same way as line icons. */
    private fun filled(path: String, strokeWidth: Float = 0f): ImageVector =
        build(listOf(path), fill = true, strokeWidth = strokeWidth)

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
