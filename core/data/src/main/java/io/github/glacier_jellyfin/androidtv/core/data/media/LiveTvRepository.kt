package io.github.glacier_jellyfin.androidtv.core.data.media

import io.github.glacier_jellyfin.androidtv.core.data.Session
import io.github.glacier_jellyfin.androidtv.core.data.SessionManager
import io.github.glacier_jellyfin.androidtv.core.data.settings.ChannelSort
import io.github.glacier_jellyfin.androidtv.core.data.settings.LiveTvSettings
import io.github.glacier_jellyfin.androidtv.core.data.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.api.client.extensions.liveTvApi
import org.jellyfin.sdk.api.client.extensions.userDataApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.ChannelType
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.RecordingStatus
import org.jellyfin.sdk.model.api.SeriesTimerInfoDto
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.api.TimerInfoDto
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
    /** A radio station: sound only, played like music. */
    val radio: Boolean = false,
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
    /** An episode of a series, so the whole series can be recorded. */
    val series: Boolean = false,
    /** The server records it (or will). */
    val timerId: String? = null,
    /** The server records it as part of a series. */
    val seriesTimerId: String? = null,
) {
    val recorded: Boolean get() = timerId != null || seriesTimerId != null

    fun airsAt(timeMs: Long): Boolean = timeMs in startMs until endMs

    /** How much of it ran at [timeMs], 0–1. */
    fun progressAt(timeMs: Long): Float =
        if (endMs <= startMs) 0f else ((timeMs - startMs).toFloat() / (endMs - startMs)).coerceIn(0f, 1f)
}

enum class ProgramCategory { Movie, Series, News, Sports, Kids }

/** A programme the server recorded, or is recording now. */
data class LiveRecording(
    val id: UUID,
    val title: String,
    val episodeTitle: String?,
    val overview: String?,
    val channelName: String?,
    val startMs: Long,
    val endMs: Long,
    val imageUrl: String?,
    /** Still being recorded. */
    val recording: Boolean,
    /** The timer recording it, to stop it early. */
    val timerId: String? = null,
)

/** A programme the server will record, or records now. */
data class LiveTimer(
    val id: String,
    val programId: String?,
    val title: String,
    val episodeTitle: String?,
    val overview: String?,
    val channelName: String?,
    val startMs: Long,
    val endMs: Long,
    /** Part of a series recording. */
    val seriesTimerId: String?,
    val recording: Boolean,
)

/** A series the server records whenever an episode airs. */
data class LiveSeriesTimer(
    val id: String,
    val title: String,
    val overview: String?,
    /** Null when it records on any channel. */
    val channelName: String?,
    val imageUrl: String?,
)

/** Everything recorded and planned on the server. */
data class LiveRecordings(
    val recorded: List<LiveRecording>,
    val scheduled: List<LiveTimer>,
    val series: List<LiveSeriesTimer>,
) {
    val isEmpty: Boolean get() = recorded.isEmpty() && scheduled.isEmpty() && series.isEmpty()
}

/**
 * The timers still to come or running at [timeMs], soonest first. The server
 * also lists cancelled and failed ones, and ones that finished a moment ago.
 */
fun upcomingTimers(timers: List<LiveTimer>, timeMs: Long): List<LiveTimer> =
    timers.filter { it.endMs > timeMs }.sortedBy { it.startMs }

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

/**
 * [channels] in [sort] order. By number: numbered channels first, a number like "5.1" after "5"
 * and "10" after "9"; channels without a number follow by name. The server puts those first.
 * [descending] turns both round, but channels without a number stay at the end.
 */
