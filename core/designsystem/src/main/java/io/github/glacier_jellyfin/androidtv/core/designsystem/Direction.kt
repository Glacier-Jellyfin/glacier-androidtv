package io.github.glacier_jellyfin.androidtv.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

/*
 * Right-to-left languages such as Arabic mirror the layout: rows run from the right, and
 * Compose flips padding, alignment and offsets by itself. What it cannot flip are the arrow
 * keys a screen reads by hand and gradients drawn with fixed edges; the helpers here cover
 * those. Timelines and playback controls keep their direction in every language (as in
 * Material's bidirectionality guidance), so they sit inside [LeftToRight].
 */

/** The arrow key towards the start of a line: Left, or Right in a right-to-left layout. */
val LayoutDirection.startKey: Key
    get() = if (this == LayoutDirection.Rtl) Key.DirectionRight else Key.DirectionLeft

/** The arrow key towards the end of a line: Right, or Left in a right-to-left layout. */
val LayoutDirection.endKey: Key
    get() = if (this == LayoutDirection.Rtl) Key.DirectionLeft else Key.DirectionRight

/** -1 for the key towards the start of a line, 1 towards the end, null for any other key. */
fun LayoutDirection.horizontalStep(key: Key): Int? = when (key) {
    startKey -> -1
    endKey -> 1
    else -> null
}

/** Lays out [content] from left to right in every language, for timelines and playback controls. */
@Composable
fun LeftToRight(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr, content = content)
}

/** A horizontal gradient that starts at the start edge: the left, or the right in a right-to-left layout. */
fun Modifier.startGradient(vararg colorStops: Pair<Float, Color>): Modifier = drawBehind {
    val brush = if (layoutDirection == LayoutDirection.Rtl) {
        Brush.horizontalGradient(*colorStops, startX = size.width, endX = 0f)
    } else {
        Brush.horizontalGradient(*colorStops, startX = 0f, endX = size.width)
    }
    drawRect(brush)
}

/** [startGradient] with evenly spread [colors]. */
fun Modifier.startGradient(colors: List<Color>): Modifier =
    startGradient(*colors.mapIndexed { index, color -> index.toFloat() / (colors.size - 1).coerceAtLeast(1) to color }.toTypedArray())
