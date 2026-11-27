package io.github.glacier_jellyfin.androidtv.activity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import io.github.glacier_jellyfin.androidtv.core.data.media.ActivityTitle
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierColors
import io.github.glacier_jellyfin.androidtv.core.designsystem.GlacierText
import io.github.glacier_jellyfin.androidtv.ui.MediaRow
import io.github.glacier_jellyfin.androidtv.ui.PageEdge

/**
 * Where the focus goes on an activity page: the first card, or after Back the card or person that was
 * opened. [last] is saved, so the place survives the page sitting in the back stack. Keys read "row/id".
 */
@Stable
class ActivityFocus(var last: String?) {
    /** The page's list: asking it for the focus lands on the first card of the first row. */
    val list = FocusRequester()
    private val requesters = mutableMapOf<String, FocusRequester>()

    /** The card that holds the focus right now, else null. */
    private var focused: String? = null

    /** Until [restore] is done, and after [freeze], a card the page focuses on its own must not overwrite [last]. */
    private var restored = false

    /** The requester of the card [key], made on first use. */
    internal fun requester(key: String): FocusRequester = requesters.getOrPut(key) { FocusRequester() }

    /** The card [key] gained or lost the focus. */
    internal fun changed(key: String, hasFocus: Boolean) {
        if (hasFocus) {
            if (restored) last = key
            focused = key
        } else if (focused == key) {
            focused = null
        }
    }

    /**
     * Puts the focus back on the remembered card, else on the first one. [rows] are the row names in list
     * order below the title, which the list scrolls to first, as a row off the screen has no cards.
     */
    suspend fun restore(listState: LazyListState, rows: List<String>) {
        try {
            place(listState, rows, key = last)
        } finally {
            restored = true
            focused?.let { last = it }
        }
    }

    private suspend fun place(
        listState: LazyListState,
        rows: List<String>,
        key: String?,
    ) {
        val row = key?.substringBefore('/')?.let(rows::indexOf)?.takeIf { it >= 0 }
        if (row != null) listState.scrollToItem(row + 1)
        // The cards of a row exist a few frames after the list is shown, and the loader that held the focus
        // can drop it again after a first request, so ask until a card still holds it a frame later.
        repeat(FOCUS_FRAMES) {
            withFrameNanos { }
            if (holds(key)) return
            // Nothing remembered: the first card of the first row, as the list itself may not pass the focus on.
            val requester = if (key != null) requesters[key] else firstCard(rows.first())
            if (requester != null) runCatching { requester.requestFocus() }
        }
        if (!holds(key)) runCatching { list.requestFocus() }
    }

    /** The page is left: focus moves from now on until the next [restore] are not the user's. */
    fun freeze() {
        restored = false
    }

    /** Whether the remembered card [key] holds the focus, or with nothing remembered any card. */
    private fun holds(key: String?): Boolean = if (key == null) focused != null else focused == key

    /** The first card of [row] that was laid out, cards register in row order. */
    private fun firstCard(row: String): FocusRequester? =
        requesters.entries.firstOrNull { it.key.startsWith("$row/") }?.value
}

/** Marks a card of the row [row] as [id] for the focus memory of [focus]. */
fun Modifier.remembered(
    focus: ActivityFocus,
    row: String,
    id: String,
): Modifier {
    val key = "$row/$id"
    return focusRequester(focus.requester(key)).onFocusChanged { focus.changed(key, it.hasFocus) }
}

@Composable
fun rememberActivityFocus(): ActivityFocus = rememberSaveable(
    saver = Saver<ActivityFocus, String>(save = { it.last }, restore = { ActivityFocus(it) }),
) { ActivityFocus(null) }

/** A row of 16:9 cards, one per title. [card] draws a title and gets the modifier of the focus memory. */
fun LazyListScope.titleRow(
    row: String,
    heading: String,
    titles: List<ActivityTitle>,
    focus: ActivityFocus,
    card: @Composable (ActivityTitle, Modifier) -> Unit,
) {
    if (titles.isEmpty()) return
    item(key = row) {
        MediaRow(title = heading) {
            items(titles, key = { it.key }) { title -> card(title, Modifier.remembered(focus, row, title.key)) }
        }
    }
}

/** Nothing to show: a line and what to do about it, in the middle of the page. */
@Composable
fun ActivityMessage(text: String, body: String) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = PageEdge.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
    ) {
        Text(text, style = GlacierText.display(30), color = GlacierColors.Ice)
        Text(
            body,
            style = GlacierText.body(20),
            color = GlacierColors.Mist,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(900.dp),
        )
    }
}

/** Frames to wait for the cards before the focus falls back to the first one. */
private const val FOCUS_FRAMES = 30