fun sortedChannels(channels: List<LiveChannel>, sort: ChannelSort, descending: Boolean = false): List<LiveChannel> {
    fun Comparator<LiveChannel>.directed() = if (descending) reversed() else this
    val byName = compareBy(NameOrder) { channel: LiveChannel -> channel.name }.directed()
    return when (sort) {
        ChannelSort.Number -> channels.sortedWith(
            compareBy<LiveChannel> { it.number == null }
                .then(Comparator<LiveChannel> { a, b -> compareChannelNumbers(a.number.orEmpty(), b.number.orEmpty()) }.directed())
                .then(byName),
        )
        ChannelSort.Name -> channels.sortedWith(byName)
    }
}

/** Part by part as numbers: "2.10" after "2.9". */
private fun compareChannelNumbers(a: String, b: String): Int {
    val left = NUMBER_PARTS.findAll(a).map { it.value.trimStart('0') }.toList()
    val right = NUMBER_PARTS.findAll(b).map { it.value.trimStart('0') }.toList()
    for (i in 0 until minOf(left.size, right.size)) {
        // Without leading zeros, a longer run of digits is the larger number, however long.
        val byValue = compareValuesBy(left[i], right[i], { it.length }, { it })
        if (byValue != 0) return byValue
    }
    return left.size.compareTo(right.size).takeIf { it != 0 } ?: a.compareTo(b)
}

private val NUMBER_PARTS = Regex("\\d+")

