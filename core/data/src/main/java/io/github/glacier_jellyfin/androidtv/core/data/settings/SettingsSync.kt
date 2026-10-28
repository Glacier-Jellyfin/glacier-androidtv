package io.github.glacier_jellyfin.androidtv.core.data.settings

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.datastore.core.DataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.glacier_jellyfin.androidtv.core.data.Session
import io.github.glacier_jellyfin.androidtv.core.data.SessionManager
import io.github.glacier_jellyfin.androidtv.core.jellyfin.runSuspendCatching
import io.github.glacier_jellyfin.androidtv.core.log.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** The question once a profile is new on this device: take the settings one of [copies] holds? */
data class RestoreOffer(val profileKey: String, val userName: String, val copies: List<DeviceSettings>, val thisDeviceId: String)

/**
 * Keeps a copy of the signed-in profile's settings on its Jellyfin server, one
 * per device ([SettingsBackup]), so they come back after the app was installed
 * again, from another store, or on another TV.
 *
 * A profile new on this device (no settings here yet) is first offered the
 * copies found on the server, once; until it is answered nothing is uploaded,
 * so an older copy of this very device is not overwritten with defaults.
 */
@Singleton
class SettingsSync @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: DataStore<SettingsState>,
    private val sessions: SessionManager,
    private val backup: SettingsBackup,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var started = false

    private val _offer = MutableStateFlow<RestoreOffer?>(null)
    val offer: StateFlow<RestoreOffer?> = _offer.asStateFlow()

    private val _copies = MutableStateFlow<List<DeviceSettings>>(emptyList())

    /** The signed-in profile's copies on the server, this device's included; empty while unknown. */
    val copies: StateFlow<List<DeviceSettings>> = _copies.asStateFlow()

    fun start() {
        if (started) return
        started = true
        scope.launch {
            sessions.session.collectLatest { session ->
                _offer.value = null
                _copies.value = emptyList()
                if (session != null) follow(session)
            }
        }
    }

    private suspend fun follow(session: Session) {
        val key = profileKey(session.server.id, session.user.userId)
        val deviceId = session.api.deviceInfo.id
        val copies = runSuspendCatching { backup.list(session) }
            .onFailure { Log.w(TAG, "Cannot read the settings copies on the server", it) }
            .getOrNull()
        copies?.let { _copies.value = it }
        val state = store.data.first()
        if (key !in state.restoreOffered) {
            // Unreachable server: asked next time, and nothing is uploaded meanwhile.
            copies ?: return
            if (copies.isNotEmpty() && state.profiles[key] == null) {
                _offer.value = RestoreOffer(key, session.user.name, copies, deviceId)
                _offer.first { it == null }
            } else {
                markOffered(key)
            }
        }
        var uploaded = copies?.firstOrNull { it.deviceId == deviceId }?.settings
        // Debounced: a burst of changes (moving rows, a slider) goes up once.
        store.data.map { it.profiles[key] }.distinctUntilChanged().collectLatest { settings ->
            if (settings == null || settings == uploaded) return@collectLatest
            delay(UPLOAD_DELAY_MS)
            val copy = DeviceSettings(deviceId, deviceName(), System.currentTimeMillis(), settings)
            runSuspendCatching { backup.save(session, copy) }
                .onSuccess {
                    uploaded = settings
                    _copies.value = listOf(copy) + _copies.value.filterNot { it.deviceId == deviceId }
                }
                .onFailure { Log.w(TAG, "Uploading the settings failed; tried again with the next change or start", it) }
        }
    }

    /** Answers [offer]: [copy] replaces the profile's settings here, null keeps them. */
    suspend fun answer(copy: DeviceSettings?) {
        val offer = _offer.value ?: return
        store.updateData { state ->
            val profiles = if (copy != null) state.profiles + (offer.profileKey to copy.settings) else state.profiles
            state.copy(profiles = profiles, restoreOffered = state.restoreOffered + offer.profileKey)
        }
        _offer.value = null
    }

    /** Settings › Account: takes [copy]'s settings for the signed-in profile. */
    suspend fun adopt(copy: DeviceSettings) {
        val session = sessions.session.value ?: return
        val key = profileKey(session.server.id, session.user.userId)
        store.updateData { it.copy(profiles = it.profiles + (key to copy.settings)) }
    }

    /** Deletes this device's copy of the signed-in profile on the server. */
    suspend fun deleteOwnCopy(): Boolean {
        val session = sessions.session.value ?: return false
        return deleteCopy(session.api.deviceInfo.id)
    }

    /** Deletes [deviceId]'s copy of the signed-in profile on the server, like an old install's or a TV no longer used. */
    suspend fun deleteCopy(deviceId: String): Boolean {
        val session = sessions.session.value ?: return false
        return runSuspendCatching { backup.delete(session, deviceId) }
            .onSuccess { _copies.value = _copies.value.filterNot { it.deviceId == deviceId } }
            .onFailure { Log.w(TAG, "Deleting a settings copy failed", it) }
            .isSuccess
    }

    /** Forgets everything of a profile removed from this device; signing in again asks once more. */
    suspend fun forget(serverId: String, userId: String) {
        val key = profileKey(serverId, userId)
        store.updateData { it.copy(profiles = it.profiles - key, restoreOffered = it.restoreOffered - key) }
    }

    /** The name the TV goes by in its own settings ("Living room"), else maker and model; the SDK has the bare model code. */
    private fun deviceName(): String =
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() }
            ?: listOf(Build.MANUFACTURER.replaceFirstChar { it.uppercase() }, Build.MODEL).distinct().joinToString(" ")

    private suspend fun markOffered(key: String) {
        store.updateData { it.copy(restoreOffered = it.restoreOffered + key) }
    }

    private companion object {
        const val TAG = "SettingsSync"
        const val UPLOAD_DELAY_MS = 2_000L
    }
}
