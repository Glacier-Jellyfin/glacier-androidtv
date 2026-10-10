package io.github.glacier_jellyfin.androidtv.settings

import io.github.glacier_jellyfin.androidtv.core.log.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.data.AccountRepository
import io.github.glacier_jellyfin.androidtv.core.data.AgeLimit
import io.github.glacier_jellyfin.androidtv.core.data.ParentalControl
import io.github.glacier_jellyfin.androidtv.core.data.ProfileLock
import io.github.glacier_jellyfin.androidtv.core.data.Protection
import io.github.glacier_jellyfin.androidtv.core.data.SessionManager
import io.github.glacier_jellyfin.androidtv.core.data.StartMode
import io.github.glacier_jellyfin.androidtv.core.data.StartProfile
import io.github.glacier_jellyfin.androidtv.core.data.StoredUser
import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryKind
import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryQuery
import io.github.glacier_jellyfin.androidtv.core.data.media.LibrarySort
import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryRepository
import io.github.glacier_jellyfin.androidtv.core.data.media.Library
import io.github.glacier_jellyfin.androidtv.core.data.media.HomeRepository
import io.github.glacier_jellyfin.androidtv.core.data.settings.AppearanceSettings
import io.github.glacier_jellyfin.androidtv.core.data.settings.HomeSettings
import io.github.glacier_jellyfin.androidtv.core.data.settings.NavigationSettings
import io.github.glacier_jellyfin.androidtv.core.data.settings.Language
import io.github.glacier_jellyfin.androidtv.core.data.settings.PlaybackSettings
import io.github.glacier_jellyfin.androidtv.core.data.settings.ProfileSettings
import io.github.glacier_jellyfin.androidtv.core.data.settings.ServerPreferences
import io.github.glacier_jellyfin.androidtv.core.data.settings.ServerPreferencesRepository
import io.github.glacier_jellyfin.androidtv.core.data.settings.SettingsRepository
import io.github.glacier_jellyfin.androidtv.core.data.settings.SubtitleStyle
import io.github.glacier_jellyfin.androidtv.core.data.settings.UiLanguage
import io.github.glacier_jellyfin.androidtv.diagnostics.Diagnostics
import io.github.glacier_jellyfin.androidtv.diagnostics.LogServer
import io.github.glacier_jellyfin.androidtv.diagnostics.LogShare
import io.github.glacier_jellyfin.androidtv.navigation.FavoritesRoute
import io.github.glacier_jellyfin.androidtv.navigation.HomeRoute
import io.github.glacier_jellyfin.androidtv.navigation.LibraryRoute
import io.github.glacier_jellyfin.androidtv.navigation.LiveTvRoute
import io.github.glacier_jellyfin.androidtv.navigation.MusicRoute
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
import kotlinx.coroutines.Job
import org.jellyfin.sdk.api.client.extensions.systemApi
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    val diagnostics: DiagnosticsState = DiagnosticsState(),
    val startProfile: StartProfile = StartProfile(),
    /** Profiles [StartMode.Fixed] can open: the signed-in ones of this server, and the chosen one wherever it is. */
    val startCandidates: List<StoredUser> = emptyList(),
)

/** Settings › System › Diagnostics: [sentAs] is the file name the server gave the log just sent. */
data class DiagnosticsState(
    val sending: Boolean = false,
    val sentAs: String? = null,
    val lastCrash: Long? = null,
    /** When detailed logging ends, null while it is off. */
    val verboseUntil: Long? = null,
    /** The log on the local network while its page is open. */
    val share: LogShare? = null,
)

