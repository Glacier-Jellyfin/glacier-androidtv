package io.github.glacier_jellyfin.androidtv.core.designsystem

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

/** Focus scale from the design (`focusScale` 1.07). */
const val FocusScale = 1.07f

/**
 * The design's focus treatment (`fx()` in the prototype): scale up, accent
 * border, a soft 4px accent ring and a drop shadow. With "reduce motion" the
 * scale is dropped and only border and ring remain.
 *
 * [content] receives the focus state so labels can switch to the accent colour.
 */
@Composable
fun GlacierClickable(
    onClick: () -> Unit,
    shape: Shape,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    unfocusedBorder: Color = Color.Transparent,
    borderWidth: Int = 2,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Box(
        modifier = modifier
            .focusScale(focused)
            .focusFrame(focused, shape, unfocusedBorder, borderWidth)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick),
        contentAlignment = contentAlignment,
    ) {
        content(focused)
    }
}

/**
 * A media card: the whole card (image and captions) scales on focus, while
 * [focusFrame] is applied by the content to the image only, as in the design.
 */
@Composable
fun GlacierCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.(focused: Boolean) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Column(
        modifier = modifier
            .focusScale(focused)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
    ) {
        content(focused)
    }
}

/** Scales up while focused, unless "reduce motion" is on. */
@Composable
fun Modifier.focusScale(focused: Boolean): Modifier {
    val scale by animateFloatAsState(
        targetValue = if (focused && !LocalReduceMotion.current) FocusScale else 1f,
        animationSpec = tween(200),
        label = "focusScale",
    )
    return graphicsLayer { scaleX = scale; scaleY = scale }
}

/** Accent border, 4px ring and drop shadow while focused; [unfocusedBorder] otherwise. */
@Composable
fun Modifier.focusFrame(
    focused: Boolean,
    shape: Shape,
    unfocusedBorder: Color = Color.Transparent,
    borderWidth: Int = 2,
): Modifier {
    val accent = LocalAccent.current.main
    return this
        .then(if (focused) Modifier.focusRing(shape, accent) else Modifier)
        .border(borderWidth.dp, if (focused) accent else unfocusedBorder, shape)
}

/** `box-shadow: 0 0 0 4px accent/0.3, 0 22px 48px -16px rgba(0,0,0,.7)`. */
private fun Modifier.focusRing(shape: Shape, accent: Color): Modifier = this
    .dropShadow(shape, Shadow(radius = 48.dp, spread = (-16).dp, color = Color.Black.copy(alpha = 0.7f), offset = DpOffset(0.dp, 22.dp)))
    .drawWithContent {
        val ring = 4.dp.toPx()
        val outline = shape.createOutline(Size(size.width + ring, size.height + ring), layoutDirection, this)
        translate(-ring / 2, -ring / 2) {
            drawOutline(outline, accent.copy(alpha = 0.3f), style = Stroke(width = ring))
        }
        drawContent()
    }
