package io.github.glacier_jellyfin.androidtv.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.data.AccountRepository
import io.github.glacier_jellyfin.androidtv.core.data.AccountState
import io.github.glacier_jellyfin.androidtv.core.data.profilesFor
import io.github.glacier_jellyfin.androidtv.core.jellyfin.DiscoveredServer
import io.github.glacier_jellyfin.androidtv.core.jellyfin.ServerFinder
import io.github.glacier_jellyfin.androidtv.core.jellyfin.ServerInfo
import io.github.glacier_jellyfin.androidtv.ui.UiEvent
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ServerEntry(
    val server: ServerInfo,
    /** Profiles as "Who's watching?" lists them: the server's public users and those set up on this device; null while unknown. */
    val profileCount: Int? = null,
    val latencyMillis: Long? = null,
    /** False while a saved server does not answer. */
    val reachable: Boolean = true,
)

data class ServerListState(
    val saved: List<ServerEntry> = emptyList(),
    val discovered: List<ServerEntry> = emptyList(),
    /** Saved servers have been read; the screen can place its initial focus. */
    val savedLoaded: Boolean = false,
    val searching: Boolean = true,
    /** Name of the server being connected to, shown in the busy overlay. */
    val connecting: String? = null,
    /** Server older than 12.0 waiting for "connect anyway". */
    val confirmUnsupported: ServerInfo? = null,
)

@HiltViewModel
class ServerListViewModel @Inject constructor(
    private val accounts: AccountRepository,
    private val finder: ServerFinder,
    private val connector: ServerConnector,
    private val reachability: ServerReachability,
) : ViewModel() {

    private val _state = MutableStateFlow(ServerListState())
    val state: StateFlow<ServerListState> = _state.asStateFlow()

    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        viewModelScope.launch { loadSaved() }
        viewModelScope.launch {
            // Discovery failing (no network, blocked broadcast) just means an empty list.
            finder.discover().catch { }.collect(::onDiscovered)
            _state.update { it.copy(searching = false) }
        }
    }

    private suspend fun loadSaved() {
        val current = accounts.current()
        val saved = current.servers.sortedByDescending { it.lastUsedAt }
        _state.update { state ->
            state.copy(
                saved = saved.map { stored ->
                    ServerEntry(
                        ServerInfo(stored.id, stored.name, stored.address, stored.version),
                        profileCount = current.profilesFor(stored.id, publicUsers = null).size,
                        reachable = reachability.isReachable(stored.id),
                    )
                },
                savedLoaded = true,
            )
        }
        probeSaved()
    }

    /** Checks the saved servers again every [RECHECK_MILLIS] while the list is shown; one that answers again loses its hint. */
    suspend fun watchReachability() {
        while (true) {
            delay(RECHECK_MILLIS)
            probeSaved()
        }
    }

    private suspend fun probeSaved() = coroutineScope {
        _state.value.saved.map { it.server }.forEach { stored ->
            launch {
                val probe = finder.probe(stored.address)
                reachability.report(stored.id, probe != null)
                val accountState = accounts.current()
                _state.update { state ->
                    state.copy(
                        saved = state.saved.map { entry ->
                            if (entry.server.id != stored.id) {
                                entry
                            } else {
                                probe?.toEntry(accountState) ?: entry.copy(reachable = false, latencyMillis = null)
                            }
                        },
                    )
                }
            }
        }
    }

    private fun onDiscovered(found: DiscoveredServer) = _state.update { state ->
        if (state.saved.any { it.server.id == found.server.id } ||
            state.discovered.any { it.server.id == found.server.id }
        ) {
            state
        } else {
            state.copy(discovered = state.discovered + found.toEntry(AccountState()))
        }
    }

    fun select(entry: ServerEntry) {
        if (!entry.reachable) {
            viewModelScope.launch { _events.send(UiEvent.Toast(R.string.setup_unreachable, listOf(entry.server.name))) }
            return
        }
        if (!entry.server.isSupported) {
            _state.update { it.copy(confirmUnsupported = entry.server) }
            return
        }
        connect(entry.server)
    }

    fun confirmUnsupported(connect: Boolean) {
        val server = _state.value.confirmUnsupported ?: return
        _state.update { it.copy(confirmUnsupported = null) }
        if (connect) connect(server)
    }

    private var connectJob: Job? = null

    private fun connect(server: ServerInfo) {
        connectJob?.cancel()
        connectJob = viewModelScope.launch {
            _state.update { it.copy(connecting = server.name) }
            val route = connector.connect(server)
            _state.update { it.copy(connecting = null) }
            _events.send(UiEvent.Navigate(route))
        }
    }

    /** Back while connecting: the list stays, as if no server had been picked. */
    fun cancelConnect() {
        connectJob?.cancel()
        connectJob = null
        _state.update { it.copy(connecting = null) }
    }

    /** Public users and the profiles set up here, each counted once; only the local ones when the server did not list its users. */
    private fun DiscoveredServer.toEntry(accountState: AccountState) = ServerEntry(
        server,
        profileCount = accountState.profilesFor(server.id, publicUsers).size,
        latencyMillis = latencyMillis,
    )

    private companion object {
        const val RECHECK_MILLIS = 10_000L
    }
}
