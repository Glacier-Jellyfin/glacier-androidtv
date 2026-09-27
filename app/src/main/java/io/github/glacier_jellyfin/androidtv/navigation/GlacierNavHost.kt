package io.github.glacier_jellyfin.androidtv.navigation

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.home.HomeScreen
import io.github.glacier_jellyfin.androidtv.profiles.ProfilesScreen
import io.github.glacier_jellyfin.androidtv.settings.SettingsPlaceholderScreen
import io.github.glacier_jellyfin.androidtv.setup.ServerAddressScreen
import io.github.glacier_jellyfin.androidtv.setup.ServerListScreen
import io.github.glacier_jellyfin.androidtv.setup.SignInScreen
import io.github.glacier_jellyfin.androidtv.ui.ComingSoonScreen
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
        composable<ServerAddressRoute> { ServerAddressScreen(onNavigate = navigate) }
        composable<SignInRoute> { SignInScreen(onNavigate = navigate) }
        composable<ProfilesRoute> { ProfilesScreen(onNavigate = navigate) }
        composable<HomeRoute> { HomeScreen(onNavigate = navigate) }

        // Later development steps; placeholders keep the navigation testable.
        composable<LibraryRoute> { ComingSoonScreen(stringResource(R.string.home_my_media), onBack = back) }
        composable<DetailRoute> { ComingSoonScreen(stringResource(R.string.hero_more_info), onBack = back) }
        composable<PlayerRoute> { ComingSoonScreen(stringResource(R.string.hero_play), onBack = back) }
        composable<SearchRoute> { ComingSoonScreen(stringResource(R.string.nav_search), onBack = back) }
        composable<SettingsRoute> {
            SettingsPlaceholderScreen(
                onBack = back,
                onSignedOut = { serverId -> navigate(UiEvent.Navigate(ProfilesRoute(serverId), clearBackStack = true)) },
            )
        }
    }
}
