package io.github.glacier_jellyfin.androidtv.ui

import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent

/**
 * While a full-screen loader shows, nothing behind it is meant to be reachable:
 * the remote would otherwise move focus to (or press) controls the user cannot
 * see. Every key is swallowed except Back, so leaving the screen still works.
 * Put it on an ancestor of everything that can hold focus.
 *
 * Key events only pass through ancestors of the focused node, so a page with no
 * focusable content yet (nothing to hold focus) takes focus itself with
 * [takeFocus]; the page's own initial focus logic runs once the content is there.
 * [pass] lists further keys that still go through (Back and Escape always do).
 * Players already hold focus on their controls and leave it off.
 */
@Composable
fun Modifier.blockKeysWhile(active: Boolean, takeFocus: Boolean = false, pass: Set<Key> = emptySet()): Modifier {
    if (!active) return this
    val requester = remember { FocusRequester() }
    if (takeFocus) LaunchedEffect(Unit) { runCatching { requester.requestFocus() } }
    return this
        .then(if (takeFocus) Modifier.focusRequester(requester).focusable() else Modifier)
        .onPreviewKeyEvent { it.key != Key.Back && it.key != Key.Escape && it.key !in pass }
}
