package io.github.glacier_jellyfin.androidtv.ui

import androidx.annotation.StringRes

/** One-off effects a view model asks its screen to perform. */
sealed interface UiEvent {
    /**
     * [clearBackStack] empties the back stack; [replace] only drops the
     * current screen, so Back skips it. [leaveTitles] drops every title and
     * cast page on top, so Back goes to where the first of them was opened from.
     * [focusNav] asks the new screen to leave the focus on its navigation entry, as after a
     * pick in the navigation bar (only the home screen reads it).
     */
    data class Navigate(
        val route: Any,
        val clearBackStack: Boolean = false,
        val replace: Boolean = false,
        val leaveTitles: Boolean = false,
        val focusNav: Boolean = false,
    ) : UiEvent
    data class Toast(@StringRes val message: Int, val args: List<Any> = emptyList()) : UiEvent
}
