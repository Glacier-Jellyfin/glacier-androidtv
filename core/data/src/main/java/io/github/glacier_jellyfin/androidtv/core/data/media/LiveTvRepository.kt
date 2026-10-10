package io.github.glacier_jellyfin.androidtv.core.data.media

import io.github.glacier_jellyfin.androidtv.core.data.Session
import io.github.glacier_jellyfin.androidtv.core.data.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.api.client.extensions.liveTvApi
import org.jellyfin.sdk.api.client.extensions.userDataApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** A Live TV channel with what runs on it now, as far as the guide knows. */
data class LiveChannel(
    val id: UUID,
    /** "101"; null when the tuner has no numbers. */
    val number: String?,
    val name: String,
    val logoUrl: String?,
    val favorite: Boolean,
    val now: LiveProgram?,
)

/** One programme of the guide. Times are epoch milliseconds. */
data class LiveProgram(
    val id: String,
    val channelId: UUID,
    val title: String,
    /** The episode's own title, e.g. "Episode 4". */
    val episodeTitle: String?,
    val overview: String?,
    val startMs: Long,
    val endMs: Long,
    val imageUrl: String?,
    val category: ProgramCategory?,
    /** Shown for the first time (the guide's "new" flag). */
    val new: Boolean,
) {
    fun airsAt(timeMs: Long): Boolean = timeMs in startMs until endMs

    /** How much of it ran at [timeMs], 0–1. */
    fun progressAt(timeMs: Long): Float =
        if (endMs <= startMs) 0f else ((timeMs - startMs).toFloat() / (endMs - startMs)).coerceIn(0f, 1f)
}

enum class ProgramCategory { Movie, Series, News, Sports, Kids }

/**
 * The programme on air at [timeMs] and the one after it, from [programs] of
 * one channel in any order. The next one is the first to start after the
 * current one ends, or after [timeMs] when nothing airs.
 */
fun nowAndNext(programs: List<LiveProgram>, timeMs: Long): Pair<LiveProgram?, LiveProgram?> {
    val sorted = programs.sortedBy { it.startMs }
    val now = sorted.firstOrNull { it.airsAt(timeMs) }
    val next = sorted.firstOrNull { it.startMs >= (now?.endMs ?: timeMs) }
    return now to next
}

/** The channel after (or before, [by] -1) [current] in [channels], wrapping around at either end. */
fun channelAfter(channels: List<LiveChannel>, current: UUID, by: Int): LiveChannel? {
    if (channels.isEmpty()) return null
    val index = channels.indexOfFirst { it.id == current }
    if (index < 0) return channels.first()
    return channels[(index + by).mod(channels.size)]
}

/** The channel a typed number picks: the exact number, else none. */
fun channelByNumber(channels: List<LiveChannel>, number: String): LiveChannel? =
    channels.firstOrNull { it.number == number }
        ?: number.trimStart('0').takeIf { it.isNotEmpty() }?.let { trimmed -> channels.firstOrNull { it.number?.trimStart('0') == trimmed } }

