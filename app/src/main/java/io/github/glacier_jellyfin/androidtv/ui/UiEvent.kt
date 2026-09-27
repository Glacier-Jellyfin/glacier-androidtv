package io.github.glacier_jellyfin.androidtv.ui

import androidx.annotation.StringRes

/** One-off effects a view model asks its screen to perform. */
sealed interface UiEvent {
    data class Navigate(val route: Any, val clearBackStack: Boolean = false) : UiEvent
    data class Toast(@StringRes val message: Int, val args: List<Any> = emptyList()) : UiEvent
}
