package io.github.glacier_jellyfin.androidtv.core.data.media

import io.github.glacier_jellyfin.androidtv.core.jellyfin.runSuspendCatching
import io.github.glacier_jellyfin.androidtv.core.log.Log
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.liveTvApi

/**
 * The libraries where Live TV keeps its recordings. They are ordinary libraries, so
 * lists of movies and shows load library by library and skip these. The recordings
 * stay under Live TV > Recordings.
 *
 * The folders are asked for once and kept for a few minutes.
 */
@Singleton
class RecordingFolders @Inject constructor() {
    private val mutex = Mutex()
    private var cacheKey: Pair<String?, UUID>? = null
    private var cachedAt = 0L
    private var cached = emptySet<UUID>()

    /** The recording folder ids of [userId]. Empty when the server has no Live TV or fails. */
    suspend fun of(api: ApiClient, userId: UUID): Set<UUID> = mutex.withLock {
        val key = api.baseUrl to userId
        val now = System.currentTimeMillis()
        if (key == cacheKey && now - cachedAt < FRESH_MS) return@withLock cached
        val folders = runSuspendCatching {
            api.liveTvApi.getRecordingFolders(userId = userId).content.items.map { it.id }.toSet()
        }.onFailure { Log.w(TAG, "Listing the recording folders failed", it) }
            .getOrDefault(emptySet())
        cacheKey = key
        cachedAt = now
        cached = folders
        folders
    }

    /** Forgets what was found, after a recording was deleted. */
    fun invalidate() {
        cacheKey = null
        cached = emptySet()
    }

    private companion object {
        const val TAG = "RecordingFolders"
        const val FRESH_MS = 5 * 60_000L
    }
}
