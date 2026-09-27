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
    val stored = StoredUser(
        serverId = user.serverId,
        userId = user.userId.toString(),
        name = user.name,
        primaryImageTag = user.primaryImageTag,
        accessToken = user.accessToken,
        pin = existing?.pin,
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
)

/** One entry on the "Who's watching?" screen. */
data class Profile(
    val userId: String,
    val name: String,
    val primaryImageTag: String?,
    /** A valid access token is stored; selecting the profile needs no password. */
    val isSignedIn: Boolean,
    val hasPin: Boolean,
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
            hasPin = stored?.pin != null,
        )
    }
    val listed = fromServer.mapTo(HashSet()) { it.userId }
    val localOnly = local.values
        .filterNot { it.userId in listed }
        .sortedByDescending { it.lastUsedAt }
        .map { Profile(it.userId, it.name, it.primaryImageTag, it.accessToken != null, it.pin != null) }
    return fromServer + localOnly
}
