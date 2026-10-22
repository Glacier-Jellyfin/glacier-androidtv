package io.github.glacier_jellyfin.androidtv.navigation

import io.github.glacier_jellyfin.androidtv.core.data.SessionManager
import io.github.glacier_jellyfin.androidtv.ui.NavTarget
import io.github.glacier_jellyfin.androidtv.ui.UiEvent

/**
 * Where an entry of the navigation bar leads, for every screen that shows the bar.
 * Null when [target] is [current], the screen already open, or when no one is
 * signed in. The profile entry ends the session and goes to "Who's watching?".
 */
fun SessionManager.navEvent(target: NavTarget, current: NavTarget?): UiEvent.Navigate? {
    if (target == current) return null
    return when (target) {
        NavTarget.Home -> UiEvent.Navigate(HomeRoute, clearBackStack = true)
        NavTarget.Search -> UiEvent.Navigate(SearchRoute())
        NavTarget.Settings -> UiEvent.Navigate(SettingsRoute)
        is NavTarget.Library -> UiEvent.Navigate(LibraryRoute(target.kind.name))
        NavTarget.Favorites -> UiEvent.Navigate(FavoritesRoute)
        NavTarget.NowPlaying -> UiEvent.Navigate(MusicRoute())
        NavTarget.LiveTv -> UiEvent.Navigate(LiveTvRoute)
        NavTarget.Profile -> {
            val serverId = session.value?.server?.id ?: return null
            leave()
            UiEvent.Navigate(ProfilesRoute(serverId), clearBackStack = true)
        }
    }
}
