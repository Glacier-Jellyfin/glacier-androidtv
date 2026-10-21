package io.github.glacier_jellyfin.androidtv.core.data

import androidx.datastore.core.DataStore
import io.github.glacier_jellyfin.androidtv.core.jellyfin.ServerInfo
import io.github.glacier_jellyfin.androidtv.core.jellyfin.SignedInUser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
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

    suspend fun setPin(serverId: String, userId: String, pin: String) {
        val hash = withContext(Dispatchers.Default) { Pins.hash(pin) }
        store.updateData { it.updateUser(serverId, userId) { user -> user.copy(pin = hash, pinChangedAt = now()) } }
    }

    /** Drops the PIN and every lock that needs it; the age limit stays. */
    suspend fun removePin(serverId: String, userId: String) {
        store.updateData {
            it.updateUser(serverId, userId) { user ->
                user.copy(
                    pin = null,
                    pinChangedAt = null,
                    protection = user.protection.copy(pinOnProfileSwitch = false, pinForLocked = false, pinForSettings = false),
                )
            }
        }
    }

    suspend fun updateProtection(serverId: String, userId: String, transform: (Protection) -> Protection) {
        store.updateData { it.updateUser(serverId, userId) { user -> user.copy(protection = transform(user.protection)) } }
    }

    suspend fun verifyPin(serverId: String, userId: String, pin: String): Boolean {
        val stored = current().users.firstOrNull { it.serverId == serverId && it.userId == userId }?.pin ?: return true
        // Hashing takes a moment on slow TVs; off the main thread the keys keep coming.
        val ok = withContext(Dispatchers.Default) { Pins.verify(pin, stored) }
        if (ok && Pins.outdated(stored)) {
            val hash = withContext(Dispatchers.Default) { Pins.hash(pin) }
            store.updateData { it.updateUser(serverId, userId) { user -> user.copy(pin = hash) } }
        }
        return ok
    }

    suspend fun setStartProfile(startProfile: StartProfile) {
        store.updateData { it.copy(startProfile = startProfile) }
    }

    suspend fun removeServer(serverId: String) {
        store.updateData { it.withoutServer(serverId) }
    }

    private fun now() = System.currentTimeMillis()
}
