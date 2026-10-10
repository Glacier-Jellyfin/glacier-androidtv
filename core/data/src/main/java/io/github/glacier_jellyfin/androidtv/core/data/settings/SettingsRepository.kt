package io.github.glacier_jellyfin.androidtv.core.data.settings

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import io.github.glacier_jellyfin.androidtv.core.data.SessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** Settings of every profile on this device, keyed by [profileKey]. */
@Serializable
data class SettingsState(
    val profiles: Map<String, ProfileSettings> = emptyMap(),
)

internal fun profileKey(serverId: String, userId: String) = "$serverId/$userId"

/** Plain JSON: settings hold nothing secret. */
object SettingsSerializer : Serializer<SettingsState> {

    private val json = Json {
        ignoreUnknownKeys = true
        // An option dropped in a later version falls back to its default instead of failing the whole file.
        coerceInputValues = true
    }

    override val defaultValue = SettingsState()

    override suspend fun readFrom(input: InputStream): SettingsState {
        val text = input.readBytes().decodeToString()
        if (text.isBlank()) return defaultValue
        return try {
            json.decodeFromString(SettingsState.serializer(), text)
        } catch (e: SerializationException) {
            throw CorruptionException("Cannot parse settings", e)
        }
    }

    override suspend fun writeTo(t: SettingsState, output: OutputStream) {
        output.write(json.encodeToString(SettingsState.serializer(), t).encodeToByteArray())
    }
}

/** The signed-in profile's [ProfileSettings]; defaults while no one is signed in. */
@Singleton
class SettingsRepository @Inject constructor(
    private val store: DataStore<SettingsState>,
    private val sessions: SessionManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The signed-in profile's settings, null while no one is signed in; emits on every profile switch. */
    val active: Flow<ProfileSettings?> = combine(sessions.session, store.data) { session, state ->
        session?.let { state.profiles[profileKey(it.server.id, it.user.userId)] ?: ProfileSettings() }
    }

    val settings: StateFlow<ProfileSettings> = active.map { it ?: ProfileSettings() }
        .stateIn(scope, SharingStarted.Eagerly, ProfileSettings())

    /** The current value, read from the file when [settings] has not caught up yet (right after start). */
    suspend fun current(): ProfileSettings {
        val session = sessions.session.value ?: return ProfileSettings()
        return store.data.first().profiles[profileKey(session.server.id, session.user.userId)] ?: ProfileSettings()
    }

    suspend fun update(transform: (ProfileSettings) -> ProfileSettings) {
        val session = sessions.session.value ?: return
        val key = profileKey(session.server.id, session.user.userId)
        store.updateData { state ->
            state.copy(profiles = state.profiles + (key to transform(state.profiles[key] ?: ProfileSettings())))
        }
    }

    suspend fun updatePlayback(transform: (PlaybackSettings) -> PlaybackSettings) = update { it.copy(playback = transform(it.playback)) }

    suspend fun updateAppearance(transform: (AppearanceSettings) -> AppearanceSettings) = update { it.copy(appearance = transform(it.appearance)) }

    suspend fun updateHome(transform: (HomeSettings) -> HomeSettings) = update { it.copy(home = transform(it.home)) }

    suspend fun updateNavigation(transform: (NavigationSettings) -> NavigationSettings) = update { it.copy(navigation = transform(it.navigation)) }

    suspend fun updateSubtitleStyle(transform: (SubtitleStyle) -> SubtitleStyle) = update { it.copy(subtitleStyle = transform(it.subtitleStyle)) }
}
