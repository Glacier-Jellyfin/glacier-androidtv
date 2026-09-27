package io.github.glacier_jellyfin.androidtv.setup

import io.github.glacier_jellyfin.androidtv.core.data.AccountRepository
import io.github.glacier_jellyfin.androidtv.core.jellyfin.Authenticator
import io.github.glacier_jellyfin.androidtv.core.jellyfin.ServerInfo
import io.github.glacier_jellyfin.androidtv.navigation.ProfilesRoute
import io.github.glacier_jellyfin.androidtv.navigation.SignInRoute
import javax.inject.Inject

/**
 * Shared last step of choosing a server, from the list or by address:
 * remember it, then continue with "Who's watching?" when there is anyone to
 * pick, otherwise straight to sign-in.
 */
class ServerConnector @Inject constructor(
    private val accounts: AccountRepository,
    private val authenticator: Authenticator,
) {
    suspend fun connect(server: ServerInfo): Any {
        accounts.rememberServer(server)
        val publicUsers = runCatching { authenticator.publicUsers(server.address) }.getOrDefault(emptyList())
        val knownUsers = accounts.current().users.any { it.serverId == server.id }
        return if (publicUsers.isNotEmpty() || knownUsers) ProfilesRoute(server.id) else SignInRoute(server.id)
    }
}
