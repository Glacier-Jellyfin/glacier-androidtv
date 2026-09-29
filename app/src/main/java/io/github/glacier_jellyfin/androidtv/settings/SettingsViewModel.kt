package io.github.glacier_jellyfin.androidtv.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.data.AgeLimit
import io.github.glacier_jellyfin.androidtv.core.data.ParentalControl
import io.github.glacier_jellyfin.androidtv.core.data.ProfileLock
import io.github.glacier_jellyfin.androidtv.core.data.Protection
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
import io.github.glacier_jellyfin.androidtv.ui.PinGate
import io.github.glacier_jellyfin.androidtv.ui.PinReason
import io.github.glacier_jellyfin.androidtv.ui.UiEvent
import io.github.glacier_jellyfin.androidtv.core.player.FfmpegAudio
import io.github.glacier_jellyfin.androidtv.core.updater.UpdateChannel
import io.github.glacier_jellyfin.androidtv.core.updater.UpdateManager
import io.github.glacier_jellyfin.androidtv.core.updater.UpdateState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import org.jellyfin.sdk.api.client.extensions.systemApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Settings categories in the design's order. */
enum class SettingsCategory(val label: Int) {
    Appearance(R.string.settings_cat_appearance),
    Home(R.string.settings_cat_home),
    Playback(R.string.settings_cat_playback),
    Audio(R.string.settings_cat_audio),
    Subtitles(R.string.settings_cat_subtitles),
    Account(R.string.settings_cat_account),
    System(R.string.settings_cat_system),
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
    val lock: ProfileLock = ProfileLock(),
    val accountUnlocked: Boolean = false,
    val update: UpdateState = UpdateState.Unchecked,
    val updateChannel: UpdateChannel = UpdateChannel.Stable,
    val autoUpdate: Boolean = true,
    val serverSummary: ServerSummary? = null,
    /** FFmpeg's version, null when this build has no FFmpeg decoder. */
    val ffmpegVersion: String? = null,
)

/** Settings › System › Server; [version] is asked fresh from the server, the stored one until then. */
data class ServerSummary(val name: String, val address: String, val version: String?)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val sessions: SessionManager,
    private val settings: SettingsRepository,
    private val serverPreferences: ServerPreferencesRepository,
    private val library: LibraryRepository,
    private val parental: ParentalControl,
    val updates: UpdateManager,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState(userName = sessions.session.value?.user?.name.orEmpty()))
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    val pin = PinGate(viewModelScope, parental) { _events.send(it) }

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
        viewModelScope.launch { parental.lock.collect { lock -> _state.update { it.copy(lock = lock) } } }
        viewModelScope.launch { parental.settingsUnlocked.collect { unlocked -> _state.update { it.copy(accountUnlocked = unlocked) } } }
        viewModelScope.launch { loadPreviewImage() }
        viewModelScope.launch { updates.state.collect { update -> _state.update { it.copy(update = update) } } }
        viewModelScope.launch { updates.channel.collect { channel -> _state.update { it.copy(updateChannel = channel) } } }
        viewModelScope.launch { updates.autoCheck.collect { on -> _state.update { it.copy(autoUpdate = on) } } }
        viewModelScope.launch { loadServerSummary() }
        viewModelScope.launch(Dispatchers.Default) { FfmpegAudio.version()?.let { version -> _state.update { it.copy(ffmpegVersion = version) } } }
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


    fun setMaxAge(limit: AgeLimit) = updateProtection { it.copy(maxAge = limit) }

    fun setBlockUnrated(on: Boolean) = updateProtection { it.copy(blockUnrated = on) }

    /** Switching a PIN option on for the first time sets the PIN first; there is no default PIN. */
    fun setPinOption(on: Boolean, transform: (Protection, Boolean) -> Protection) {
        val apply = {
            // Whoever switches the settings lock on is already in; it applies from the next visit.
            parental.unlockSettings()
            updateProtection { transform(it, on) }
        }
        if (on && !_state.value.lock.hasPin) pin.open(PinReason.Create, apply) else apply()
    }

    fun changePin() = pin.open(if (_state.value.lock.hasPin) PinReason.Change else PinReason.Create)

    fun unlockAccount() = pin.open(PinReason.Settings)

    private fun updateProtection(transform: (Protection) -> Protection) {
        viewModelScope.launch { parental.updateProtection(transform) }
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

    private suspend fun loadServerSummary() {
        val session = sessions.session.value ?: return
        val server = session.server
        _state.update { it.copy(serverSummary = ServerSummary(server.name, server.address, server.version)) }
        try {
            val version = session.api.systemApi.getPublicSystemInfo().content.version ?: return
            _state.update { it.copy(serverSummary = it.serverSummary?.copy(version = version)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Asking the server for its version failed", e)
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
