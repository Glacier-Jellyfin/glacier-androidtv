package io.github.glacier_jellyfin.androidtv.core.designsystem

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged

/**
 * The focus memory of one horizontal row.
 *
 * The first entry from above or below lands on the row's first item. After that the row
 * remembers the item that had the focus and enters there again. [lastKey] is saved, so the
 * memory survives the row leaving the composition and the page sitting in the back stack.
 */
@Stable
class RowFocus {
    /** Key of the item that had the focus last; null until the row was entered. */
    var lastKey: Any? = null

    /** Key of the item at index 0; set while the row's content is built. */
    var firstKey: Any? = null

    internal val requesters = mutableMapOf<Any, FocusRequester>()

    /** Forgets the remembered item, so the next entry starts at the first one again. */
    fun reset() {
        lastKey = null
    }

    internal fun lastRequester(): FocusRequester? = lastKey?.let { requesters[it] }

    internal fun firstRequester(): FocusRequester? = firstKey?.let { requesters[it] }
}

/** A [RowFocus] that keeps its [RowFocus.lastKey] across disposal and the back stack. */
@Composable
fun rememberRowFocus(): RowFocus = rememberSaveable(
    saver = Saver<RowFocus, Any>(
        save = { it.lastKey },
        restore = { saved -> RowFocus().also { it.lastKey = saved } },
    ),
) { RowFocus() }

/**
 * Picks where the focus goes when a row is entered: the remembered item, else [entry],
 * else the first item. Null leaves the choice to Compose. Pure, so it can be tested.
 */
internal fun <T : Any> pickRowTarget(
    last: T?,
    entry: T?,
    first: T?,
): T? = last ?: entry ?: first

/**
 * Makes the row a focus group that lands on [focus]'s remembered item when it is entered
 * with Up, Down or programmatically. The first entry goes to [entry] if given, else to the
 * first item. Left and right keep Compose's default. The redirect is synchronous.
 */
fun Modifier.rowFocus(focus: RowFocus, entry: FocusRequester? = null): Modifier = this
    .focusProperties {
        onEnter = {
            val vertical = requestedFocusDirection == FocusDirection.Up ||
                requestedFocusDirection == FocusDirection.Down ||
                requestedFocusDirection == FocusDirection.Enter
            if (vertical) {
                pickRowTarget(focus.lastRequester(), entry, focus.firstRequester())?.requestFocus()
            }
        }
    }
    .focusGroup()

/**
 * Marks an item of a [rowFocus] row: it takes part in the focus memory under [key].
 * Set [first] on the item at index 0 of rows that are not built with [rememberingFocus].
 */
@Composable
fun Modifier.rowFocusItem(
    focus: RowFocus,
    key: Any,
    first: Boolean = false,
): Modifier {
    val requester = remember(key) { FocusRequester() }
    DisposableEffect(focus, key) {
        focus.requesters[key] = requester
        onDispose { if (focus.requesters[key] === requester) focus.requesters.remove(key) }
    }
    if (first) SideEffect { focus.firstKey = key }
    return this
        .focusRequester(requester)
        .onFocusChanged { if (it.hasFocus) focus.lastKey = key }
}

/**
 * A scope that wraps every item of a lazy row in a [rowFocusItem] box, so the row needs no
 * changes in its content. Unkeyed items get the key "index-N".
 */
fun LazyListScope.rememberingFocus(focus: RowFocus): LazyListScope = RememberingScope(this, focus)

private class RememberingScope(private val delegate: LazyListScope, private val rowFocus: RowFocus) :
    LazyListScope by delegate {
    private var next = 0

    init {
        rowFocus.firstKey = null
    }

    override fun item(
        key: Any?,
        contentType: Any?,
        content: @Composable LazyItemScope.() -> Unit,
    ) {
        val focus = rowFocus
        val index = next++
        val itemKey = key ?: "index-$index"
        if (index == 0) rowFocus.firstKey = itemKey
        delegate.item(key, contentType) {
            val scope = this
            Box(Modifier.rowFocusItem(focus, itemKey)) { scope.content() }
        }
    }

    override fun items(
        count: Int,
        key: ((index: Int) -> Any)?,
        contentType: (index: Int) -> Any?,
        itemContent: @Composable LazyItemScope.(index: Int) -> Unit,
    ) {
        val focus = rowFocus
        val base = next
        next += count
        if (base == 0 && count > 0) rowFocus.firstKey = key?.invoke(0) ?: "index-0"
        delegate.items(count, key, contentType) { index ->
            val scope = this
            val itemKey = key?.invoke(index) ?: "index-${base + index}"
            Box(Modifier.rowFocusItem(focus, itemKey)) { scope.itemContent(index) }
        }
    }
}
