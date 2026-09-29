package io.github.glacier_jellyfin.androidtv.core.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Parental control of the signed-in profile. */
data class ProfileLock(
    val protection: Protection = Protection(),
    val hasPin: Boolean = false,
    val pinChangedAt: Long? = null,
)

/**
 * The signed-in profile's [Protection] and PIN, plus what the PIN unlocked in
 * this session: the Account settings and single titles. Unlocks end with the
 * session (profile switch, sign-out, app restart).
 */
@Singleton
class ParentalControl @Inject constructor(
    private val accounts: AccountRepository,
    private val sessions: SessionManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val lock: StateFlow<ProfileLock> = combine(sessions.session, accounts.state) { session, state ->
        val user = session?.let { s -> state.users.firstOrNull { it.serverId == s.server.id && it.userId == s.user.userId } }
        user?.let { ProfileLock(it.protection, it.pin != null, it.pinChangedAt) } ?: ProfileLock()
    }.stateIn(scope, SharingStarted.Eagerly, ProfileLock())

    private val _settingsUnlocked = MutableStateFlow(false)
    val settingsUnlocked: StateFlow<Boolean> = _settingsUnlocked.asStateFlow()

    private val _unlockedItems = MutableStateFlow<Set<String>>(emptySet())
    val unlockedItems: StateFlow<Set<String>> = _unlockedItems.asStateFlow()

    init {
        scope.launch {
            sessions.session.distinctUntilChangedBy { it?.server?.id to it?.user?.userId }.collect {
                _settingsUnlocked.value = false
                _unlockedItems.value = emptySet()
            }
        }
    }

    suspend fun verifyPin(pin: String): Boolean {
        val (serverId, userId) = activeUser() ?: return false
        return accounts.verifyPin(serverId, userId, pin)
    }

    suspend fun setPin(pin: String) {
        val (serverId, userId) = activeUser() ?: return
        accounts.setPin(serverId, userId, pin)
    }

    suspend fun updateProtection(transform: (Protection) -> Protection) {
        val (serverId, userId) = activeUser() ?: return
        accounts.updateProtection(serverId, userId, transform)
    }

    fun unlockSettings() {
        _settingsUnlocked.value = true
    }

    fun unlock(itemId: String) = _unlockedItems.update { it + itemId }

    private fun activeUser(): Pair<String, String>? = sessions.session.value?.let { it.server.id to it.user.userId }
}
