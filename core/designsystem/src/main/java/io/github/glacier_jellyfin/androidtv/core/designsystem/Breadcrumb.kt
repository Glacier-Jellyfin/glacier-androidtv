package io.github.glacier_jellyfin.androidtv.core.designsystem

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import kotlin.math.roundToInt

private const val LEVEL_PAD = 6
private const val UNDERLINE = 3
private const val FOCUS_MS = 180
private const val LEVEL_SCALE = 1.05f
private const val ELLIPSIS = "\u2026"
private const val LEVEL_SIZE = 17
private const val LETTER_SPACING = 0.06f
private const val DOT = 5

/** Added on each side of a dot to the row's 6dp, so a dot has 10dp to the levels. */
private const val DOT_SPACE = 4
private const val CAP_HEIGHT = 0.72f

/**
 * The line above a page title: a back chevron, then the levels from the top down, split by dots.
 * A level with an action takes focus (Left and Right move between them); the page's own level stays plain text.
 * [lowestFocus] is attached to the last level that takes focus, so the page can send Up from its buttons there.
 * Too long, the last level gives up its end; with [startEllipsis] the first levels give way instead
 * (a folder path keeps its nearest folders), shown as a dot-split "…".
 */
@Composable
fun Breadcrumb(
    levels: List<BreadcrumbLevel>,
    modifier: Modifier = Modifier,
    lowestFocus: FocusRequester? = null,
    startEllipsis: Boolean = false,
) {
    if (levels.isEmpty()) return
    if (!startEllipsis) {
        BreadcrumbRow(levels, from = 0, lowestFocus = lowestFocus, modifier = modifier)
        return
    }
    BoxWithConstraints(modifier) {
        val measurer = rememberTextMeasurer()
        val density = LocalDensity.current
        val levelStyle = levelStyle()
        val keep = levels.indexOfLast { it.onClick != null }.takeIf { it >= 0 } ?: levels.lastIndex
        val available = constraints.maxWidth
        val from = remember(levels.map { it.label }, keep, available, levelStyle, density) {
            val trim = with(density) { (LEVEL_SIZE * LETTER_SPACING).sp.roundToPx() }
            fun width(text: String, style: TextStyle) =
                measurer.measure(text, style, maxLines = 1, softWrap = false).size.width - trim
            breadcrumbStart(
                widths = levels.map { width(it.label.uppercase(), levelStyle) },
                keepFrom = keep,
                lead = with(density) { (CHEVRON + CHEVRON_EXTRA).dp.roundToPx() },
                // The dot with its extra space on both sides.
                dot = with(density) { (DOT + 2 * DOT_SPACE).dp.roundToPx() },
                ellipsis = width(ELLIPSIS, levelStyle),
                gap = with(density) { GAP.dp.roundToPx() },
                available = available,
            )
        }
        BreadcrumbRow(levels, from, lowestFocus)
    }
}

private const val CHEVRON = 14
private const val CHEVRON_EXTRA = 2
private const val GAP = 6

@Composable
private fun levelStyle(): TextStyle = GlacierText.body(LEVEL_SIZE).copy(letterSpacing = LETTER_SPACING.em)

/**
 * Reports the text without the letter spacing after its last letter, so the box is the visible glyph run:
 * gaps, underline and scale then start and end at the letters. The spacing sits on the other side in RTL.
 */
private fun Modifier.trimTrailingSpacing(rtl: Boolean): Modifier = layout { measurable, constraints ->
    val trim = (LEVEL_SIZE * LETTER_SPACING).sp.roundToPx()
    val wide = if (constraints.hasBoundedWidth) {
        constraints.copy(maxWidth = constraints.maxWidth + trim)
    } else {
        constraints
    }
    val placeable = measurable.measure(wide)
    layout((placeable.width - trim).coerceAtLeast(0), placeable.height) {
        placeable.place(if (rtl) -trim else 0, 0)
    }
}

