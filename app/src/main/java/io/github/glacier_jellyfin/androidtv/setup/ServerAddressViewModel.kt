package io.github.glacier_jellyfin.androidtv.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.jellyfin.ResolveResult
import io.github.glacier_jellyfin.androidtv.core.jellyfin.ServerFinder
import io.github.glacier_jellyfin.androidtv.core.jellyfin.ServerInfo
import io.github.glacier_jellyfin.androidtv.ui.UiEvent
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ServerAddressState(
    val address: String = "",
    val connecting: String? = null,
    val confirmUnsupported: ServerInfo? = null,
)

@HiltViewModel
class ServerAddressViewModel @Inject constructor(
    private val finder: ServerFinder,
    private val connector: ServerConnector,
) : ViewModel() {

    private val _state = MutableStateFlow(ServerAddressState())
    val state: StateFlow<ServerAddressState> = _state.asStateFlow()

    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    fun setAddress(value: String) = _state.update { it.copy(address = value) }

    fun connect() {
        val input = _state.value.address.trim()
        if (input.removePrefix("https://").removePrefix("http://").length < MIN_HOST_LENGTH) {
            viewModelScope.launch { _events.send(UiEvent.Toast(R.string.address_invalid)) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(connecting = input) }
            when (val result = finder.resolve(input)) {
                ResolveResult.NotFound -> {
                    _state.update { it.copy(connecting = null) }
                    _events.send(UiEvent.Toast(R.string.address_not_found, listOf(input)))
                }
                is ResolveResult.Found -> {
                    if (result.server.isSupported) {
                        finish(result.server)
                    } else {
                        _state.update { it.copy(connecting = null, confirmUnsupported = result.server) }
                    }
                }
            }
        }
    }

    fun confirmUnsupported(connect: Boolean) {
        val server = _state.value.confirmUnsupported ?: return
        _state.update { it.copy(confirmUnsupported = null) }
        if (connect) viewModelScope.launch {
            _state.update { it.copy(connecting = server.name) }
            finish(server)
        }
    }

    private suspend fun finish(server: ServerInfo) {
        val route = connector.connect(server)
        _state.update { it.copy(connecting = null) }
        _events.send(UiEvent.Navigate(route))
    }

    private companion object {
        const val MIN_HOST_LENGTH = 4
    }
}
