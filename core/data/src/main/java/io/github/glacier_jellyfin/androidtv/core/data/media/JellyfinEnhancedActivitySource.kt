package io.github.glacier_jellyfin.androidtv.core.data.media

import io.github.glacier_jellyfin.androidtv.core.data.Session
import io.github.glacier_jellyfin.androidtv.core.jellyfin.runSuspendCatching
import io.github.glacier_jellyfin.androidtv.core.log.Log
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jellyfin.sdk.api.client.HttpMethod
import org.jellyfin.sdk.api.client.exception.InvalidStatusException

/** Where the activity feed comes from. Only this file knows the plugin that serves it. */
interface ActivitySource {
    /** The newest [limit] entries of what users watched and favorited, before their items are loaded. */
    suspend fun fetch(session: Session, limit: Int): SourceResult
}

/** The answer of an [ActivitySource]. */
sealed interface SourceResult {
    /** There is no feed: the plugin is missing or the feed is switched off or not allowed. */
    data object Unavailable : SourceResult

    /** The feed could not be read this time: the server was not reached or sent something unreadable. */
    data object Failed : SourceResult

    data class Entries(val entries: List<RawActivity>) : SourceResult
}

/** An entry as the source sent it, before the item is loaded. */
data class RawActivity(
    val type: ActivityType,
    val userId: UUID,
    val userName: String,
    val userHasImage: Boolean,
    val timestampMs: Long,
    val itemId: UUID,
    val itemType: String,
    val seriesId: UUID?,
    val completed: Boolean,
    val progress: Float,
)

/** The feed of the Jellyfin Enhanced plugin. */
@Singleton
class JellyfinEnhancedActivitySource @Inject constructor() : ActivitySource {
    override suspend fun fetch(session: Session, limit: Int): SourceResult {
        val response = runSuspendCatching {
            session.api.request(HttpMethod.GET, PATH, emptyMap(), mapOf("limit" to limit), null)
        }
        val failure = response.exceptionOrNull()
        if (failure != null) {
            if (failure is InvalidStatusException && failure.status in Unavailable) return SourceResult.Unavailable
            Log.w(TAG, "Activity feed not loaded", failure)
            return SourceResult.Failed
        }
        return runSuspendCatching { parseActivity(response.getOrThrow().body.decodeToString()) }
            .onFailure { Log.w(TAG, "Activity feed unreadable", it) }
            .getOrNull()
            ?.let(SourceResult::Entries)
            ?: SourceResult.Failed
    }

    private companion object {
        const val TAG = "Activity"
        const val PATH = "/JellyfinEnhanced/activity"
        val Unavailable = setOf(401, 404)
    }
}

private val activityJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
}

private val VideoTypes = setOf("Movie", "Episode", "Video")

/**
 * Reads the feed. Only watched and favorited entries stay, only for movies,
 * episodes and videos (and shows when favorited). Entries with an unreadable id are dropped.
 */
internal fun parseActivity(json: String): List<RawActivity> =
    activityJson.decodeFromString<ActivityDto>(json).items.mapNotNull { dto ->
        val type = ActivityType.entries.firstOrNull { it.name == dto.activityType } ?: return@mapNotNull null
        val item = dto.item ?: return@mapNotNull null
        val itemType = item.type.orEmpty()
        if (itemType !in VideoTypes && !(type == ActivityType.Favorited && itemType == "Series")) {
            return@mapNotNull null
        }
        RawActivity(
            type = type,
            userId = dto.userId?.let(::parseJellyfinId) ?: return@mapNotNull null,
            userName = dto.userName.orEmpty(),
            userHasImage = dto.userHasPrimaryImage,
            timestampMs = dto.timestamp,
            itemId = item.id?.let(::parseJellyfinId) ?: return@mapNotNull null,
            itemType = itemType,
            seriesId = item.seriesId?.let(::parseJellyfinId),
            completed = dto.completed,
            progress = dto.progress.coerceIn(0f, 1f),
        )
    }

@Serializable
private data class ActivityDto(val items: List<ActivityEntryDto> = emptyList())

@Serializable
private data class ActivityEntryDto(
    @SerialName("ActivityType") val activityType: String? = null,
    @SerialName("UserId") val userId: String? = null,
    @SerialName("UserName") val userName: String? = null,
    @SerialName("UserHasPrimaryImage") val userHasPrimaryImage: Boolean = false,
    @SerialName("Timestamp") val timestamp: Long = 0,
    @SerialName("Item") val item: ActivityItemDto? = null,
    @SerialName("Completed") val completed: Boolean = false,
    @SerialName("Progress") val progress: Float = 0f,
)

@Serializable
private data class ActivityItemDto(
    @SerialName("Id") val id: String? = null,
    @SerialName("Type") val type: String? = null,
    @SerialName("SeriesId") val seriesId: String? = null,
)
