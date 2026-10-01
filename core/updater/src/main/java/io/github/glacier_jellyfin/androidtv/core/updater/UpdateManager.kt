package io.github.glacier_jellyfin.androidtv.core.updater

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** Where the running app comes from; provided by the app module. */
data class UpdaterConfig(
    /** The running version, e.g. "1.4.0-beta.2". */
    val versionName: String,
    /** See [ReleaseFeed]. */
    val feedUrl: String,
)

/** Where the app update stands (Settings › System, update dialog). */
sealed interface UpdateState {
    /** Nothing known: automatic checks are off and no one asked yet. */
    data object Unchecked : UpdateState
    data object Checking : UpdateState
    data class Current(val checkedAt: Long) : UpdateState
    data class Available(val candidate: UpdateCandidate) : UpdateState
    data class Downloading(val candidate: UpdateCandidate, val bytes: Long) : UpdateState
    data class Ready(val candidate: UpdateCandidate) : UpdateState
    data class Installing(val candidate: UpdateCandidate) : UpdateState
    /** [candidate] is null when the check itself failed. */
    data class Failed(val error: UpdateError, val candidate: UpdateCandidate?) : UpdateState
}

/** A newer version is known and not installed yet: the dot on the settings gear and the System category. */
val UpdateState.pending: Boolean
    get() = when (this) {
        is UpdateState.Available, is UpdateState.Downloading, is UpdateState.Ready, is UpdateState.Installing -> true
        is UpdateState.Failed -> candidate != null
        else -> false
    }

/** Notices for the user outside the settings screen. */
sealed interface UpdateNotice {
    data class Downloaded(val version: AppVersion) : UpdateNotice
    data object InstallCancelled : UpdateNotice
    data class Failed(val error: UpdateError) : UpdateNotice
}

/**
 * Glacier's self-updater (docs/RELEASING.md): looks for a newer GitHub release
 * of the chosen channel at every start, downloads it only when
 * the user asks, checks it and hands it to Android's installer.
 *
 * Channel and automatic checks apply to the whole device, not per profile.
 */
