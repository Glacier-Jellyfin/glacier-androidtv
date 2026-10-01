package io.github.glacier_jellyfin.androidtv.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Text
import kotlinx.coroutines.delay

private const val MARQUEE_PAUSE_MS = 1200L
private const val MARQUEE_DP_PER_SECOND = 45f

/**
 * One line of text that ends in "…" when it does not fit; while [active] (the card is
 * focused) a cut-off text instead slides to its end and back again, pausing at each end.
 */
@Composable
fun MarqueeText(text: String, style: TextStyle, color: Color, active: Boolean, modifier: Modifier = Modifier) {
    var overflows by remember(text) { mutableStateOf(false) }
    if (active && overflows) {
        val scroll = rememberScrollState()
        val density = LocalDensity.current
        LaunchedEffect(text, scroll.maxValue) {
            val distance = scroll.maxValue
            if (distance <= 0) return@LaunchedEffect
            val durationMs = (with(density) { distance.toDp().value } / MARQUEE_DP_PER_SECOND * 1000).toInt()
            while (true) {
                delay(MARQUEE_PAUSE_MS)
                scroll.animateScrollTo(distance, tween(durationMs, easing = LinearEasing))
                delay(MARQUEE_PAUSE_MS)
                scroll.animateScrollTo(0, tween(durationMs, easing = LinearEasing))
            }
        }
        Text(text, style = style, color = color, maxLines = 1, softWrap = false, modifier = modifier.horizontalScroll(scroll, enabled = false))
    } else {
        Text(
            text,
            style = style,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { overflows = it.hasVisualOverflow },
            modifier = modifier,
        )
    }
}
