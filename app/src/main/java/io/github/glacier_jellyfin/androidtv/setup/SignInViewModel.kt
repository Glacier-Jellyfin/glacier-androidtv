package io.github.glacier_jellyfin.androidtv.setup

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.data.AccountRepository
import io.github.glacier_jellyfin.androidtv.core.data.SessionManager
import io.github.glacier_jellyfin.androidtv.core.jellyfin.Authenticator
import io.github.glacier_jellyfin.androidtv.core.jellyfin.QuickConnectState
import io.github.glacier_jellyfin.androidtv.core.jellyfin.ServerInfo
import io.github.glacier_jellyfin.androidtv.core.jellyfin.SignInResult
import io.github.glacier_jellyfin.androidtv.core.jellyfin.SignedInUser
import io.github.glacier_jellyfin.androidtv.navigation.HomeRoute
import io.github.glacier_jellyfin.androidtv.navigation.SignInRoute
import io.github.glacier_jellyfin.androidtv.ui.UiEvent
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class SignInField { Username, Password }

data class SignInState(
    val server: ServerInfo? = null,
    val username: String = "",
    val password: String = "",
    val field: SignInField = SignInField.Username,
    val busy: Boolean = false,
    /** Current Quick Connect code; null while unavailable. */
    val quickConnectCode: String? = null,
)

@HiltViewModel
class SignInViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val accounts: AccountRepository,
    private val authenticator: Authenticator,
    private val sessions: SessionManager,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<SignInRoute>()

    private val _state = MutableStateFlow(
        SignInState(
            username = route.username.orEmpty(),
            field = if (route.username != null) SignInField.Password else SignInField.Username,
        ),
    )
    val state: StateFlow<SignInState> = _state.asStateFlow()

    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var quickConnect: Job? = null

    init {
        viewModelScope.launch {
            val stored = accounts.current().servers.firstOrNull { it.id == route.serverId } ?: return@launch
            val server = ServerInfo(stored.id, stored.name, stored.address, stored.version)
            _state.update { it.copy(server = server) }
            startQuickConnect(server)
        }
    }

    fun selectField(field: SignInField) = _state.update { it.copy(field = field) }

    fun setText(value: String) = _state.update {
        if (it.field == SignInField.Username) it.copy(username = value) else it.copy(password = value)
    }

    fun clearField() = setText("")

    fun signIn() {
        val current = _state.value
        val server = current.server ?: return
        if (current.username.isBlank()) {
            viewModelScope.launch { _events.send(UiEvent.Toast(R.string.signin_username_required)) }
            _state.update { it.copy(field = SignInField.Username) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            when (val result = authenticator.signIn(server, current.username.trim(), current.password)) {
                is SignInResult.Success -> finish(result.user)
                SignInResult.InvalidCredentials -> {
                    _state.update { it.copy(busy = false, password = "", field = SignInField.Password) }
                    _events.send(UiEvent.Toast(R.string.signin_invalid))
                }
                is SignInResult.Failed -> {
                    _state.update { it.copy(busy = false) }
                    _events.send(UiEvent.Toast(R.string.signin_failed, listOf(result.cause.message ?: result.cause.javaClass.simpleName)))
                }
            }
        }
    }

    private fun startQuickConnect(server: ServerInfo) {
        quickConnect?.cancel()
        quickConnect = viewModelScope.launch {
            authenticator.quickConnect(server).collect { qc ->
                when (qc) {
                    is QuickConnectState.WaitingForApproval -> _state.update { it.copy(quickConnectCode = qc.code) }
                    is QuickConnectState.Authorized -> finish(qc.user)
                    QuickConnectState.Unavailable, is QuickConnectState.Failed -> _state.update { it.copy(quickConnectCode = null) }
                }
            }
        }
    }

    private suspend fun finish(user: SignedInUser) {
        quickConnect?.cancel()
        accounts.rememberSignIn(user)
        sessions.open(user.serverId, user.userId.toString())
        _events.send(UiEvent.Navigate(HomeRoute, clearBackStack = true))
    }
}
