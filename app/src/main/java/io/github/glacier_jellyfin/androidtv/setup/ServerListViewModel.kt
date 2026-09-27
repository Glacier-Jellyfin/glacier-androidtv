package io.github.glacier_jellyfin.androidtv.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.data.AccountRepository
import io.github.glacier_jellyfin.androidtv.core.jellyfin.DiscoveredServer
import io.github.glacier_jellyfin.androidtv.core.jellyfin.ServerFinder
import io.github.glacier_jellyfin.androidtv.core.jellyfin.ServerInfo
import io.github.glacier_jellyfin.androidtv.ui.UiEvent
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ServerEntry(
    val server: ServerInfo,
    val publicUserCount: Int? = null,
    val latencyMillis: Long? = null,
    /** False while a saved server has not answered (yet). */
    val reachable: Boolean = true,
)

data class ServerListState(
    val saved: List<ServerEntry> = emptyList(),
    val discovered: List<ServerEntry> = emptyList(),
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
        val saved = accounts.current().servers.sortedByDescending { it.lastUsedAt }
        _state.update { state ->
            state.copy(saved = saved.map { ServerEntry(ServerInfo(it.id, it.name, it.address, it.version)) })
        }
        saved.forEach { stored ->
            viewModelScope.launch {
                val probe = finder.probe(stored.address)
                _state.update { state ->
                    state.copy(saved = state.saved.map { entry ->
                        if (entry.server.id != stored.id) entry
                        else probe?.toEntry() ?: entry.copy(reachable = false)
                    })
                }
            }
        }
    }

    private fun onDiscovered(found: DiscoveredServer) = _state.update { state ->
        if (state.saved.any { it.server.id == found.server.id } || state.discovered.any { it.server.id == found.server.id }) state
        else state.copy(discovered = state.discovered + found.toEntry())
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

    private fun connect(server: ServerInfo) {
        viewModelScope.launch {
            _state.update { it.copy(connecting = server.name) }
            val route = connector.connect(server)
            _state.update { it.copy(connecting = null) }
            _events.send(UiEvent.Navigate(route))
        }
    }

    private fun DiscoveredServer.toEntry() = ServerEntry(server, publicUserCount, latencyMillis)
}