@Singleton
class UpdateManager @Inject constructor(
    @ApplicationContext private val context: Context,
    config: UpdaterConfig,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val prefs = context.getSharedPreferences("updater", Context.MODE_PRIVATE)
    private val feed = ReleaseFeed(config.feedUrl)
    private val downloader = ApkDownloader(context)
    private val installer = ApkInstaller(context)
    private val json = Json { ignoreUnknownKeys = true }

    /** Null for a version the scheme does not know (a hand-made build): nothing is offered then. */
    val installed: AppVersion? = AppVersion.parse(config.versionName)

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Unchecked)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val _channel = MutableStateFlow(
        runCatching { UpdateChannel.valueOf(prefs.getString(KEY_CHANNEL, null).orEmpty()) }
            // Someone running a beta evidently wants betas.
            .getOrDefault(if (installed?.isBeta == true) UpdateChannel.Beta else UpdateChannel.Stable),
    )
    val channel: StateFlow<UpdateChannel> = _channel.asStateFlow()

    private val _autoCheck = MutableStateFlow(prefs.getBoolean(KEY_AUTO, true))
    val autoCheck: StateFlow<Boolean> = _autoCheck.asStateFlow()

    /** An update found by the automatic check that the user has not been shown yet (home screen dialog). */
    private val _prompt = MutableStateFlow<UpdateCandidate?>(null)
    val prompt: StateFlow<UpdateCandidate?> = _prompt.asStateFlow()

    private val _notices = Channel<UpdateNotice>(Channel.BUFFERED)
    val notices: Flow<UpdateNotice> = _notices.receiveAsFlow()

    private var started = false
    private var job: Job? = null
    private var installAfterDownload = false

    /** Once per app start: a fresh check; the last result stands in until it answers or if it fails. */
    fun start() {
        if (started || installed == null) return
        started = true
        val cached = cachedCandidate()
        if (cached == null) downloader.clear()
        if (!_autoCheck.value) return
        cached?.let { _state.value = UpdateState.Available(it) }
        check(automatic = true)
    }

    /** "Check for updates"; also runs after a channel switch. */
    fun check() = check(automatic = false)

    private fun check(automatic: Boolean) {
        val current = installed ?: return
        if (busy()) return
        val before = _state.value
        _state.value = UpdateState.Checking
        job = scope.launch {
            try {
                val candidate = ReleaseSelector.select(feed.releases(), current, _channel.value)
                val now = System.currentTimeMillis()
                prefs.edit {
                    putLong(KEY_LAST_CHECK, now)
                    putString(KEY_CANDIDATE, candidate?.let { json.encodeToString(UpdateCandidate.serializer(), it) })
                    putString(KEY_CANDIDATE_CHANNEL, _channel.value.name)
                }
                _state.value = candidate?.let(UpdateState::Available) ?: UpdateState.Current(now)
                if (automatic && candidate != null) offer(candidate)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Checking for updates failed", e)
                // A failed check at start stays quiet; the next start tries again.
                _state.value = if (automatic) before else UpdateState.Failed(UpdateError.Network, null)
            }
        }
    }

    /** Downloads the offered update; [install] continues with the installation right away ("Update now"). */
    fun download(install: Boolean = false) {
        val candidate = when (val state = _state.value) {
            is UpdateState.Available -> state.candidate
            is UpdateState.Failed -> state.candidate
            else -> null
        } ?: return
        installAfterDownload = install
        _state.value = UpdateState.Downloading(candidate, 0)
        job = scope.launch {
            try {
                var shown = 0L
                downloader.download(candidate) { bytes ->
                    // About every percent is plenty for a progress bar.
                    if (bytes - shown >= candidate.apk.sizeBytes / 100 || bytes == candidate.apk.sizeBytes) {
                        shown = bytes
                        _state.value = UpdateState.Downloading(candidate, bytes)
                    }
                }
                _state.value = UpdateState.Ready(candidate)
                if (installAfterDownload) install() else _notices.send(UpdateNotice.Downloaded(candidate.version))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Downloading ${candidate.apk.name} failed", e)
                fail((e as? UpdateException)?.error ?: UpdateError.Network, candidate)
            }
        }
    }

    fun install() {
        val candidate = (_state.value as? UpdateState.Ready)?.candidate ?: return
        _state.value = UpdateState.Installing(candidate)
        scope.launch {
            try {
                // Already checked; download() returns the stored file without fetching it again.
                val file = downloader.download(candidate) { }
                installer.install(file) { result ->
                    when (result) {
                        InstallResult.Aborted -> {
                            _state.value = UpdateState.Ready(candidate)
                            _notices.trySend(UpdateNotice.InstallCancelled)
                        }
                        is InstallResult.Failed -> {
                            Log.w(TAG, "Installing ${candidate.version} failed: ${result.message}")
                            fail(UpdateError.Install, candidate)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Installing ${candidate.version} failed", e)
                fail((e as? UpdateException)?.error ?: UpdateError.Install, candidate)
            }
        }
    }

    /** "Try again" after a failure: repeats the step that failed. */
    fun retry() {
        val failed = _state.value as? UpdateState.Failed ?: return
        if (failed.candidate == null) check() else download(install = failed.error == UpdateError.Install)
    }

    /** The home screen showed [prompt]; it does not come back for this version. */
    fun promptShown() {
        val candidate = _prompt.value ?: return
        prefs.edit { putString(KEY_PROMPTED, candidate.version.toString()) }
        _prompt.value = null
    }

    fun setChannel(channel: UpdateChannel) {
        if (channel == _channel.value) return
        _channel.value = channel
        prefs.edit { putString(KEY_CHANNEL, channel.name).remove(KEY_CANDIDATE) }
        if (busy()) return
        _state.value = UpdateState.Unchecked
        check()
    }

    fun setAutoCheck(on: Boolean) {
        _autoCheck.value = on
        prefs.edit { putBoolean(KEY_AUTO, on) }
    }

    private fun busy(): Boolean = _state.value.let {
        it is UpdateState.Checking || it is UpdateState.Downloading || it is UpdateState.Installing
    }

    private fun fail(error: UpdateError, candidate: UpdateCandidate) {
        _state.value = UpdateState.Failed(error, candidate)
        _notices.trySend(UpdateNotice.Failed(error))
    }

    private fun offer(candidate: UpdateCandidate) {
        if (prefs.getString(KEY_PROMPTED, null) != candidate.version.toString()) _prompt.update { candidate }
    }

    /** The last check's result, if it is still newer than what runs and belongs to the current channel. */
    private fun cachedCandidate(): UpdateCandidate? {
        val text = prefs.getString(KEY_CANDIDATE, null) ?: return null
        if (prefs.getString(KEY_CANDIDATE_CHANNEL, null) != _channel.value.name) return null
        val candidate = runCatching { json.decodeFromString(UpdateCandidate.serializer(), text) }.getOrNull() ?: return null
        val current = installed ?: return null
        return candidate.takeIf { it.version > current }
    }

    private companion object {
        const val TAG = "UpdateManager"
        const val KEY_CHANNEL = "channel"
        const val KEY_AUTO = "auto_check"
        const val KEY_LAST_CHECK = "last_check"
        const val KEY_CANDIDATE = "candidate"
        const val KEY_CANDIDATE_CHANNEL = "candidate_channel"
        const val KEY_PROMPTED = "prompted"
    }
}

