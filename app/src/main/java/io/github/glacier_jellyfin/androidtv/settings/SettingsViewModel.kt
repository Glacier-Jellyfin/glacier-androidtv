package io.github.glacier_jellyfin.androidtv.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.data.SessionManager
import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryKind
import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryQuery
import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryRepository
import io.github.glacier_jellyfin.androidtv.core.data.settings.AppearanceSettings
import io.github.glacier_jellyfin.androidtv.core.data.settings.HomeSettings
import io.github.glacier_jellyfin.androidtv.core.data.settings.Language
import io.github.glacier_jellyfin.androidtv.core.data.settings.PlaybackSettings
import io.github.glacier_jellyfin.androidtv.core.data.settings.ProfileSettings
import io.github.glacier_jellyfin.androidtv.core.data.settings.ServerPreferences
import io.github.glacier_jellyfin.androidtv.core.data.settings.ServerPreferencesRepository
import io.github.glacier_jellyfin.androidtv.core.data.settings.SettingsRepository
import io.github.glacier_jellyfin.androidtv.core.data.settings.SubtitleStyle
import io.github.glacier_jellyfin.androidtv.core.data.settings.UiLanguage
import io.github.glacier_jellyfin.androidtv.navigation.HomeRoute
import io.github.glacier_jellyfin.androidtv.navigation.LibraryRoute
import io.github.glacier_jellyfin.androidtv.navigation.ProfilesRoute
import io.github.glacier_jellyfin.androidtv.navigation.SearchRoute
import io.github.glacier_jellyfin.androidtv.ui.NavTarget
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

/** Settings categories in the design's order; the rest follow in later steps. */
enum class SettingsCategory(val label: Int) {
    Appearance(R.string.settings_cat_appearance),
    Home(R.string.settings_cat_home),
    Playback(R.string.settings_cat_playback),
    Audio(R.string.settings_cat_audio),
    Subtitles(R.string.settings_cat_subtitles),
    Account(R.string.settings_cat_account),
}

/** Which language list is open. */
enum class LanguageTarget { Audio, Subtitles, Ui }

data class SettingsUiState(
    val category: SettingsCategory = SettingsCategory.Appearance,
    val profile: ProfileSettings = ProfileSettings(),
    /** Null while loading or when the server cannot be reached ([serverFailed]). */
    val server: ServerPreferences? = null,
    val serverFailed: Boolean = false,
    val languages: List<Language> = emptyList(),
    val languagePicker: LanguageTarget? = null,
    val userName: String = "",
    /** Backdrop behind the subtitle preview. */
    val previewImage: String? = null,
    /** A new interface language: the activity is recreated in it, then focus goes back to its button. */
    val refocusLanguage: UiLanguage? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val sessions: SessionManager,
    private val settings: SettingsRepository,
    private val serverPreferences: ServerPreferencesRepository,
    private val library: LibraryRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState(userName = sessions.session.value?.user?.name.orEmpty()))
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    init {
        viewModelScope.launch { settings.settings.collect { profile -> _state.update { it.copy(profile = profile) } } }
        viewModelScope.launch { serverPreferences.preferences.collect { server -> if (server != null) _state.update { it.copy(server = server) } } }
        viewModelScope.launch {
            // Another client may have changed them since the app started.
            val server = serverPreferences.refresh()
            _state.update { it.copy(server = server ?: it.server, serverFailed = server == null && it.server == null) }
        }
        viewModelScope.launch {
            val languages = serverPreferences.languages()
            _state.update { it.copy(languages = languages) }
        }
        viewModelScope.launch { loadPreviewImage() }
    }

    fun selectCategory(category: SettingsCategory) = _state.update { it.copy(category = category) }

    fun updatePlayback(transform: (PlaybackSettings) -> PlaybackSettings) {
        viewModelScope.launch { settings.updatePlayback(transform) }
    }

    fun updateAppearance(transform: (AppearanceSettings) -> AppearanceSettings) {
        viewModelScope.launch { settings.updateAppearance(transform) }
    }

    fun updateHome(transform: (HomeSettings) -> HomeSettings) {
        viewModelScope.launch { settings.updateHome(transform) }
    }

    fun updateSubtitleStyle(transform: (SubtitleStyle) -> SubtitleStyle) {
        viewModelScope.launch { settings.updateSubtitleStyle(transform) }
    }

    /** Shown at once, saved on the server behind it; a failed save brings the old value back. */
    fun updateServer(transform: (ServerPreferences) -> ServerPreferences) {
        val before = _state.value.server ?: return
        _state.update { it.copy(server = transform(before)) }
        viewModelScope.launch {
            if (!serverPreferences.update(transform)) {
                _state.update { it.copy(server = before) }
                _events.send(UiEvent.Toast(R.string.settings_save_failed))
            }
        }
    }

    fun languageRefocused() = _state.update { it.copy(refocusLanguage = null) }

    fun openLanguages(target: LanguageTarget) = _state.update { it.copy(languagePicker = target) }

    fun closeLanguages() = _state.update { it.copy(languagePicker = null) }

    /** [code] null: "Original" for audio, "None" for subtitles, the device language for the interface. */
    fun pickLanguage(code: String?) {
        val target = _state.value.languagePicker ?: return
        closeLanguages()
        if (target == LanguageTarget.Ui) {
            val language = UiLanguage.of(code)
            if (language == _state.value.profile.uiLanguage) return
            _state.update { it.copy(refocusLanguage = language) }
            viewModelScope.launch { settings.update { it.copy(uiLanguage = language) } }
            return
        }
        updateServer {
            when (target) {
                LanguageTarget.Audio -> it.copy(audioLanguage = code)
                LanguageTarget.Subtitles -> it.copy(subtitleLanguage = code)
                LanguageTarget.Ui -> it
            }
        }
    }

    /** Ends the session on the server as well; see [SessionManager.signOut]. */
    fun signOut() {
        val serverId = sessions.session.value?.server?.id ?: return
        viewModelScope.launch {
            sessions.signOut()
            _events.send(UiEvent.Navigate(ProfilesRoute(serverId), clearBackStack = true))
        }
    }

    fun onNav(target: NavTarget) {
        viewModelScope.launch {
            when (target) {
                NavTarget.Settings -> Unit
                NavTarget.Home -> _events.send(UiEvent.Navigate(HomeRoute, clearBackStack = true))
                NavTarget.Search -> _events.send(UiEvent.Navigate(SearchRoute()))
                is NavTarget.Library -> _events.send(UiEvent.Navigate(LibraryRoute(target.kind.name)))
                NavTarget.Profile -> {
                    val serverId = sessions.session.value?.server?.id ?: return@launch
                    sessions.leave()
                    _events.send(UiEvent.Navigate(ProfilesRoute(serverId), clearBackStack = true))
                }
            }
        }
    }

    private suspend fun loadPreviewImage() {
        try {
            val image = library.page(LibraryQuery(LibraryKind.Movies), start = 0, limit = PREVIEW_CANDIDATES)
                .items.firstNotNullOfOrNull { it.backdropUrl }
            _state.update { it.copy(previewImage = image) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The preview works on the plain background too.
            Log.w(TAG, "Loading a preview backdrop failed", e)
        }
    }

    private companion object {
        const val TAG = "Settings"
        const val PREVIEW_CANDIDATES = 12
    }
}