/** Settings › System › Server; [version] is asked fresh from the server, the stored one until then. */
data class ServerSummary(val name: String, val address: String, val version: String?)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val sessions: SessionManager,
    private val accounts: AccountRepository,
    private val settings: SettingsRepository,
    private val serverPreferences: ServerPreferencesRepository,
    private val library: LibraryRepository,
    home: HomeRepository,
    private val parental: ParentalControl,
    private val diagnostics: Diagnostics,
    val updates: UpdateManager,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState(userName = sessions.session.value?.user?.name.orEmpty()))
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    val pin = PinGate(viewModelScope, parental) { _events.send(it) }

    /** The libraries with a "New in" row, each its own entry in Settings › Home. */
    val latestLibraries: StateFlow<List<Library>> = home.latestLibraries

    /** All libraries, to tell whether a library is the only one of its kind. */
    val libraries: StateFlow<List<Library>> = home.libraries

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
        viewModelScope.launch {
            accounts.state.collect { accountState ->
                val serverId = sessions.session.value?.server?.id
                val start = accountState.startProfile
                val candidates = accountState.users.filter { user ->
                    (user.serverId == serverId && user.accessToken != null) ||
                        (start.mode == StartMode.Fixed && user.serverId == start.serverId && user.userId == start.userId)
                }
                _state.update { it.copy(startProfile = start, startCandidates = candidates) }
            }
        }
        viewModelScope.launch(Dispatchers.Default) { FfmpegAudio.version()?.let { version -> _state.update { it.copy(ffmpegVersion = version) } } }
        viewModelScope.launch(Dispatchers.IO) { Log.lastCrash()?.let { crash -> _state.update { it.copy(diagnostics = it.diagnostics.copy(lastCrash = crash)) } } }
        _state.update { it.copy(diagnostics = it.diagnostics.copy(verboseUntil = Log.verboseUntil())) }
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

    fun updateNavigation(transform: (NavigationSettings) -> NavigationSettings) {
        viewModelScope.launch { settings.updateNavigation(transform) }
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


    /** Choosing "fixed profile" starts with the profile using the app now. */
    fun setStartMode(mode: StartMode) {
        val user = sessions.session.value?.user
        val start = if (mode == StartMode.Fixed) StartProfile(mode, user?.serverId, user?.userId) else StartProfile(mode)
        viewModelScope.launch { accounts.setStartProfile(start) }
    }

    fun setStartUser(user: StoredUser) {
        viewModelScope.launch { accounts.setStartProfile(StartProfile(StartMode.Fixed, user.serverId, user.userId)) }
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
                NavTarget.Favorites -> _events.send(UiEvent.Navigate(FavoritesRoute))
                NavTarget.NowPlaying -> _events.send(UiEvent.Navigate(MusicRoute()))
                NavTarget.LiveTv -> _events.send(UiEvent.Navigate(LiveTvRoute))
                NavTarget.Profile -> {
                    val serverId = sessions.session.value?.server?.id ?: return@launch
                    sessions.leave()
                    _events.send(UiEvent.Navigate(ProfilesRoute(serverId), clearBackStack = true))
                }
            }
        }
    }

    fun sendLog() {
        if (_state.value.diagnostics.sending) return
        _state.update { it.copy(diagnostics = it.diagnostics.copy(sending = true)) }
        viewModelScope.launch {
            try {
                val fileName = diagnostics.sendToServer()
                _state.update { it.copy(diagnostics = it.diagnostics.copy(sending = false, sentAs = fileName, lastCrash = null)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Sending the log failed", e)
                _state.update { it.copy(diagnostics = it.diagnostics.copy(sending = false)) }
                // The server answers 403 when its admin turned client log uploads off.
                val forbidden = (e as? InvalidStatusException)?.status == 403
                _events.send(UiEvent.Toast(if (forbidden) R.string.diag_send_forbidden else R.string.diag_send_failed))
            }
        }
    }

    fun clearLog() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { Log.clear() }
            _state.update { it.copy(diagnostics = DiagnosticsState(verboseUntil = Log.verboseUntil())) }
            _events.send(UiEvent.Toast(R.string.diag_cleared))
        }
    }

    fun toggleVerbose() {
        Log.setVerbose(_state.value.diagnostics.verboseUntil == null)
        _state.update { it.copy(diagnostics = it.diagnostics.copy(verboseUntil = Log.verboseUntil())) }
    }

    private var logServer: LogServer? = null
    private var opening: Job? = null

    fun shareLog() {
        if (logServer != null || opening?.isActive == true) return
        opening = viewModelScope.launch {
            val server = try {
                diagnostics.shareOnNetwork()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Offering the log on the network failed", e)
                null
            }
            if (server == null) {
                _events.send(UiEvent.Toast(R.string.diag_share_no_network))
                return@launch
            }
            logServer = server
            _state.update { it.copy(diagnostics = it.diagnostics.copy(share = LogShare(server.url, server.fileName, server.sizeBytes), lastCrash = null)) }
        }
    }

    fun closeShare() {
        logServer?.close()
        logServer = null
        _state.update { it.copy(diagnostics = it.diagnostics.copy(share = null)) }
    }

    override fun onCleared() {
        logServer?.close()
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
            val image = library.page(LibraryQuery(LibraryKind.Movies, sort = LibrarySort.DateAdded, descending = true), start = 0, limit = PREVIEW_CANDIDATES)
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
