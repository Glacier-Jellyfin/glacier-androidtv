package io.github.glacier_jellyfin.androidtv.core.designsystem

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale

/**
 * The page ground for screens without a picture: a wide glow in the accent
 * colour from above, fading into near black towards the bottom and the edges.
 */
@Composable
fun GlacierBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val accent = LocalAccent.current.deep
    Box(
        modifier = modifier
            .fillMaxSize()
            .drawBehind {
                drawRect(Ground)
                glow(accent.copy(alpha = 0.42f), centerX = 0.42f, centerY = -0.12f, radiusX = 1500f, radiusY = 1000f, stop = 1f)
                drawRect(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Ground.copy(alpha = 0.7f)))
            },
        content = content,
    )
}

/** CSS `radial-gradient(ellipse rx ry at cx cy, color, transparent stop)`, in design pixels. */
private fun DrawScope.glow(color: Color, centerX: Float, centerY: Float, radiusX: Float, radiusY: Float, stop: Float) {
    val center = Offset(size.width * centerX, size.height * centerY)
    val rx = radiusX.dp2px(this)
    val ry = radiusY.dp2px(this)
    scale(scaleX = 1f, scaleY = ry / rx, pivot = center) {
        drawRect(
            brush = Brush.radialGradient(0f to color, stop to Color.Transparent, center = center, radius = rx),
            topLeft = Offset(0f, center.y - rx),
            size = size.copy(height = rx * 2),
        )
    }
}

private val Ground = Color(0xFF05090D)

private fun Float.dp2px(scope: DrawScope) = this * scope.density
