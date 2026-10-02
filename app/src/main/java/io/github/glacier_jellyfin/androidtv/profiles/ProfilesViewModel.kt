package io.github.glacier_jellyfin.androidtv.profiles

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.data.AccountRepository
import io.github.glacier_jellyfin.androidtv.core.data.Profile
import io.github.glacier_jellyfin.androidtv.core.data.SessionManager
import io.github.glacier_jellyfin.androidtv.core.data.StartChoice
import io.github.glacier_jellyfin.androidtv.core.data.StartMode
import io.github.glacier_jellyfin.androidtv.core.data.StoredServer
import io.github.glacier_jellyfin.androidtv.core.data.profilesFor
import io.github.glacier_jellyfin.androidtv.core.data.startChoice
import io.github.glacier_jellyfin.androidtv.core.designsystem.PinKey
import io.github.glacier_jellyfin.androidtv.core.jellyfin.Authenticator
import io.github.glacier_jellyfin.androidtv.core.jellyfin.PublicUser
import io.github.glacier_jellyfin.androidtv.navigation.HomeRoute
import io.github.glacier_jellyfin.androidtv.navigation.ProfilesRoute
import io.github.glacier_jellyfin.androidtv.navigation.ServerListRoute
import io.github.glacier_jellyfin.androidtv.navigation.SignInRoute
import io.github.glacier_jellyfin.androidtv.ui.UiEvent
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class ProfileCard(val profile: Profile, val imageUrl: String?)

data class ProfilesState(
    val server: StoredServer? = null,
    val profiles: List<ProfileCard> = emptyList(),
    val loading: Boolean = true,
    /** The server did not answer; only profiles known on this device are shown. */
    val offline: Boolean = false,
    val pinFor: Profile? = null,
    val pin: String = "",
    val pinWrong: Boolean = false,
    /** The start profile is being opened; the profiles stay hidden meanwhile. */
    val starting: Boolean = false,
    /** The profile to focus first; otherwise the first one. */
    val focusUserId: String? = null,
)

@HiltViewModel
class ProfilesViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val accounts: AccountRepository,
    private val authenticator: Authenticator,
    private val sessions: SessionManager,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<ProfilesRoute>()
    private val serverId = route.serverId

    private val _state = MutableStateFlow(ProfilesState(starting = route.appStart))
    val state: StateFlow<ProfilesState> = _state.asStateFlow()

    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private val publicUsers = MutableStateFlow<List<PublicUser>?>(null)

    init {
        viewModelScope.launch {
            val server = accounts.current().servers.firstOrNull { it.id == serverId }
            if (server == null) {
                _events.send(UiEvent.Navigate(ServerListRoute, clearBackStack = true))
                return@launch
            }
            _state.update { it.copy(server = server) }
            // Only "the single profile" needs the server's list; the other start modes decide right away.
            if (route.appStart && accounts.current().startProfile.mode != StartMode.Single && start(publicUsers = null)) return@launch
            val users = runCatching { authenticator.publicUsers(server.address) }
            publicUsers.value = users.getOrNull()
            // Nobody to pick (no public users, nobody signed in here before): go straight to sign-in.
            if (users.getOrNull()?.isEmpty() == true && accounts.current().users.none { it.serverId == serverId }) {
                _events.send(UiEvent.Navigate(SignInRoute(serverId), replace = true))
                return@launch
            }
            if (_state.value.starting) start(users.getOrNull())
            _state.update { it.copy(loading = false, offline = users.isFailure) }
        }
        viewModelScope.launch {
            combine(accounts.state, publicUsers) { state, public -> state.profilesFor(serverId, public) }
                .collect { profiles ->
                    val address = _state.value.server?.address
                    _state.update { state ->
                        state.copy(profiles = profiles.map { ProfileCard(it, address?.let { a -> imageUrl(a, it) }) })
                    }
                }
        }
    }

    /** Opens the start profile, or focuses it when it needs its PIN (see [startChoice]). Returns true when it opened. */
    private suspend fun start(publicUsers: List<PublicUser>?): Boolean {
        val state = accounts.current()
        val profiles = state.profilesFor(serverId, publicUsers)
        when (val choice = state.startChoice(serverId, profiles)) {
            StartChoice.Pick -> Unit
            is StartChoice.Focus -> _state.update { it.copy(focusUserId = choice.userId) }
            // Stays hidden on the way to Home; a revoked token lands on sign-in, and Back shows the profiles.
            is StartChoice.Open -> if (open(profiles.first { it.userId == choice.userId })) return true
        }
        _state.update { it.copy(starting = false) }
        return false
    }

    fun select(profile: Profile) {
        if (!profile.isSignedIn) {
            navigate(SignInRoute(serverId, profile.name))
            return
        }
        if (profile.pinLocked) {
            _state.update { it.copy(pinFor = profile, pin = "", pinWrong = false) }
            return
        }
        viewModelScope.launch { open(profile) }
    }

    fun otherUser() = navigate(SignInRoute(serverId))

    fun changeServer() = navigate(ServerListRoute)

    fun dismissPin() = _state.update { it.copy(pinFor = null, pin = "", pinWrong = false) }

    fun pinKey(key: PinKey) {
        val current = _state.value
        val profile = current.pinFor ?: return
        val pin = when (key) {
            is PinKey.Digit -> (current.pin + key.value).take(4)
            PinKey.Delete -> current.pin.dropLast(1)
            PinKey.Confirm -> current.pin.also {
                if (it.length < 4) viewModelScope.launch { _events.send(UiEvent.Toast(R.string.pin_required)) }
            }
        }
        _state.update { it.copy(pin = pin, pinWrong = false) }
        if (pin.length < 4) return
        viewModelScope.launch {
            // Short pause so the fourth dot is visible before the result.
            delay(260)
            if (accounts.verifyPin(serverId, profile.userId, pin)) {
                dismissPin()
                open(profile)
            } else {
                _state.update { it.copy(pin = "", pinWrong = true) }
            }
        }
    }

    /** Returns true when the profile opened (and Home is on its way). */
    private suspend fun open(profile: Profile): Boolean {
        val server = _state.value.server ?: return false
        val token = accounts.current().users.firstOrNull { it.serverId == serverId && it.userId == profile.userId }?.accessToken
        // A revoked token sends the user to sign-in; an unreachable server does not.
        val valid = token != null && runCatching { authenticator.isTokenValid(server.address, token) }.getOrDefault(true)
        if (!valid) {
            accounts.forgetToken(serverId, profile.userId)
            navigate(SignInRoute(serverId, profile.name))
            return false
        }
        if (!sessions.open(serverId, profile.userId)) return false
        _events.send(UiEvent.Navigate(HomeRoute, clearBackStack = true))
        return true
    }

    private fun navigate(route: Any) {
        viewModelScope.launch { _events.send(UiEvent.Navigate(route)) }
    }

    private fun imageUrl(address: String, profile: Profile): String? =
        runCatching { UUID.fromString(profile.userId) }.getOrNull()
            ?.let { authenticator.userImageUrl(address, it, profile.primaryImageTag) }
}
