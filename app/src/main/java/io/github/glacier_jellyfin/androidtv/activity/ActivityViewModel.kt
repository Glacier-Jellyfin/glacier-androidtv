package io.github.glacier_jellyfin.androidtv.activity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.glacier_jellyfin.androidtv.core.data.ParentalControl
import io.github.glacier_jellyfin.androidtv.core.data.SessionManager
import io.github.glacier_jellyfin.androidtv.core.data.media.ActivityEntry
import io.github.glacier_jellyfin.androidtv.core.data.media.ActivityPerson
import io.github.glacier_jellyfin.androidtv.core.data.media.ActivityRepository
import io.github.glacier_jellyfin.androidtv.core.data.media.ActivityTitle
import io.github.glacier_jellyfin.androidtv.core.data.media.AgeFilter
import io.github.glacier_jellyfin.androidtv.core.data.media.activityPeople
import io.github.glacier_jellyfin.androidtv.core.data.media.favoritedTitles
import io.github.glacier_jellyfin.androidtv.core.data.media.watchedTitles
import io.github.glacier_jellyfin.androidtv.core.data.settings.SettingsRepository
import io.github.glacier_jellyfin.androidtv.core.jellyfin.runSuspendCatching
import io.github.glacier_jellyfin.androidtv.core.log.Log
import io.github.glacier_jellyfin.androidtv.navigation.ActivityPersonRoute
import io.github.glacier_jellyfin.androidtv.navigation.NavTarget
import io.github.glacier_jellyfin.androidtv.navigation.navEvent
import io.github.glacier_jellyfin.androidtv.parental.PinGate
import io.github.glacier_jellyfin.androidtv.ui.UiEvent
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.serializer.toUUIDOrNull

data class ActivityState(
    val loading: Boolean = true,
    /** The rows "Watched" and "Favorited": a card per title. */
    val watched: List<ActivityTitle> = emptyList(),
    val favorited: List<ActivityTitle> = emptyList(),
    /** The row "Other users": everyone but this profile, the most recently active first. */
    val people: List<ActivityPerson> = emptyList(),
    val userName: String = "",
) {
    val isEmpty: Boolean get() = watched.isEmpty() && favorited.isEmpty() && people.isEmpty()
}

@HiltViewModel
class ActivityViewModel @Inject constructor(
    private val repository: ActivityRepository,
    private val ageFilter: AgeFilter,
    private val sessions: SessionManager,
    parental: ParentalControl,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val self: UUID? = sessions.session.value?.user?.userId?.toUUIDOrNull()

    private val _state = MutableStateFlow(ActivityState(userName = sessions.session.value?.user?.name.orEmpty()))
    val state: StateFlow<ActivityState> = _state.asStateFlow()

    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    val pin = PinGate(viewModelScope, parental) { _events.send(it) }

    /** What the server sent, before the profile's person filter; null when the feed cannot be used. */
    private var entries: List<ActivityEntry>? = null
    private var resumes = 0

    init {
        load(fresh = false, spinner = true)
        // Settings › Activity: people switched off leave the page. The current value is skipped, as the first load
        // builds the page anyway, and a build before it would show an empty page that is no longer loading.
        viewModelScope.launch {
            settings.settings.map { it.activity.hiddenUsers }.distinctUntilChanged().drop(1).collect { rebuild() }
        }
    }

    /** The page is shown again: the feed is read anew, but not on the first time, when it loads anyway. */
    fun resumed() {
        if (resumes++ > 0) load(fresh = true, spinner = false)
    }

    private fun load(fresh: Boolean, spinner: Boolean) {
        viewModelScope.launch {
            if (spinner) _state.update { it.copy(loading = true) }
            if (fresh) repository.invalidate()
            runSuspendCatching { repository.feed() }
                .onSuccess { entries = it }
                .onFailure {
                    Log.w(TAG, "Loading the activity failed", it)
                    if (spinner) entries = null
                }
            rebuild()
        }
    }

    private fun rebuild() {
        val visible = entries.orEmpty().withoutHidden(settings.settings.value.activity.hiddenUsers, self)
        _state.value = ActivityState(
            loading = false,
            watched = watchedTitles(visible),
            favorited = favoritedTitles(visible),
            people = activityPeople(visible).filter { it.user.id != self },
            userName = _state.value.userName,
        )
    }

    /** A show card opens its show, a home video plays, anything else opens its page. */
    fun open(title: ActivityTitle) {
        pin.openActivityTitle(title, ageFilter, ::navigate)
    }

    /** A person of the row "People" opens their profile. */
    fun openPerson(person: ActivityPerson) {
        navigate(ActivityPersonRoute(person.user.id.toString()))
    }

    fun onNav(target: NavTarget) {
        val event = sessions.navEvent(target, current = NavTarget.Activity) ?: return
        viewModelScope.launch { _events.send(event) }
    }

    private fun navigate(route: Any) {
        viewModelScope.launch { _events.send(UiEvent.Navigate(route)) }
    }

    private companion object {
        const val TAG = "Activity"
    }
}
