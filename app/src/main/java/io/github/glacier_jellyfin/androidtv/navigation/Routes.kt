package io.github.glacier_jellyfin.androidtv.navigation

import kotlinx.serialization.Serializable

/** Server discovery and saved servers (design: "Einrichtung"). */
@Serializable
data object ServerListRoute

/** Manual server address entry. */
@Serializable
data object ServerAddressRoute

/** Username/password sign-in, plus Quick Connect when the server allows it. */
@Serializable
data class SignInRoute(val serverId: String, val username: String? = null)

/** "Who's watching?" */
@Serializable
data class ProfilesRoute(val serverId: String)

@Serializable
data object HomeRoute

/** Movies, shows or music across all libraries of that kind, or one library when [libraryId] is set. */
@Serializable
data class LibraryRoute(
    val kind: String,
    val libraryId: String? = null,
    /** Library or genre name shown as the title; the kind name when null. */
    val title: String? = null,
    val genreId: String? = null,
)

@Serializable
data class DetailRoute(val itemId: String)

@Serializable
data class PlayerRoute(val itemId: String)

@Serializable
data object SearchRoute

@Serializable
data object SettingsRoute