/** Live TV through the server: its channels (as the tuners and the guide set them up) and their programmes. */
@Singleton
class LiveTvRepository @Inject constructor(
    private val sessions: SessionManager,
    private val settings: SettingsRepository,
) {

    /** Which channels are radio, as this profile found out or chose, and their order; see [LiveTvSettings]. */
    val radioMarks: Flow<LiveTvSettings> = settings.settings.map { it.liveTv }.distinctUntilChanged()

    /** Orders the channels everywhere: list, guide and switching channels in the player. */
    suspend fun setSort(sort: ChannelSort, descending: Boolean) {
        settings.update { it.copy(liveTv = it.liveTv.copy(sort = sort, descending = descending)) }
    }

    /** Moves [channelId] to the radio stations, or back to the TV channels. */
    suspend fun moveChannel(channelId: UUID, toRadio: Boolean) {
        settings.update { it.copy(liveTv = it.liveTv.moved(channelId.toString(), toRadio)) }
    }

    /** [channelId] played with sound and no picture: from now on it lists as a radio station. */
    suspend fun heardAsRadio(channelId: UUID) {
        if (settings.current().liveTv.isRadio(channelId.toString(), serverSaysRadio = false)) return
        settings.update { it.copy(liveTv = it.liveTv.heardAsRadio(channelId.toString())) }
    }

    private val _lastWatched = MutableStateFlow<WatchedChannel?>(null)

    /** The channel watched last, so the channel list and the guide can land on it again. */
    val lastWatched: StateFlow<WatchedChannel?> = _lastWatched.asStateFlow()

    fun watched(channelId: UUID) {
        _lastWatched.value = WatchedChannel(channelId, System.currentTimeMillis())
    }

    /** All channels in the profile's order (see [sortedChannels]), TV and radio, each with the programme on air now. */
    suspend fun channels(): List<LiveChannel> = withContext(Dispatchers.IO) {
        val session = requireSession()
        val api = session.api
        val marks = settings.current().liveTv
        api.liveTvApi.getLiveTvChannels(
            userId = session.userId,
            enableImages = true,
            imageTypeLimit = 1,
            enableImageTypes = listOf(ImageType.PRIMARY),
            enableUserData = true,
            addCurrentProgram = true,
            fields = listOf(ItemFields.OVERVIEW),
        ).content.items.map { it.toChannel(api, marks) }.let { sortedChannels(it, marks.sort, marks.descending) }
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

    /** Records [programId] with the server's default settings (padding, how long to keep it). */
    suspend fun record(programId: String) = withContext(Dispatchers.IO) {
        val api = requireSession().api.liveTvApi
        val timer = api.getDefaultTimer(programId).content
        api.createTimer(
            TimerInfoDto(
                channelId = timer.channelId,
                externalChannelId = timer.externalChannelId,
                programId = timer.programId,
                externalProgramId = timer.externalProgramId,
                name = timer.name,
                overview = timer.overview,
                startDate = timer.startDate,
                endDate = timer.endDate,
                serviceName = timer.serviceName,
                priority = timer.priority,
                prePaddingSeconds = timer.prePaddingSeconds,
                postPaddingSeconds = timer.postPaddingSeconds,
                isPrePaddingRequired = timer.isPrePaddingRequired,
                isPostPaddingRequired = timer.isPostPaddingRequired,
                keepUntil = timer.keepUntil,
            ),
        )
        Unit
    }

    /** Records every episode of the series [programId] belongs to, with the server's default settings. */
    suspend fun recordSeries(programId: String) = withContext(Dispatchers.IO) {
        val api = requireSession().api.liveTvApi
        api.createSeriesTimer(api.getDefaultTimer(programId).content)
        Unit
    }

    suspend fun cancelTimer(timerId: String) = withContext(Dispatchers.IO) {
        requireSession().api.liveTvApi.cancelTimer(timerId)
        Unit
    }

    suspend fun cancelSeriesTimer(seriesTimerId: String) = withContext(Dispatchers.IO) {
        requireSession().api.liveTvApi.cancelSeriesTimer(seriesTimerId)
        Unit
    }

    suspend fun deleteRecording(id: UUID) = withContext(Dispatchers.IO) {
        requireSession().api.liveTvApi.deleteRecording(id)
        Unit
    }

    /** What the server recorded (newest first), what it will record, and the series it follows. */
    suspend fun recordings(): LiveRecordings = withContext(Dispatchers.IO) {
        val session = requireSession()
        val api = session.api
        coroutineScope {
            val recorded = async {
                api.liveTvApi.getRecordings(
                    userId = session.userId,
                    enableImages = true,
                    imageTypeLimit = 1,
                    enableImageTypes = listOf(ImageType.PRIMARY, ImageType.THUMB),
                    fields = listOf(ItemFields.OVERVIEW, ItemFields.DATE_CREATED),
                    enableUserData = true,
                ).content.items.mapNotNull { it.toRecording(api) }.sortedByDescending { it.startMs }
            }
            val timers = async { api.liveTvApi.getTimers().content.items.mapNotNull { it.toTimer() } }
            val series = async { api.liveTvApi.getSeriesTimers().content.items.map { it.toSeriesTimer(api) } }
            val done = recorded.await()
            // A running timer is already listed as its recording.
            val listed = done.mapNotNull { it.timerId }.toSet()
            LiveRecordings(
                recorded = done,
                scheduled = upcomingTimers(timers.await(), System.currentTimeMillis()).filterNot { it.id in listed },
                series = series.await().sortedBy { it.title.lowercase() },
            )
        }
    }

    /**
     * While it records, the server lists a recording with the programme's
     * times and channel. Once done, the library has it as an episode or a
     * movie: named after the episode, under its series, with only the time
     * the file was made and its length.
     */
    private fun BaseItemDto.toRecording(api: ApiClient): LiveRecording? {
        val startMs = (startDate ?: dateCreated)?.toEpochMs() ?: return null
        val series = seriesName?.takeIf { it.isNotBlank() }
        return LiveRecording(
            id = id,
            title = series ?: name.orEmpty(),
            episodeTitle = (if (series != null) name else episodeTitle)?.takeIf { it.isNotBlank() && it != series },
            overview = plainText(overview)?.takeIf { it.isNotBlank() },
            channelName = channelName?.takeIf { it.isNotBlank() },
            startMs = startMs,
            endMs = endDate?.toEpochMs() ?: (startMs + (runTimeTicks ?: 0L) / TICKS_PER_MS),
            imageUrl = imageOf(api, id, imageTags)
                ?: seriesId?.let { show -> seriesPrimaryImageTag?.let { imageOf(api, show, mapOf(ImageType.PRIMARY to it)) } },
            recording = status.equals("InProgress", ignoreCase = true),
            timerId = timerId?.takeIf { it.isNotBlank() },
        )
    }

    private fun TimerInfoDto.toTimer(): LiveTimer? {
        val id = id ?: return null
        val start = startDate ?: return null
        val end = endDate ?: return null
        if (status == RecordingStatus.CANCELLED || status == RecordingStatus.ERROR) return null
        return LiveTimer(
            id = id,
            programId = programId,
            title = name.orEmpty(),
            episodeTitle = programInfo?.episodeTitle?.takeIf { it.isNotBlank() && it != name },
            overview = plainText(overview ?: programInfo?.overview)?.takeIf { it.isNotBlank() },
            channelName = channelName?.takeIf { it.isNotBlank() },
            startMs = start.toEpochMs(),
            endMs = end.toEpochMs(),
            seriesTimerId = seriesTimerId?.takeIf { it.isNotBlank() },
            recording = status == RecordingStatus.IN_PROGRESS,
        )
    }

    private fun SeriesTimerInfoDto.toSeriesTimer(api: ApiClient) = LiveSeriesTimer(
        id = id.orEmpty(),
        title = name.orEmpty(),
        overview = plainText(overview)?.takeIf { it.isNotBlank() },
        channelName = channelName?.takeIf { it.isNotBlank() && recordAnyChannel != true },
        imageUrl = parentPrimaryImageItemId?.let { item ->
            parentPrimaryImageTag?.let { api.imageApi.getItemImageUrl(itemId = item, imageType = ImageType.PRIMARY, tag = it, maxWidth = PROGRAM_WIDTH) }
        },
    )

    private fun imageOf(api: ApiClient, item: UUID, tags: Map<ImageType, String>?): String? {
        val (type, tag) = tags?.get(ImageType.PRIMARY)?.let { ImageType.PRIMARY to it }
            ?: tags?.get(ImageType.THUMB)?.let { ImageType.THUMB to it }
            ?: return null
        return api.imageApi.getItemImageUrl(itemId = item, imageType = type, tag = tag, maxWidth = PROGRAM_WIDTH)
    }

    private fun BaseItemDto.toChannel(api: ApiClient, marks: LiveTvSettings) = LiveChannel(
        id = id,
        number = channelNumber?.takeIf { it.isNotBlank() },
        name = name.orEmpty(),
        logoUrl = imageTags?.get(ImageType.PRIMARY)?.let {
            api.imageApi.getItemImageUrl(itemId = id, imageType = ImageType.PRIMARY, tag = it, maxWidth = LOGO_WIDTH)
        },
        favorite = userData?.isFavorite == true,
        now = currentProgram?.toProgram(api, channel = id),
        radio = marks.isRadio(id.toString(), serverSaysRadio = channelType == ChannelType.RADIO),
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
            series = isSeries == true,
            timerId = timerId?.takeIf { it.isNotBlank() },
            seriesTimerId = seriesTimerId?.takeIf { it.isNotBlank() },
        )
    }

    private fun requireSession(): Session = checkNotNull(sessions.session.value) { "No profile is signed in" }

    private val Session.userId: UUID get() = UUID.fromString(user.userId)

    private companion object {
        const val LOGO_WIDTH = 320
        const val PROGRAM_WIDTH = 720
        const val TICKS_PER_MS = 10_000L
    }
}

/** The SDK reads server times into the device's time zone. */
private fun LocalDateTime.toEpochMs(): Long = atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

private fun Long.toLocalDateTime(): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(this), ZoneId.systemDefault())

/**
 * A channel tuned to at [atMs]. Each time counts on its own: back on the same
 * channel as the trip before, the list still lands on it, whatever was
 * focused in between.
 */
data class WatchedChannel(val channelId: UUID, val atMs: Long) {
    /** Tells this time apart from every other one. */
    val key: String get() = "$channelId@$atMs"
}
