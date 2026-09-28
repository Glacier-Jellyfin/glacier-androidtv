package io.github.glacier_jellyfin.androidtv.core.designsystem

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.dp

/** The Glacier mark: an outlined diamond around a solid core (26-unit grid). */
@Composable
fun GlacierMark(size: Int, modifier: Modifier = Modifier) {
    val accent = LocalAccent.current.main
    Canvas(modifier.size(size.dp)) {
        val unit = this.size.width / 26f
        drawPath(diamond(13f, 12f, unit), accent, style = Stroke(width = 1.2f * unit, join = StrokeJoin.Round))
        drawPath(diamond(13f, 6f, unit), accent)
    }
}

/**
 * Loading indicator from the design (`gTurn` + `gCorePop`): the outline turns
 * in quarter steps while the core pulses.
 */
@Composable
fun SpinningDiamond(size: Int, modifier: Modifier = Modifier) {
    val accent = LocalAccent.current.main
    val transition = rememberInfiniteTransition(label = "diamond")
    val ease = CubicBezierEasing(0.7f, 0f, 0.2f, 1f)
    val turn by transition.animateFloat(
        initialValue = 0f,
        targetValue = 180f,
        animationSpec = infiniteRepeatable(
            keyframes {
                durationMillis = 2400
                0f at 0 using ease
                90f at 840
                90f at 1200 using ease
                180f at 2040
                180f at 2400
            },
        ),
        label = "turn",
    )
    // Design gCorePop: the core shrinks while the diamond turns and pops back,
    // slightly overshooting, the moment it snaps into place (35 % and 85 %).
    val pop by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            keyframes {
                durationMillis = 2400
                1f at 0 using EaseInOut
                0.45f at 240 using EaseInOut
                0.45f at 600 using EaseInOut
                1.12f at 840 using EaseInOut
                1f at 1008 using EaseInOut
                0.45f at 1440 using EaseInOut
                0.45f at 1800 using EaseInOut
                1.12f at 2040 using EaseInOut
                1f at 2208 using EaseInOut
            },
        ),
        label = "pop",
    )
    Canvas(modifier.size(size.dp)) {
        val unit = this.size.width / 26f
        rotate(turn) {
            drawPath(diamond(13f, 12f, unit), accent, style = Stroke(width = 1.2f * unit, join = StrokeJoin.Round))
        }
        val core = this.size.width * 30f / 110f
        scale(pop) {
            rotate(45f) {
                drawCore(accent, core)
            }
        }
    }
}

private fun DrawScope.drawCore(color: androidx.compose.ui.graphics.Color, side: Float) {
    drawRoundRect(
        color = color,
        topLeft = Offset(center.x - side / 2, center.y - side / 2),
        size = Size(side, side),
        cornerRadius = CornerRadius(side / 10),
    )
}

/** Diamond centred at (c, c) with half-diagonal [r], in 26-unit coordinates. */
private fun diamond(c: Float, r: Float, unit: Float) = Path().apply {
    moveTo(c * unit, (c - r) * unit)
    lineTo((c + r) * unit, c * unit)
    lineTo(c * unit, (c + r) * unit)
    lineTo((c - r) * unit, c * unit)
    close()
}
