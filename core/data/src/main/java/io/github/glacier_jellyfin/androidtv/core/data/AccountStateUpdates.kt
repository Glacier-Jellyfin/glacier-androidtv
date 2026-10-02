package io.github.glacier_jellyfin.androidtv.core.data

import io.github.glacier_jellyfin.androidtv.core.jellyfin.PublicUser
import io.github.glacier_jellyfin.androidtv.core.jellyfin.ServerInfo
import io.github.glacier_jellyfin.androidtv.core.jellyfin.SignedInUser

/*
 * Pure state transitions, kept apart from DataStore so they can be unit tested.
 */

internal fun AccountState.withServer(server: ServerInfo, now: Long): AccountState {
    val stored = StoredServer(server.id, server.name, server.address, server.version, lastUsedAt = now)
    return copy(servers = servers.filterNot { it.id == server.id } + stored, lastServerId = server.id)
}

internal fun AccountState.withSignIn(user: SignedInUser, now: Long): AccountState {
    val key = user.serverId to user.userId.toString()
    val existing = users.firstOrNull { (it.serverId to it.userId) == key }
    // PIN and parental control stay: they belong to this device, not to the sign-in.
    val stored = existing?.copy(
        name = user.name,
        primaryImageTag = user.primaryImageTag,
        accessToken = user.accessToken,
        lastUsedAt = now,
    ) ?: StoredUser(
        serverId = user.serverId,
        userId = user.userId.toString(),
        name = user.name,
        primaryImageTag = user.primaryImageTag,
        accessToken = user.accessToken,
        lastUsedAt = now,
    )
    return copy(users = users.filterNot { (it.serverId to it.userId) == key } + stored, lastServerId = user.serverId)
}

internal fun AccountState.updateUser(serverId: String, userId: String, change: (StoredUser) -> StoredUser): AccountState =
    copy(users = users.map { if (it.serverId == serverId && it.userId == userId) change(it) else it })

internal fun AccountState.withoutServer(serverId: String): AccountState = copy(
    servers = servers.filterNot { it.id == serverId },
    users = users.filterNot { it.serverId == serverId },
    lastServerId = lastServerId.takeUnless { it == serverId },
    startProfile = startProfile.takeUnless { it.serverId == serverId } ?: StartProfile(),
)

/** One entry on the "Who's watching?" screen. */
data class Profile(
    val userId: String,
    val name: String,
    val primaryImageTag: String?,
    /** A valid access token is stored; selecting the profile needs no password. */
    val isSignedIn: Boolean,
    /** Opening the profile asks for its PIN. */
    val pinLocked: Boolean,
)

/**
 * Users the server lists publicly, in the server's order, followed by users
 * who signed in on this device but are hidden from the server's login screen.
 * [publicUsers] is null when the server could not be asked.
 */
fun AccountState.profilesFor(serverId: String, publicUsers: List<PublicUser>?): List<Profile> {
    val local = users.filter { it.serverId == serverId }.associateBy { it.userId }
    val fromServer = publicUsers.orEmpty().map { user ->
        val stored = local[user.id.toString()]
        Profile(
            userId = user.id.toString(),
            name = user.name,
            primaryImageTag = user.primaryImageTag ?: stored?.primaryImageTag,
            isSignedIn = stored?.accessToken != null,
            pinLocked = stored?.pinLocked == true,
        )
    }
    val listed = fromServer.mapTo(HashSet()) { it.userId }
    val localOnly = local.values
        .filterNot { it.userId in listed }
        .sortedByDescending { it.lastUsedAt }
        .map { Profile(it.userId, it.name, it.primaryImageTag, it.accessToken != null, it.pinLocked) }
    return fromServer + localOnly
}

internal val StoredUser.pinLocked: Boolean get() = pin != null && protection.pinOnProfileSwitch

/** What the app does with "Who's watching?" on start. */
sealed interface StartChoice {
    /** Show the profiles as usual. */
    data object Pick : StartChoice

    /** Open this profile without showing the profiles. */
    data class Open(val userId: String) : StartChoice

    /** Show the profiles with this one focused: it needs its PIN first. */
    data class Focus(val userId: String) : StartChoice
}

/**
 * The profile [StartProfile] picks on [serverId] among [profiles] (as listed by
 * [profilesFor]). Only signed-in profiles qualify; nothing qualifying means
 * the profiles are shown.
 */
fun AccountState.startChoice(serverId: String, profiles: List<Profile>): StartChoice {
    val userId = when (startProfile.mode) {
        StartMode.Picker -> null
        StartMode.Single -> profiles.singleOrNull()?.userId
        StartMode.Last -> users.filter { it.serverId == serverId && it.accessToken != null }.maxByOrNull { it.lastUsedAt }?.userId
        StartMode.Fixed -> startProfile.userId.takeIf { startProfile.serverId == serverId }
    }
    return userId?.let { profileChoice(it, profiles) } ?: StartChoice.Pick
}

/** Opening [userId]'s profile right away, as far as it is signed in and needs no PIN. */
fun profileChoice(userId: String, profiles: List<Profile>): StartChoice {
    val profile = profiles.firstOrNull { it.userId == userId && it.isSignedIn } ?: return StartChoice.Pick
    return if (profile.pinLocked) StartChoice.Focus(profile.userId) else StartChoice.Open(profile.userId)
}

/** The server the app starts on: the fixed profile's, otherwise the last one used. */
fun AccountState.startServerId(): String? {
    val fixed = startProfile.serverId.takeIf { startProfile.mode == StartMode.Fixed }
    return listOfNotNull(fixed, lastServerId).firstOrNull { id -> servers.any { it.id == id } }
}
