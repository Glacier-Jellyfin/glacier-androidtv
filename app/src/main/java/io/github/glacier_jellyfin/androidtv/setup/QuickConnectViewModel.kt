package io.github.glacier_jellyfin.androidtv.setup

import io.github.glacier_jellyfin.androidtv.core.log.Log
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
import io.github.glacier_jellyfin.androidtv.core.jellyfin.SignedInUser
import io.github.glacier_jellyfin.androidtv.navigation.HomeRoute
import io.github.glacier_jellyfin.androidtv.navigation.QuickConnectRoute
import io.github.glacier_jellyfin.androidtv.ui.UiEvent
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class QuickConnectUiState(
    val server: ServerInfo? = null,
    /** Six digits; null until the server has handed out a code. */
    val code: String? = null,
    val secondsLeft: Int = QUICK_CONNECT_TTL_SECONDS,
    /** Set when Quick Connect stopped working; the screen then goes back. */
    val closed: Boolean = false,
)

/** How long the server keeps a code (Jellyfin's QuickConnectManager: 10 minutes). */
const val QUICK_CONNECT_TTL_SECONDS = 600

@HiltViewModel
class QuickConnectViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val accounts: AccountRepository,
    private val authenticator: Authenticator,
    private val sessions: SessionManager,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<QuickConnectRoute>()

    private val _state = MutableStateFlow(QuickConnectUiState())
    val state: StateFlow<QuickConnectUiState> = _state.asStateFlow()

    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var session: Job? = null
    private var countdown: Job? = null

    init {
        viewModelScope.launch {
            val stored = accounts.current().servers.firstOrNull { it.id == route.serverId } ?: return@launch
            val server = ServerInfo(stored.id, stored.name, stored.address, stored.version)
            _state.update { it.copy(server = server) }
            newCode()
        }
    }

    /** "New code", and automatically when the current one runs out. */
    fun newCode() {
        val server = _state.value.server ?: return
        session?.cancel()
        _state.update { it.copy(code = null, secondsLeft = QUICK_CONNECT_TTL_SECONDS) }
        session = viewModelScope.launch {
            authenticator.quickConnect(server).collect { qc ->
                when (qc) {
                    is QuickConnectState.WaitingForApproval -> {
                        _state.update { it.copy(code = qc.code, secondsLeft = QUICK_CONNECT_TTL_SECONDS) }
                        startCountdown()
                    }
                    // Finish outside this job: finish() cancels it, which would abort the sign-in itself.
                    is QuickConnectState.Authorized -> viewModelScope.launch { finish(qc.user) }
                    QuickConnectState.Unavailable -> close(R.string.quick_connect_unavailable)
                    is QuickConnectState.Failed -> {
                        Log.w(TAG, "Quick Connect failed", qc.cause)
                        close(R.string.quick_connect_failed)
                    }
                }
            }
        }
    }

    private fun startCountdown() {
        countdown?.cancel()
        countdown = viewModelScope.launch {
            while (_state.value.secondsLeft > 0) {
                delay(1_000)
                _state.update { it.copy(secondsLeft = (it.secondsLeft - 1).coerceAtLeast(0)) }
            }
            newCode()
        }
    }

    private suspend fun close(message: Int) {
        countdown?.cancel()
        _events.send(UiEvent.Toast(message))
        _state.update { it.copy(closed = true) }
    }

    private suspend fun finish(user: SignedInUser) {
        session?.cancel()
        countdown?.cancel()
        accounts.rememberSignIn(user)
        sessions.open(user.serverId, user.userId.toString())
        _events.send(UiEvent.Toast(R.string.quick_connect_signed_in))
        _events.send(UiEvent.Navigate(HomeRoute, clearBackStack = true))
    }

    private companion object {
        const val TAG = "QuickConnect"
    }
}
