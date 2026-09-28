package io.github.glacier_jellyfin.androidtv.core.data.settings

import android.util.Log
import io.github.glacier_jellyfin.androidtv.core.data.Session
import io.github.glacier_jellyfin.androidtv.core.data.SessionManager
import io.github.glacier_jellyfin.androidtv.core.data.media.Languages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.extensions.localizationApi
import org.jellyfin.sdk.api.client.extensions.userApi
import org.jellyfin.sdk.model.api.SubtitlePlaybackMode
import org.jellyfin.sdk.model.api.UserConfiguration
import java.text.Collator
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Which subtitles start with playback; the server picks them (Jellyfin subtitle mode). */
enum class SubtitleMode(internal val api: SubtitlePlaybackMode) {
    Default(SubtitlePlaybackMode.DEFAULT),
    Smart(SubtitlePlaybackMode.SMART),
    OnlyForced(SubtitlePlaybackMode.ONLY_FORCED),
    Always(SubtitlePlaybackMode.ALWAYS),
    None(SubtitlePlaybackMode.NONE),
}

/**
 * Audio and subtitle preferences from the user's Jellyfin configuration, so
 * they are the same in every Jellyfin client. The server applies languages,
 * the default flag and the subtitle mode when it picks the tracks; the two
 * "remember" switches are applied by the player (see [rememberedTracks]).
 */
data class ServerPreferences(
    /** ISO 639-2 code as the server keeps it; null plays the original (default) track. */
    val audioLanguage: String? = null,
    val playDefaultAudioTrack: Boolean = true,
    val rememberAudio: Boolean = true,
    /** Null: no preferred language. */
    val subtitleLanguage: String? = null,
    val subtitleMode: SubtitleMode = SubtitleMode.Smart,
    val rememberSubtitles: Boolean = true,
)

/** A language the server knows, for the language pickers. */
data class Language(val code: String, val name: String)

@Singleton
class ServerPreferencesRepository @Inject constructor(
    private val sessions: SessionManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()

    private val _preferences = MutableStateFlow<ServerPreferences?>(null)

    /** Null until loaded, or when the server could not be reached. */
    val preferences: StateFlow<ServerPreferences?> = _preferences.asStateFlow()

    private var languages: List<Language>? = null

    init {
        scope.launch {
            sessions.session.distinctUntilChangedBy { it?.user?.userId to it?.server?.id }.collect {
                _preferences.value = null
                languages = null
                if (it != null) refresh()
            }
        }
    }

    /** Loads the preferences again; other clients may have changed them. */
    suspend fun refresh(): ServerPreferences? = withContext(Dispatchers.IO) {
        val session = sessions.session.value ?: return@withContext null
        try {
            session.api.userApi.getCurrentUser().content.configuration?.toPreferences()
                ?.also { if (sessions.session.value === session) _preferences.value = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Loading the user configuration failed", e)
            null
        }
    }

    /**
     * Saves a change on the server. The configuration is read fresh first so
     * fields Glacier does not show (and changes from other clients) survive.
     */
    suspend fun update(transform: (ServerPreferences) -> ServerPreferences): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            val session = sessions.session.value ?: return@withLock false
            try {
                val api = session.api
                val config = api.userApi.getCurrentUser().content.configuration ?: return@withLock false
                val changed = transform(config.toPreferences())
                api.userApi.updateUserConfiguration(session.userId, config.with(changed))
                if (sessions.session.value === session) _preferences.value = changed
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Saving the user configuration failed", e)
                false
            }
        }
    }

    /**
     * The server's languages, named in the device language and sorted by that
     * name. Only languages with a two-letter code: the full list also has
     * hundreds of historic and regional ones nobody picks for a film.
     */
    suspend fun languages(): List<Language> = withContext(Dispatchers.IO) {
        languages?.let { return@withContext it }
        val session = sessions.session.value ?: return@withContext emptyList()
        try {
            session.api.localizationApi.getCultures().content
                .filter { !it.twoLetterIsoLanguageName.isNullOrBlank() }
                .mapNotNull { culture ->
                    val code = culture.threeLetterIsoLanguageName ?: return@mapNotNull null
                    Language(code, Languages.name(code) ?: culture.displayName ?: code)
                }
                .distinctBy { it.code }
                .sortedWith(compareBy(Collator.getInstance()) { it.name })
                .also { languages = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Loading the server's languages failed", e)
            emptyList()
        }
    }

    private val Session.userId: UUID get() = UUID.fromString(user.userId)

    private fun UserConfiguration.toPreferences() = ServerPreferences(
        audioLanguage = audioLanguagePreference?.takeIf { it.isNotBlank() },
        playDefaultAudioTrack = playDefaultAudioTrack,
        rememberAudio = rememberAudioSelections,
        subtitleLanguage = subtitleLanguagePreference?.takeIf { it.isNotBlank() },
        subtitleMode = SubtitleMode.entries.firstOrNull { it.api == subtitleMode } ?: SubtitleMode.Default,
        rememberSubtitles = rememberSubtitleSelections,
    )

    private fun UserConfiguration.with(preferences: ServerPreferences) = copy(
        audioLanguagePreference = preferences.audioLanguage.orEmpty(),
        playDefaultAudioTrack = preferences.playDefaultAudioTrack,
        rememberAudioSelections = preferences.rememberAudio,
        subtitleLanguagePreference = preferences.subtitleLanguage.orEmpty(),
        subtitleMode = preferences.subtitleMode.api,
        rememberSubtitleSelections = preferences.rememberSubtitles,
    )

    private companion object {
        const val TAG = "ServerPreferences"
    }
}
