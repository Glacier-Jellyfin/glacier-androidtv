package io.github.glacier_jellyfin.androidtv.core.data.media

import io.github.glacier_jellyfin.androidtv.core.data.Session
import io.github.glacier_jellyfin.androidtv.core.data.SessionManager
import io.github.glacier_jellyfin.androidtv.core.jellyfin.runSuspendCatching
import io.github.glacier_jellyfin.androidtv.core.log.Log
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.model.api.ItemFields

enum class ActivityType { Watched, Favorited }

/** Someone who watched or favorited a title. The UI draws [initials] when there is no [imageUrl]. */
data class ActivityUser(
    val id: UUID,
    val name: String,
    val imageUrl: String?,
) {
    /** First letter of the first word, plus the first letter of the last word when there are two or more. */
    val initials: String
        get() {
            val words = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (words.isEmpty()) return "?"
            val first = words.first().first().uppercaseChar()
            return if (words.size >= 2) "$first${words.last().first().uppercaseChar()}" else "$first"
        }
}

/** One thing a user did with a title: [progress] runs from 0 to 1. */
data class ActivityEntry(
    val type: ActivityType,
    val user: ActivityUser,
    val timestampMs: Long,
    val item: MediaItem,
    val completed: Boolean,
    val progress: Float,
)

/** The entries of a feed read for the profile [key] (see [ActivityRepository.keyOf]). */
data class LatestFeed(val key: String, val entries: List<ActivityEntry>)

/**
 * The activity feed: what other users watched and favorited, as far as the signed-in user may see it.
 * It asks an [ActivitySource] and fails soft: without a source, or with the feed switched off,
 * [feed] returns null.
 */
@Singleton
class ActivityRepository @Inject constructor(
    private val sessions: SessionManager,
    private val ageFilter: AgeFilter,
    private val source: ActivitySource,
) {
    private val mutex = Mutex()
    private val availability = FeedAvailability { System.currentTimeMillis() }
    private var cacheKey: String? = null
    private var cacheAt = 0L
    private var cached: List<ActivityEntry> = emptyList()

    private val _available = MutableStateFlow(false)

    /** What the last answer said for the current profile: false until known. */
    val available: StateFlow<Boolean> = _available.asStateFlow()

    private val _latest = MutableStateFlow<LatestFeed?>(null)

    /**
     * The entries of the last successful read, for whoever shows them without asking again, such as
     * the avatars on cards. Null when the feed is not usable. Check [LatestFeed.key] against [keyOf]
     * before use, as it still holds the entries of the profile that was signed in before a switch.
     */
    val latest: StateFlow<LatestFeed?> = _latest.asStateFlow()

    /** Drops the cached feed, for after something was played. */
    fun invalidate() {
        cacheKey = null
    }

    /**
     * The newest [limit] entries, newest first. Null when the feed cannot be
     * used (switched off, plugin missing, no access, server not reached), an
     * empty list when nothing happened yet.
     */
    suspend fun feed(limit: Int = DEFAULT_LIMIT): List<ActivityEntry>? = withContext(Dispatchers.IO) {
        val session = sessions.session.value
        if (session == null) {
            _available.value = false
            _latest.value = null
            return@withContext null
        }
        val key = keyOf(session)
        mutex.withLock {
            val now = System.currentTimeMillis()
            if (cacheKey == key && now - cacheAt < CACHE_MS) {
                return@withLock cached.take(limit)
            }
            if (!availability.shouldAsk(key)) {
                _available.value = false
                _latest.value = null
                return@withLock null
            }
            val raw = when (val result = source.fetch(session, limit.coerceIn(1, MAX_LIMIT))) {
                SourceResult.Unavailable -> {
                    setAvailable(key, false)
                    _latest.value = null
                    return@withLock null
                }

                SourceResult.Failed -> return@withLock null

                is SourceResult.Entries -> result.entries
            }
            setAvailable(key, true)
            val entries = runSuspendCatching { hydrate(session, raw) }
                .onFailure { Log.w(TAG, "Activity items not loaded", it) }
                .getOrNull() ?: return@withLock null
            cacheKey = key
            cacheAt = now
            cached = entries
            _latest.value = LatestFeed(key, entries)
            entries.take(limit)
        }
    }

    /** Names the profile on a server that a feed belongs to. */
    fun keyOf(session: Session): String = "${session.server.id}/${session.user.userId}"

    private fun setAvailable(key: String, value: Boolean) {
        availability.record(key, value)
        if (_available.value != value) Log.i(TAG, "Activity feed ${if (value) "available" else "unavailable"}")
        _available.value = value
    }

    private suspend fun hydrate(session: Session, raw: List<RawActivity>): List<ActivityEntry> {
        if (raw.isEmpty()) return emptyList()
        val ids = raw.map { it.itemId }.distinct()
        val mapper = MediaMapper(session.api)
        val items = ids.chunked(ITEM_CHUNK).flatMap { chunk ->
            session.api.libraryApi.getItems(
                userId = UUID.fromString(session.user.userId),
                ids = chunk,
                fields = FIELDS,
                enableUserData = true,
            ).content.items.map(mapper::item)
        }
        val visible = ageFilter.screen(items).associateBy { it.id }
        val address = session.server.address.trimEnd('/')
        return raw.mapNotNull { entry ->
            val item = visible[entry.itemId] ?: return@mapNotNull null
            ActivityEntry(
                type = entry.type,
                user = ActivityUser(
                    id = entry.userId,
                    name = entry.userName,
                    imageUrl = "$address/Users/${entry.userId.toString().replace("-", "")}/Images/Primary?maxHeight=192"
                        .takeIf { entry.userHasImage },
                ),
                timestampMs = entry.timestampMs,
                item = item,
                completed = entry.completed,
                progress = entry.progress,
            )
        }
    }

    private companion object {
        const val TAG = "Activity"
        const val DEFAULT_LIMIT = 200
        const val MAX_LIMIT = 200
        const val CACHE_MS = 30_000L
        const val ITEM_CHUNK = 100
        val FIELDS = listOf(ItemFields.OVERVIEW, ItemFields.GENRES, ItemFields.MEDIA_STREAMS)
    }
}

/**
 * Remembers which server and user pairs answered "no feed". Such a no is not
 * asked again for [RECHECK_MS], as the admin may switch the feed on meanwhile.
 * A yes is cheap to confirm and is asked again every time.
 */
internal class FeedAvailability(private val clock: () -> Long) {
    private val unavailableSince = HashMap<String, Long>()

    fun shouldAsk(key: String): Boolean {
        val since = unavailableSince[key] ?: return true
        return clock() - since >= RECHECK_MS
    }

    fun record(key: String, available: Boolean) {
        if (available) unavailableSince.remove(key) else unavailableSince[key] = clock()
    }

    companion object {
        const val RECHECK_MS = 5 * 60_000L
    }
}