@Composable
private fun BreadcrumbRow(
    levels: List<BreadcrumbLevel>,
    from: Int,
    lowestFocus: FocusRequester?,
    modifier: Modifier = Modifier,
) {
    val lowest = levels.indexOfLast { it.onClick != null }
    val first = (from..levels.lastIndex).firstOrNull { levels[it].onClick != null } ?: -1
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    // The dot sits at the middle of the capital letters, which is not quite the middle of the line.
    val measurer = rememberTextMeasurer()
    val style = levelStyle()
    val density = LocalDensity.current
    val dotShift = remember(style, density) {
        val text = measurer.measure("H", style, maxLines = 1)
        val capMiddle = text.firstBaseline - CAP_HEIGHT * with(density) { LEVEL_SIZE.sp.toPx() } / 2
        (capMiddle - text.size.height / 2f).roundToInt()
    }
    Row(
        modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GAP.dp),
    ) {
        Icon(
            GlacierIcons.ChevronLeft,
            contentDescription = null,
            tint = GlacierColors.Mist.copy(alpha = 0.7f),
            modifier = Modifier.padding(end = CHEVRON_EXTRA.dp).size(CHEVRON.dp),
        )
        if (from > 0) {
            Text(
                ELLIPSIS,
                style = style,
                color = GlacierColors.Mist,
                maxLines = 1,
                modifier = Modifier.trimTrailingSpacing(rtl),
            )
            Separator(dotShift)
        }
        for (index in from..levels.lastIndex) {
            if (index > from) Separator(dotShift)
            val last = index == levels.lastIndex
            Level(
                level = levels[index],
                focusRequester = lowestFocus.takeIf { index == lowest },
                // Left and Right stay in the breadcrumb: only Up and Down leave it.
                stopLeft = if (rtl) index == lowest else index == first,
                stopRight = if (rtl) index == first else index == lowest,
                // Only the last level shrinks: a title that is too long loses its end.
                modifier = if (last) Modifier.weight(1f, fill = false) else Modifier,
            )
        }
    }
}

/** A filled dot rather than a glyph, whose side bearings would make the gaps around it uneven. */
@Composable
private fun Separator(shift: Int) {
    Box(
        Modifier
            .offset { IntOffset(0, shift) }
            .padding(horizontal = DOT_SPACE.dp)
            .size(DOT.dp)
            .background(GlacierColors.Ice.copy(alpha = 0.85f), CircleShape),
    )
}

/** Text with 6dp above and below, which a focused level fills with its underline. */
@Composable
private fun Level(
    level: BreadcrumbLevel,
    focusRequester: FocusRequester?,
    stopLeft: Boolean,
    stopRight: Boolean,
    modifier: Modifier,
) {
    val click = level.onClick
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val reduceMotion = LocalReduceMotion.current
    val accent = LocalAccent.current.main
    val scale by animateFloatAsState(
        if (focused && !reduceMotion) LEVEL_SCALE else 1f,
        tween(FOCUS_MS),
        label = "levelScale",
    )
    val underline by animateFloatAsState(
        if (focused) 1f else 0f,
        if (reduceMotion) tween(0) else tween(FOCUS_MS),
        label = "levelUnderline",
    )
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    var box = modifier
    if (click != null) {
        box = box
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusProperties {
                if (stopLeft) left = FocusRequester.Cancel
                if (stopRight) right = FocusRequester.Cancel
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clickable(interactionSource = interaction, indication = null, onClick = click)
            .then(
                if (level.contentDescription != null) {
                    Modifier.semantics { contentDescription = level.contentDescription }
                } else {
                    Modifier
                },
            )
    }
    val base = levelStyle()
    Text(
        level.label.uppercase(),
        style = if (focused) {
            base.copy(shadow = Shadow(accent.copy(alpha = 0.45f), blurRadius = 18f))
        } else {
            base
        },
        color = if (focused) accent else GlacierColors.Mist,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = box
            .drawBehind {
                if (underline <= 0f) return@drawBehind
                val height = UNDERLINE.dp.toPx()
                val width = size.width * underline
                drawRect(
                    accent,
                    topLeft = Offset(if (rtl) size.width - width else 0f, size.height - height),
                    size = Size(width, height),
                )
            }
            .padding(vertical = LEVEL_PAD.dp)
            .trimTrailingSpacing(rtl),
    )
}

/**
 * The first level to show when the levels' [widths] (pixels) do not all fit in [available]: the oldest
 * levels give way to a dot-split "…" of width [ellipsis] until the rest fits, but never past [keepFrom].
 * [lead] is the chevron with its space, [dot] a separator and [gap] the space between elements.
 */
internal fun breadcrumbStart(
    widths: List<Int>,
    keepFrom: Int,
    lead: Int,
    dot: Int,
    ellipsis: Int,
    gap: Int,
    available: Int,
): Int {
    val limit = keepFrom.coerceIn(0, (widths.size - 1).coerceAtLeast(0))
    for (from in 0..limit) {
        val elements = buildList {
            add(lead)
            if (from > 0) {
                add(ellipsis)
                add(dot)
            }
            for (index in from until widths.size) {
                if (index > from) add(dot)
                add(widths[index])
            }
        }
        if (elements.sum() + gap * (elements.size - 1) <= available) return from
    }
    return limit
}
