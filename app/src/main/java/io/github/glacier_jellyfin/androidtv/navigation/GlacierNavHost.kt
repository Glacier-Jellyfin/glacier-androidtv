package io.github.glacier_jellyfin.androidtv.navigation

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import io.github.glacier_jellyfin.androidtv.detail.DetailScreen
import io.github.glacier_jellyfin.androidtv.detail.PersonScreen
import io.github.glacier_jellyfin.androidtv.player.PlayerScreen
import io.github.glacier_jellyfin.androidtv.search.SearchScreen
import io.github.glacier_jellyfin.androidtv.home.HomeScreen
import io.github.glacier_jellyfin.androidtv.library.LibraryScreen
import io.github.glacier_jellyfin.androidtv.music.MusicPlayerScreen
import io.github.glacier_jellyfin.androidtv.profiles.ProfilesScreen
import io.github.glacier_jellyfin.androidtv.settings.SettingsScreen
import io.github.glacier_jellyfin.androidtv.setup.ServerAddressScreen
import io.github.glacier_jellyfin.androidtv.setup.QuickConnectScreen
import io.github.glacier_jellyfin.androidtv.setup.ServerListScreen
import io.github.glacier_jellyfin.androidtv.setup.SignInScreen
import io.github.glacier_jellyfin.androidtv.trailer.TrailerScreen
import io.github.glacier_jellyfin.androidtv.ui.UiEvent

/** Screens without arguments; navigating to one that is already on top does nothing. */
private val SingleScreens = setOf(ServerListRoute, ServerAddressRoute, HomeRoute, SettingsRoute)

@Composable
fun GlacierNavHost(navController: NavHostController, startDestination: Any) {
    val navigate: (UiEvent.Navigate) -> Unit = { event ->
        val current = navController.currentDestination?.id
        navController.navigate(event.route) {
            when {
                event.clearBackStack -> popUpTo(0) { inclusive = true }
                event.replace && current != null -> popUpTo(current) { inclusive = true }
            }
            // Only parameterless screens are kept single: a library, genre or
            // detail page with other arguments is a new screen of the same kind.
            launchSingleTop = event.route in SingleScreens
        }
    }
    val back: () -> Unit = { navController.popBackStack() }
    NavHost(
        navController = navController,
        startDestination = startDestination,
        // The design's screen entrance (gScreen): fade in while rising 14px.
        enterTransition = { fadeIn() + slideInVertically { 14 } },
        exitTransition = { fadeOut() },
        popEnterTransition = { fadeIn() },
        popExitTransition = { fadeOut() },
    ) {
        composable<ServerListRoute> {
            ServerListScreen(onNavigate = navigate, onManualAddress = { navController.navigate(ServerAddressRoute) })
        }
        composable<ServerAddressRoute> { ServerAddressScreen(onNavigate = navigate, onBack = back) }
        composable<SignInRoute> { SignInScreen(onNavigate = navigate) }
        composable<QuickConnectRoute> { QuickConnectScreen(onNavigate = navigate, onBack = back) }
        composable<ProfilesRoute> { ProfilesScreen(onNavigate = navigate) }
        composable<HomeRoute> { HomeScreen(onNavigate = navigate) }
        composable<LibraryRoute> { LibraryScreen(onNavigate = navigate) }
        composable<DetailRoute> { DetailScreen(onNavigate = navigate, onBack = back) }
        composable<PersonRoute> { PersonScreen(onNavigate = navigate) }

        // Later development steps; placeholders keep the navigation testable.
        composable<TrailerRoute> { TrailerScreen(onNavigate = navigate, onBack = back) }
        composable<PlayerRoute> { PlayerScreen(onBack = back) }
        composable<MusicRoute> { MusicPlayerScreen(onBack = back) }
        composable<SearchRoute> { SearchScreen(onNavigate = navigate) }
        composable<SettingsRoute> { SettingsScreen(onNavigate = navigate) }
    }
}
