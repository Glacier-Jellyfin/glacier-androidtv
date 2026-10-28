package io.github.glacier_jellyfin.androidtv.core.data.settings

import io.github.glacier_jellyfin.androidtv.core.data.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jellyfin.sdk.api.client.extensions.displayPreferenceApi
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** One device's copy of a profile's settings, kept on the Jellyfin server. */
@Serializable
data class DeviceSettings(
    /** Jellyfin's device id of the app on that device. */
    val deviceId: String,
    val deviceName: String,
    val savedAt: Long,
    val settings: ProfileSettings,
)

/*
 * The copies live in the user's display preferences of their own (item and client
 * below), one custom preference per device. Nothing else is stored there, so a
 * write sends back only these keys.
 */
private const val PREFERENCES_ID = "glacier-settings"
private const val CLIENT = "glacier"
private const val KEY_PREFIX = "device."

private val json = Json {
    ignoreUnknownKeys = true
    // As in SettingsSerializer: a copy from another version falls back to defaults where it does not fit.
    coerceInputValues = true
}

/** The device copies among [prefs], newest first; ones that cannot be read (another app's, a damaged one) are left out. */
internal fun decodeDeviceSettings(prefs: Map<String, String?>): List<DeviceSettings> =
    prefs.filterKeys { it.startsWith(KEY_PREFIX) }.values.mapNotNull { value ->
        value ?: return@mapNotNull null
        try {
            json.decodeFromString(DeviceSettings.serializer(), value)
        } catch (e: IllegalArgumentException) {
            // SerializationException is one too.
            null
        }
    }.sortedByDescending { it.savedAt }

/** [prefs] (our keys only) with [copy] put in, or with [removeDeviceId]'s copy taken out. */
internal fun withDeviceSettings(prefs: Map<String, String?>, copy: DeviceSettings? = null, removeDeviceId: String? = null): Map<String, String?> {
    val own = prefs.filterKeys { it.startsWith(KEY_PREFIX) }
    return when {
        copy != null -> own + (KEY_PREFIX + copy.deviceId to json.encodeToString(DeviceSettings.serializer(), copy))
        removeDeviceId != null -> own - (KEY_PREFIX + removeDeviceId)
        else -> own
    }
}

/** Reads and writes the [DeviceSettings] of the signed-in profile on its server. */
@Singleton
class SettingsBackup @Inject constructor() {

    // Each write reads all copies first; two writes from this device must not overlap.
    private val writing = Mutex()

    suspend fun list(session: Session): List<DeviceSettings> = decodeDeviceSettings(read(session).customPrefs)

    suspend fun save(session: Session, copy: DeviceSettings) = change(session) { withDeviceSettings(it, copy = copy) }

    suspend fun delete(session: Session, deviceId: String) = change(session) { withDeviceSettings(it, removeDeviceId = deviceId) }

    private suspend fun change(session: Session, transform: (Map<String, String?>) -> Map<String, String?>) = writing.withLock {
        val current = read(session)
        withContext(Dispatchers.IO) {
            session.api.displayPreferenceApi.updateDisplayPreferences(PREFERENCES_ID, userId(session), CLIENT, current.copy(customPrefs = transform(current.customPrefs)))
        }
    }

    private suspend fun read(session: Session) = withContext(Dispatchers.IO) {
        session.api.displayPreferenceApi.getDisplayPreferences(PREFERENCES_ID, userId(session), CLIENT).content
    }

    private fun userId(session: Session): UUID = UUID.fromString(session.user.userId)
}
