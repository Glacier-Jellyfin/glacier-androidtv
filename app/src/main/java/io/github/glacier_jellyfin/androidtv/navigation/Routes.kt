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

/**
 * "Who's watching?"; [appStart] lets the start profile setting open a profile by itself,
 * or [userId] (the profile of a title picked on the Android TV home screen) instead.
 */
@Serializable
data class ProfilesRoute(val serverId: String, val appStart: Boolean = false, val userId: String? = null)

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

/** [themeArea] is the show of an episode opened from its show, known before the page loads (theme song). */
@Serializable
data class DetailRoute(val itemId: String, val themeArea: String? = null)

/** The video player; with [queueOf] (a playlist, or an artist for its music videos) the next video follows. */
@Serializable
data class PlayerRoute(val itemId: String, val fromStart: Boolean = false, val queueOf: String? = null)

/** Trailers of a library title ([itemId]), or of a Seerr title ([seerrType] and [tmdbId]) not in the library yet. */
@Serializable
data class TrailerRoute(val itemId: String? = null, val seerrType: String? = null, val tmdbId: Int? = null)

/**
 * The music player, queueing every song of an album, artist or playlist
 * ([sourceId]); [startTrackId] plays first, else the first song (a random
 * one with shuffle on). Without [sourceId] it shows what plays (mini player).
 */
@Serializable
data class MusicRoute(val sourceId: String? = null, val startTrackId: String? = null)

/**
 * A cast member; [fromTitle] and [role] feed the breadcrumb ("Dracula · Count Dracula").
 * Without [personId], the person is a TMDB cast member ([tmdbId], [name]) from a
 * Seerr title: the library's page when the library knows them, else TMDB's.
 */
@Serializable
data class PersonRoute(
    val personId: String? = null,
    val fromTitle: String? = null,
    val role: String? = null,
    val tmdbId: Int? = null,
    val name: String? = null,
)

@Serializable
data class SearchRoute(val query: String? = null)

/** A movie or show to request through Seerr; [type] is a SeerrMediaType name. */
@Serializable
data class SeerrRoute(val type: String, val tmdbId: Int)

/** Everything marked with the heart. */
@Serializable
data object FavoritesRoute

@Serializable
data object SettingsRoute
