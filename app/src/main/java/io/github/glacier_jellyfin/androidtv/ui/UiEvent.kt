package io.github.glacier_jellyfin.androidtv.ui

import androidx.annotation.StringRes

/** One-off effects a view model asks its screen to perform. */
sealed interface UiEvent {
    /**
     * [clearBackStack] empties the back stack; [replace] only drops the
     * current screen, so Back skips it.
     */
    data class Navigate(val route: Any, val clearBackStack: Boolean = false, val replace: Boolean = false) : UiEvent
    data class Toast(@StringRes val message: Int, val args: List<Any> = emptyList()) : UiEvent
}
