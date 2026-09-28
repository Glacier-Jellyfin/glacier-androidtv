package io.github.glacier_jellyfin.androidtv.navigation

import kotlinx.serialization.Serializable

/** Server discovery and saved servers (design: "Einrichtung"). */
@Serializable
data object ServerListRoute

/** Manual server address entry. */
@Serializable
data object ServerAddressRoute

/** Username/password sign-in, with a way to Quick Connect when the server allows it. */
@Serializable
data class SignInRoute(val serverId: String, val username: String? = null)

/** Sign-in by approving a code on another device. */
@Serializable
data class QuickConnectRoute(val serverId: String)

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
data class PlayerRoute(val itemId: String, val fromStart: Boolean = false)

@Serializable
data class TrailerRoute(val itemId: String)

/**
 * The music player, queueing every song of an album, artist or playlist
 * ([sourceId]); [startTrackId] plays first, else the first song (a random
 * one with shuffle on).
 */
@Serializable
data class MusicRoute(val sourceId: String, val startTrackId: String? = null)

/** A cast member; [fromTitle] and [role] feed the breadcrumb ("Dracula · Count Dracula"). */
@Serializable
data class PersonRoute(val personId: String, val fromTitle: String? = null, val role: String? = null)

@Serializable
data class SearchRoute(val query: String? = null)

@Serializable
data object SettingsRoute
