package io.github.glacier_jellyfin.androidtv.core.data.media

import io.github.glacier_jellyfin.androidtv.core.data.Session
import io.github.glacier_jellyfin.androidtv.core.jellyfin.runSuspendCatching
import io.github.glacier_jellyfin.androidtv.core.log.Log
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jellyfin.sdk.api.client.extensions.libraryStructureApi
import org.jellyfin.sdk.api.client.extensions.systemApi
import org.jellyfin.sdk.api.client.extensions.userApi

/**
 * The metadata language the Jellyfin server uses, which "Automatic" article sorting follows: the one
 * of the library when the user may read it (administrators only, the server answers others with 403),
 * else the server-wide one. Both are read once per server and user and kept for a few minutes,
 * never per listing. A failing request means "not available" (null), never an error.
 */
@Singleton
class MetadataLanguage @Inject constructor() {

    /** What the server told: [libraries] by id without dashes (null when not readable), and the server-wide language. */
    private class Loaded(
        val at: Long,
        val libraries: Map<String, String?>?,
        val server: String?,
    )

    private val cache = HashMap<String, Loaded>()
    private val lock = Mutex()

    /**
     * The raw metadata language ("de", "pt-BR") for [libraryIds]: the language all of these libraries
     * share when the user is an administrator, else the server-wide one; null when neither is known.
     * [libraryIds] null or empty skips the libraries.
     */
    suspend fun of(session: Session, libraryIds: Collection<UUID>?): String? {
        val loaded = load(session)
        val shared = if (libraryIds.isNullOrEmpty()) null else sharedLanguage(loaded.libraries, libraryIds)
        return shared ?: loaded.server
    }

    /** The server-wide language alone. */
    suspend fun ofServer(session: Session): String? = load(session).server

    private suspend fun load(session: Session): Loaded = lock.withLock {
        val key = "${session.server.id}|${session.user.userId}"
        val now = System.currentTimeMillis()
        cache[key]?.let { if (now - it.at < FRESH_MS) return it }
        val api = session.api
        val admin = runSuspendCatching { api.userApi.getCurrentUser().content.policy?.isAdministrator == true }
            .getOrDefault(false)
        val libraries = if (admin) {
            runSuspendCatching {
                api.libraryStructureApi.getVirtualFolders().content.associate { folder ->
                    normalized(folder.itemId.orEmpty()) to folder.libraryOptions?.preferredMetadataLanguage
                }
            }.onFailure { Log.w(TAG, "Reading the library languages failed", it) }.getOrNull()
        } else {
            null
        }
        val server = runSuspendCatching { api.systemApi.getConfiguration().content.preferredMetadataLanguage }
            .onFailure { Log.w(TAG, "Reading the server language failed", it) }
            .getOrNull()
        val loaded = Loaded(now, libraries, server?.takeIf { it.isNotBlank() })
        // A failed read is tried again sooner.
        val failed = server == null
        cache[key] = if (failed) Loaded(now - FRESH_MS + FAILED_RETRY_MS, libraries, null) else loaded
        loaded
    }

    companion object {
        private const val TAG = "MetadataLanguage"
        private const val FRESH_MS = 5 * 60_000L
        private const val FAILED_RETRY_MS = 30_000L

        private fun normalized(id: String) = id.replace("-", "").lowercase(Locale.ROOT)

        /**
         * The language all of [ids] are set to, as the server wrote it; null when the languages are not
         * known, when a library has none, or when they differ ("de" and "de-DE" are the same language).
         */
        internal fun sharedLanguage(languages: Map<String, String?>?, ids: Collection<UUID>): String? {
            if (languages == null || ids.isEmpty()) return null
            val each = ids.map { languages[normalized(it.toString())]?.takeIf { code -> code.isNotBlank() } }
            if (each.any { it == null }) return null
            val first = each.first() ?: return null
            fun base(code: String) = code.lowercase(Locale.ROOT).substringBefore('-').substringBefore('_')
            return first.takeIf { each.all { code -> base(code.orEmpty()) == base(first) } }
        }
    }
}
