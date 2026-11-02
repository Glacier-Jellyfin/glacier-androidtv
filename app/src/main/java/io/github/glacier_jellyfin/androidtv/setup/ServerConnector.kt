package io.github.glacier_jellyfin.androidtv.setup

import io.github.glacier_jellyfin.androidtv.core.data.AccountRepository
import io.github.glacier_jellyfin.androidtv.core.data.serversReplacedBy
import io.github.glacier_jellyfin.androidtv.core.data.settings.SettingsRepository
import io.github.glacier_jellyfin.androidtv.core.jellyfin.Authenticator
import io.github.glacier_jellyfin.androidtv.core.jellyfin.ServerInfo
import io.github.glacier_jellyfin.androidtv.core.jellyfin.runSuspendCatching
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
    private val settings: SettingsRepository,
) {
    suspend fun connect(server: ServerInfo): Any {
        adopt(server)
        accounts.rememberServer(server)
        val publicUsers = runSuspendCatching { authenticator.publicUsers(server.address) }.getOrDefault(emptyList())
        val knownUsers = accounts.current().users.any { it.serverId == server.id }
        return if (publicUsers.isNotEmpty() || knownUsers) ProfilesRoute(server.id) else SignInRoute(server.id)
    }

    /**
     * [server] answers at the address of a saved server under another id: it was set up
     * again there. Profiles whose sign-in it still accepts move over to it, with PIN,
     * parental control and settings; the others go with the old entry. Returns the old
     * user ids that moved, mapped to the new ones.
     */
    suspend fun adopt(server: ServerInfo): Map<String, String> {
        val moved = mutableMapOf<String, String>()
        for (old in accounts.current().serversReplacedBy(server)) {
            val kept = accounts.current().users.filter { it.serverId == old.id }.mapNotNull { user ->
                val token = user.accessToken ?: return@mapNotNull null
                runSuspendCatching { authenticator.tokenUserId(server.address, token) }.getOrNull()?.let { user.userId to it }
            }.toMap()
            accounts.replaceServer(old.id, server, kept)
            settings.moveServer(old.id, server.id, kept)
            moved += kept
        }
        return moved
    }
}
