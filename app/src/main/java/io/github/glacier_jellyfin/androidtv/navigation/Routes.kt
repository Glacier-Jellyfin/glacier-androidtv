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
