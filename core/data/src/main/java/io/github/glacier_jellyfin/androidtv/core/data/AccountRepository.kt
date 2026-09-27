package io.github.glacier_jellyfin.androidtv.core.data

import androidx.datastore.core.DataStore
import io.github.glacier_jellyfin.androidtv.core.jellyfin.ServerInfo
import io.github.glacier_jellyfin.androidtv.core.jellyfin.SignedInUser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AccountRepository @Inject constructor(
    private val store: DataStore<AccountState>,
) {
    val state: Flow<AccountState> = store.data

    suspend fun current(): AccountState = store.data.first()

    suspend fun rememberServer(server: ServerInfo) {
        store.updateData { it.withServer(server, now()) }
    }

    suspend fun rememberSignIn(user: SignedInUser) {
        store.updateData { it.withSignIn(user, now()) }
    }

    suspend fun markUsed(serverId: String, userId: String) {
        store.updateData { it.updateUser(serverId, userId) { user -> user.copy(lastUsedAt = now()) } }
    }

    /** Keeps the profile listed but drops its credential. */
    suspend fun forgetToken(serverId: String, userId: String) {
        store.updateData { it.updateUser(serverId, userId) { user -> user.copy(accessToken = null) } }
    }

    suspend fun setPin(serverId: String, userId: String, pin: String?) {
        val hash = pin?.let { Pins.hash(it) }
        store.updateData { it.updateUser(serverId, userId) { user -> user.copy(pin = hash) } }
    }

    suspend fun verifyPin(serverId: String, userId: String, pin: String): Boolean {
        val stored = current().users.firstOrNull { it.serverId == serverId && it.userId == userId }?.pin ?: return true
        return Pins.verify(pin, stored)
    }

    suspend fun removeServer(serverId: String) {
        store.updateData { it.withoutServer(serverId) }
    }

    private fun now() = System.currentTimeMillis()
}
