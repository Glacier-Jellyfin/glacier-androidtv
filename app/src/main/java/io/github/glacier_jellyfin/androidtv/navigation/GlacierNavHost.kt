package io.github.glacier_jellyfin.androidtv.navigation

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import io.github.glacier_jellyfin.androidtv.home.HomePlaceholderScreen
import io.github.glacier_jellyfin.androidtv.profiles.ProfilesScreen
import io.github.glacier_jellyfin.androidtv.setup.ServerAddressScreen
import io.github.glacier_jellyfin.androidtv.setup.ServerListScreen
import io.github.glacier_jellyfin.androidtv.setup.SignInScreen
import io.github.glacier_jellyfin.androidtv.ui.UiEvent

@Composable
fun GlacierNavHost(navController: NavHostController, startDestination: Any) {
    val navigate: (UiEvent.Navigate) -> Unit = { event ->
        val current = navController.currentDestination?.id
        navController.navigate(event.route) {
            when {
                event.clearBackStack -> popUpTo(0) { inclusive = true }
                event.replace && current != null -> popUpTo(current) { inclusive = true }
            }
            launchSingleTop = true
        }
    }
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
        composable<ServerAddressRoute> { ServerAddressScreen(onNavigate = navigate) }
        composable<SignInRoute> { SignInScreen(onNavigate = navigate) }
        composable<ProfilesRoute> { ProfilesScreen(onNavigate = navigate) }
        composable<HomeRoute> {
            HomePlaceholderScreen(onLeave = { serverId -> navigate(UiEvent.Navigate(ProfilesRoute(serverId), clearBackStack = true)) })
        }
    }
}