/** Live TV through the server: its channels (as the tuners and the guide set them up) and their programmes. */
@Singleton
class LiveTvRepository @Inject constructor(
    private val sessions: SessionManager,
) {

    /** All channels in the server's order (by number), each with the programme on air now. */
    suspend fun channels(): List<LiveChannel> = withContext(Dispatchers.IO) {
        val session = requireSession()
        val api = session.api
        api.liveTvApi.getLiveTvChannels(
            userId = session.userId,
            enableImages = true,
            imageTypeLimit = 1,
            enableImageTypes = listOf(ImageType.PRIMARY),
            enableUserData = true,
            addCurrentProgram = true,
            fields = listOf(ItemFields.OVERVIEW),
        ).content.items.map { it.toChannel(api) }
    }

    /** The programmes of [channelId] from what airs now to [hours] ahead. */
    suspend fun programs(channelId: UUID, hours: Long = 12): List<LiveProgram> = withContext(Dispatchers.IO) {
        val session = requireSession()
        val api = session.api
        val now = LocalDateTime.now()
        api.liveTvApi.getLiveTvPrograms(
            channelIds = listOf(channelId),
            userId = session.userId,
            minEndDate = now,
            maxStartDate = now.plusHours(hours),
            sortBy = listOf(ItemSortBy.START_DATE),
            sortOrder = listOf(SortOrder.ASCENDING),
            enableImages = true,
            imageTypeLimit = 1,
            enableImageTypes = listOf(ImageType.PRIMARY, ImageType.THUMB),
            fields = listOf(ItemFields.OVERVIEW),
            enableTotalRecordCount = false,
        ).content.items.mapNotNull { it.toProgram(api) }
    }

    /** The guide of [channelIds] from [fromMs] to [toMs], by channel; channels without data are missing. */
    suspend fun guide(channelIds: List<UUID>, fromMs: Long, toMs: Long): Map<UUID, List<LiveProgram>> = withContext(Dispatchers.IO) {
        if (channelIds.isEmpty()) return@withContext emptyMap()
        val session = requireSession()
        val api = session.api
        api.liveTvApi.getLiveTvPrograms(
            channelIds = channelIds,
            userId = session.userId,
            minEndDate = fromMs.toLocalDateTime(),
            maxStartDate = toMs.toLocalDateTime(),
            sortBy = listOf(ItemSortBy.START_DATE),
            sortOrder = listOf(SortOrder.ASCENDING),
            enableImages = false,
            fields = listOf(ItemFields.OVERVIEW),
            enableTotalRecordCount = false,
        ).content.items.mapNotNull { it.toProgram(api) }.groupBy { it.channelId }
    }

    suspend fun setFavorite(channelId: UUID, favorite: Boolean) = withContext(Dispatchers.IO) {
        val api = requireSession().api.userDataApi
        if (favorite) api.markFavoriteItem(channelId) else api.unmarkFavoriteItem(channelId)
        Unit
    }

    private fun BaseItemDto.toChannel(api: ApiClient) = LiveChannel(
        id = id,
        number = channelNumber?.takeIf { it.isNotBlank() },
        name = name.orEmpty(),
        logoUrl = imageTags?.get(ImageType.PRIMARY)?.let {
            api.imageApi.getItemImageUrl(itemId = id, imageType = ImageType.PRIMARY, tag = it, maxWidth = LOGO_WIDTH)
        },
        favorite = userData?.isFavorite == true,
        now = currentProgram?.toProgram(api, channel = id),
    )

    private fun BaseItemDto.toProgram(api: ApiClient, channel: UUID? = null): LiveProgram? {
        val start = startDate ?: return null
        val end = endDate ?: return null
        val image = imageTags?.get(ImageType.PRIMARY)?.let { id to (ImageType.PRIMARY to it) }
            ?: imageTags?.get(ImageType.THUMB)?.let { id to (ImageType.THUMB to it) }
        return LiveProgram(
            id = id.toString(),
            channelId = channelId ?: channel ?: return null,
            title = name.orEmpty(),
            episodeTitle = episodeTitle?.takeIf { it.isNotBlank() && it != name },
            overview = plainText(overview)?.takeIf { it.isNotBlank() },
            startMs = start.toEpochMs(),
            endMs = end.toEpochMs(),
            imageUrl = image?.let { (item, typeAndTag) ->
                api.imageApi.getItemImageUrl(itemId = item, imageType = typeAndTag.first, tag = typeAndTag.second, maxWidth = PROGRAM_WIDTH)
            },
            category = when {
                isMovie == true -> ProgramCategory.Movie
                isSports == true -> ProgramCategory.Sports
                isNews == true -> ProgramCategory.News
                isKids == true -> ProgramCategory.Kids
                isSeries == true -> ProgramCategory.Series
                else -> null
            },
            new = isPremiere == true,
        )
    }

    private fun requireSession(): Session = checkNotNull(sessions.session.value) { "No profile is signed in" }

    private val Session.userId: UUID get() = UUID.fromString(user.userId)

    private companion object {
        const val LOGO_WIDTH = 320
        const val PROGRAM_WIDTH = 720
    }
}

/** The SDK reads server times into the device's time zone. */
private fun LocalDateTime.toEpochMs(): Long = atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

private fun Long.toLocalDateTime(): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(this), ZoneId.systemDefault())
