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
 * The page ground: void colour with two off-screen glows, like light
 * scattering inside ice (glacier.css, --jf-palette-background-defaultImage).
 */
@Composable
fun GlacierBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .drawBehind {
                drawRect(GlacierColors.Void)
                glow(GlacierColors.GlowNear, centerX = 0.12f, centerY = -0.08f, radiusX = 900f, radiusY = 520f, stop = 0.60f)
                glow(GlacierColors.GlowFar, centerX = 1.08f, centerY = 0.26f, radiusX = 800f, radiusY = 620f, stop = 0.55f)
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

private fun Float.dp2px(scope: DrawScope) = this * scope.density
