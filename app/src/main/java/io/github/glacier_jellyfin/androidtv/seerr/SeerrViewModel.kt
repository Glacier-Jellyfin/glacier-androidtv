package io.github.glacier_jellyfin.androidtv.seerr

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.data.media.SeerrDetails
import io.github.glacier_jellyfin.androidtv.core.data.media.SeerrMediaType
import io.github.glacier_jellyfin.androidtv.core.data.media.SeerrQuota
import io.github.glacier_jellyfin.androidtv.core.data.media.SeerrRepository
import io.github.glacier_jellyfin.androidtv.core.data.media.SeerrRequestResult
import io.github.glacier_jellyfin.androidtv.core.log.Log
import io.github.glacier_jellyfin.androidtv.navigation.SearchRoute
import io.github.glacier_jellyfin.androidtv.navigation.SeerrRoute
import io.github.glacier_jellyfin.androidtv.navigation.TrailerRoute
import io.github.glacier_jellyfin.androidtv.ui.UiEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SeerrState(
    val details: SeerrDetails? = null,
    val failed: Boolean = false,
    /** Seasons ticked for the request; all requestable ones to begin with. */
    val selected: Set<Int> = emptySet(),
    val sending: Boolean = false,
    /** The request limit for this kind of title; null when Seerr sets none. */
    val quota: SeerrQuota? = null,
)

@HiltViewModel
class SeerrViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: SeerrRepository,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<SeerrRoute>()
    private val type = SeerrMediaType.valueOf(route.type)

    private val _state = MutableStateFlow(SeerrState())
    val state: StateFlow<SeerrState> = _state.asStateFlow()

    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(failed = false) }
            try {
                val details = repository.details(type, route.tmdbId)
                val selected = details.seasons.filter { it.requestable }.mapTo(HashSet()) { it.number }
                _state.update { it.copy(details = details, selected = selected) }
                if (details.requestable) {
                    val quota = repository.quota(type)
                    _state.update { it.copy(quota = quota) }
                }
                if (details.requestable) {
                    val quota = repository.quota(type)
                    _state.update { it.copy(quota = quota) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Loading $type ${route.tmdbId} failed", e)
                _state.update { it.copy(failed = true) }
            }
        }
    }

    fun toggleSeason(number: Int) = _state.update {
        it.copy(selected = if (number in it.selected) it.selected - number else it.selected + number)
    }

    fun playTrailer() {
        viewModelScope.launch { _events.send(UiEvent.Navigate(TrailerRoute(seerrType = type.name, tmdbId = route.tmdbId))) }
    }

    /** TMDB people have no Jellyfin page; their name is looked up in the library instead. */
    fun openPerson(name: String) {
        viewModelScope.launch { _events.send(UiEvent.Navigate(SearchRoute(name))) }
    }

    fun request() {
        val current = _state.value
        val details = current.details ?: return
        if (current.sending || !details.requestable) return
        if (type == SeerrMediaType.Tv && current.selected.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(sending = true) }
            val result = try {
                repository.request(type, route.tmdbId, current.selected.sorted())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Request for $type ${route.tmdbId} failed", e)
                SeerrRequestResult.Failed
            }
            _state.update { it.copy(sending = false) }
            when (result) {
                SeerrRequestResult.Sent -> {
                    _events.send(UiEvent.Toast(R.string.seerr_requested, listOf(details.item.title)))
                    load()
                }
                SeerrRequestResult.NotAllowed -> _events.send(UiEvent.Toast(R.string.seerr_not_allowed))
                SeerrRequestResult.QuotaUsedUp -> _events.send(UiEvent.Toast(R.string.seerr_quota_used_up))
                SeerrRequestResult.AlreadyRequested -> {
                    _events.send(UiEvent.Toast(R.string.seerr_already_requested))
                    load()
                }
                SeerrRequestResult.Failed -> _events.send(UiEvent.Toast(R.string.seerr_request_failed))
            }
        }
    }

    private companion object {
        const val TAG = "SeerrPage"
    }
}
