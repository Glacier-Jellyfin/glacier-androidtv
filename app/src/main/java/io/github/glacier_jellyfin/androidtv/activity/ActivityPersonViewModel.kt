package io.github.glacier_jellyfin.androidtv.activity

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.glacier_jellyfin.androidtv.core.data.ParentalControl
import io.github.glacier_jellyfin.androidtv.core.data.SessionManager
import io.github.glacier_jellyfin.androidtv.core.data.media.ActivityEntry
import io.github.glacier_jellyfin.androidtv.core.data.media.ActivityRepository
import io.github.glacier_jellyfin.androidtv.core.data.media.ActivityTitle
import io.github.glacier_jellyfin.androidtv.core.data.media.AgeFilter
import io.github.glacier_jellyfin.androidtv.core.data.media.PersonProfile
import io.github.glacier_jellyfin.androidtv.core.data.media.personProfile
import io.github.glacier_jellyfin.androidtv.core.data.settings.SettingsRepository
import io.github.glacier_jellyfin.androidtv.core.jellyfin.runSuspendCatching
import io.github.glacier_jellyfin.androidtv.core.log.Log
import io.github.glacier_jellyfin.androidtv.navigation.ActivityPersonRoute
import io.github.glacier_jellyfin.androidtv.parental.PinGate
import io.github.glacier_jellyfin.androidtv.ui.UiEvent
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.serializer.toUUIDOrNull

data class ActivityPersonState(
    val loading: Boolean = true,
    /** Null for a person that is hidden, unknown or has no entry in the loaded feed. */
    val profile: PersonProfile? = null,
)

@HiltViewModel
class ActivityPersonViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: ActivityRepository,
    private val ageFilter: AgeFilter,
    sessions: SessionManager,
    parental: ParentalControl,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val userId: UUID? = savedStateHandle.toRoute<ActivityPersonRoute>().userId.toUUIDOrNull()
    private val self: UUID? = sessions.session.value?.user?.userId?.toUUIDOrNull()

    private val _state = MutableStateFlow(ActivityPersonState())
    val state: StateFlow<ActivityPersonState> = _state.asStateFlow()

    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    val pin = PinGate(viewModelScope, parental) { _events.send(it) }

    private var entries: List<ActivityEntry> = emptyList()

    init {
        viewModelScope.launch {
            // The page before has just read the feed, so this mostly comes from the repository's cache.
            runSuspendCatching { repository.feed() }
                .onSuccess { entries = it.orEmpty() }
                .onFailure { Log.w(TAG, "Loading the activity failed", it) }
            rebuild()
            // Settings › Activity: a person switched off leaves the page.
            settings.settings.map { it.activity.hiddenUsers }.distinctUntilChanged().collect { rebuild() }
        }
    }

    private fun rebuild() {
        val visible = entries.withoutHidden(settings.settings.value.activity.hiddenUsers, self)
        _state.value = ActivityPersonState(
            loading = false,
            profile = userId?.let { personProfile(visible, it) },
        )
    }

    /** A show card opens its show, a home video plays, anything else opens its page. */
    fun open(title: ActivityTitle) {
        pin.openActivityTitle(title, ageFilter) { route ->
            viewModelScope.launch { _events.send(UiEvent.Navigate(route)) }
        }
    }

    private companion object {
        const val TAG = "Activity"
    }
}
