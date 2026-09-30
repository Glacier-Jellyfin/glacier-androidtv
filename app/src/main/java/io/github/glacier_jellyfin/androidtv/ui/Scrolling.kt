package io.github.glacier_jellyfin.androidtv.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** Where a focused row settles vertically, and a focused card horizontally (design `sync()`). */
const val RowPivot = 330
const val CardPivot = 120
private const val ROW_TOLERANCE = 48

/**
 * Vertical scrolling of a page made of rows: anything inside the first
 * [topHeight] (spotlight, detail header) scrolls the page back to the top; a
 * focused row settles with its cards [RowPivot] from the top.
 *
 * Moving sideways within a row also asks the page to bring the new card into
 * view, a few pixels off the pivot; correcting that made the page tremble,
 * so offsets within [ROW_TOLERANCE] count as "already in place".
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun rememberRowPivotSpec(listState: LazyListState, topHeight: Int): BringIntoViewSpec {
    val density = LocalDensity.current
    return remember(listState, density, topHeight) {
        val pivot = with(density) { RowPivot.dp.toPx() }
        val tolerance = with(density) { ROW_TOLERANCE.dp.toPx() }
        val top = with(density) { topHeight.dp.toPx() }
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                if (listState.firstVisibleItemIndex == 0) {
                    val scrolled = listState.firstVisibleItemScrollOffset.toFloat()
                    if (offset + scrolled < top) return -scrolled
                }
                val distance = offset - pivot
                return if (abs(distance) < tolerance) 0f else distance
            }
        }
    }
}

/** Whether the last D-pad key moved up or down; set by the activity's key handler. */
object NavDirection {
    @Volatile
    var vertical = false
}

/**
 * Horizontal counterpart: the focused card settles [CardPivot] from the left edge.
 * Arriving from above or below only scrolls a card that is not fully visible,
 * so a row does not jump while the focus merely passes through.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun rememberCardPivotSpec(): BringIntoViewSpec {
    val density = LocalDensity.current
    return remember(density) {
        val pivot = with(density) { CardPivot.dp.toPx() }
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = when {
                !NavDirection.vertical -> offset - pivot
                offset < 0f -> offset
                offset + size > containerSize -> offset + size - containerSize
                else -> 0f
            }
        }
    }
}
