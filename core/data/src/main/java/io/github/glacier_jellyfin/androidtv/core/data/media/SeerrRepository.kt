package io.github.glacier_jellyfin.androidtv.core.data.media

import io.github.glacier_jellyfin.androidtv.core.data.Session
import io.github.glacier_jellyfin.androidtv.core.data.SessionManager
import io.github.glacier_jellyfin.androidtv.core.log.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jellyfin.sdk.api.client.HttpMethod
import org.jellyfin.sdk.api.client.util.AuthorizationHeaderBuilder
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

enum class SeerrMediaType(val path: String) { Movie("movie"), Tv("tv") }

/** Seerr's media status (MediaStatus in its API, 1 to 7). */
enum class SeerrStatus { Unknown, Pending, Processing, PartiallyAvailable, Available, Blocklisted, Deleted }

/** A title found through Seerr. */
data class SeerrItem(
    val tmdbId: Int,
    val type: SeerrMediaType,
    val title: String,
    val year: Int?,
    val overview: String?,
    val posterUrl: String?,
    val backdropUrl: String?,
    val status: SeerrStatus,
    /** The title in the Jellyfin library, when Seerr knows it is there. */
    val jellyfinId: UUID?,
)

data class SeerrSeason(
    val number: Int,
    val episodeCount: Int?,
    val status: SeerrStatus,
    /** Already asked for (an open or approved request), so it cannot be requested again. */
    val requested: Boolean,
) {
    val requestable: Boolean get() = !requested && status in Requestable
}

/** A cast member from TMDB, known to Seerr but not necessarily to Jellyfin. */
data class SeerrPerson(val name: String, val role: String?, val imageUrl: String?)

data class SeerrDetails(
    val item: SeerrItem,
    val rating: Float?,
    val runtimeMinutes: Int?,
    val genres: List<String>,
    /** Seasons of a show, without specials; empty for movies. */
    val seasons: List<SeerrSeason>,
    val cast: List<SeerrPerson> = emptyList(),
    val trailers: List<YouTubeTrailer> = emptyList(),
) {
    /** A movie that can be asked for, or a show with at least one season left to ask for. */
    val requestable: Boolean
        get() = when (item.type) {
            SeerrMediaType.Movie -> item.status in Requestable
            SeerrMediaType.Tv -> seasons.any { it.requestable }
        }
}

enum class SeerrRequestResult { Sent, NotAllowed, QuotaUsedUp, AlreadyRequested, Failed }

/** A request limit set in Seerr: [remaining] of [limit] requests within [days]. */
data class SeerrQuota(val limit: Int, val remaining: Int, val days: Int)

private val Requestable = setOf(SeerrStatus.Unknown, SeerrStatus.Deleted)

/**
 * Seerr through the Jellyfin Enhanced plugin: the plugin holds the Seerr key
 * and maps the signed-in Jellyfin user to their Seerr user, so Glacier only
 * needs the Jellyfin session. Every call fails soft; without the plugin, or
 * for a user Seerr does not know, there simply are no Seerr results.
 */
@Singleton
class SeerrRepository @Inject constructor(
    private val sessions: SessionManager,
) {
    private val mutex = Mutex()
    private var linkedFor: String? = null
    private var linked = false
    private var checkedAt = 0L

    /**
     * Whether Seerr can be used by the signed-in user. A yes is kept for the
     * profile; a no is asked again after a while, as the admin may link the user meanwhile.
     */
    suspend fun available(): Boolean = withContext(Dispatchers.IO) {
        val session = sessions.session.value ?: return@withContext false
        val key = "${session.server.id}/${session.user.userId}"
        mutex.withLock {
            if (linkedFor == key && (linked || System.currentTimeMillis() - checkedAt < RECHECK_MS)) return@withLock linked
            val status = runCatching { session.get<UserStatusDto>("/user-status") }
                .onFailure { if (it is CancellationException) throw it }
                .getOrNull()
            // Only answers are kept: a server that could not be reached is asked again next time.
            if (status != null) {
                linked = status.active && status.userFound
                linkedFor = key
                checkedAt = System.currentTimeMillis()
                Log.i(TAG, "Seerr via Jellyfin Enhanced: ${status.reason ?: if (linked) "linked" else "inactive"}")
            }
            status?.let { it.active && it.userFound } ?: false
        }
    }

    /** Movies and shows matching [query], in Seerr's order; empty when Seerr cannot be used. */
    suspend fun search(query: String): List<SeerrItem> = withContext(Dispatchers.IO) {
        val term = query.trim()
        if (term.isEmpty() || !available()) return@withContext emptyList()
        val session = requireSession()
        session.get<SearchDto>("/search", mapOf("query" to term, "page" to 1, "language" to language()))
            .results.mapNotNull { it.toItem() }
    }

    suspend fun details(type: SeerrMediaType, tmdbId: Int): SeerrDetails = withContext(Dispatchers.IO) {
        val session = requireSession()
        val language = language()
        var dto = session.get<DetailsDto>("/${type.path}/$tmdbId", mapOf("language" to language))
        // TMDB leaves the plot empty without a translation and lists only trailers in the
        // asked language; English fills the gaps.
        if (language != FALLBACK_LANGUAGE && (dto.overview.isNullOrBlank() || dto.youTubeTrailers().isEmpty())) {
            val english = runCatching { session.get<DetailsDto>("/${type.path}/$tmdbId", mapOf("language" to FALLBACK_LANGUAGE)) }
                .onFailure { if (it is CancellationException) throw it }
                .getOrNull()
            if (english != null) {
                dto = dto.copy(
                    overview = dto.overview?.takeIf { it.isNotBlank() } ?: english.overview,
                    relatedVideos = dto.relatedVideos.ifEmpty { english.relatedVideos },
                )
            }
        }
        val item = dto.copy(mediaType = type.path).toItem() ?: error("Seerr returned no $type $tmdbId")
        val requestedSeasons = dto.mediaInfo?.requests.orEmpty()
            .filter { it.status in OpenRequestStatuses }
            .flatMap { request -> request.seasons.map { it.seasonNumber } }
            .toSet()
        val seasonStatus = dto.mediaInfo?.seasons.orEmpty().associate { it.seasonNumber to status(it.status) }
        SeerrDetails(
            item = item,
            rating = dto.voteAverage?.takeIf { it > 0 },
            runtimeMinutes = dto.runtime ?: dto.episodeRunTime.firstOrNull(),
            genres = dto.genres.map { it.name },
            seasons = dto.seasons
                .filter { it.seasonNumber > 0 }
                .map { season ->
                    SeerrSeason(
                        number = season.seasonNumber,
                        episodeCount = season.episodeCount,
                        status = seasonStatus[season.seasonNumber] ?: SeerrStatus.Unknown,
                        requested = season.seasonNumber in requestedSeasons,
                    )
                },
            cast = dto.credits?.cast.orEmpty().take(CAST_LIMIT).map { person ->
                SeerrPerson(
                    name = person.name,
                    role = person.character?.takeIf { it.isNotBlank() },
                    imageUrl = person.profilePath?.let { "$TMDB_IMAGES/w185$it" },
                )
            },
            trailers = dto.youTubeTrailers(),
        )
    }

    /** The user's request limit for [type]; null when there is none or it cannot be read. */
    suspend fun quota(type: SeerrMediaType): SeerrQuota? = withContext(Dispatchers.IO) {
        val quota = runCatching { requireSession().get<QuotaDto>("/quota") }
            .onFailure { if (it is CancellationException) throw it }
            .getOrNull() ?: return@withContext null
        val entry = if (type == SeerrMediaType.Movie) quota.movie else quota.tv
        entry?.takeIf { (it.limit ?: 0) > 0 }?.let { SeerrQuota(it.limit ?: 0, it.remaining ?: 0, it.days ?: 0) }
    }

    /**
     * Asks for a movie, or for the given [seasons] of a show. Sent without the
     * SDK client, which drops the body of an error answer: the reason for a
     * refusal (no permission, quota used up) is only in there.
     */
    suspend fun request(type: SeerrMediaType, tmdbId: Int, seasons: List<Int> = emptyList()): SeerrRequestResult =
        withContext(Dispatchers.IO) {
            val session = requireSession()
            val body = json.encodeToString(
                RequestDto.serializer(),
                RequestDto(mediaType = type.path, mediaId = tmdbId, seasons = seasons.takeIf { type == SeerrMediaType.Tv }),
            )
            val connection = URL(session.api.createUrl("$BASE/request")).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.connectTimeout = TIMEOUT_MS
                connection.readTimeout = TIMEOUT_MS
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("Authorization", authorization(session))
                connection.outputStream.use { it.write(body.toByteArray()) }
                val status = connection.responseCode
                if (status in 200..299) return@withContext SeerrRequestResult.Sent
                val answer = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                Log.w(TAG, "Seerr request for $type $tmdbId answered $status: ${answer.take(300)}")
                requestFailure(status, answer)
            } finally {
                connection.disconnect()
            }
        }

    private fun authorization(session: Session): String = AuthorizationHeaderBuilder.buildHeader(
        clientName = session.api.clientInfo.name,
        clientVersion = session.api.clientInfo.version,
        deviceId = session.api.deviceInfo.id,
        deviceName = session.api.deviceInfo.name,
        accessToken = session.api.accessToken,
    )

    private suspend inline fun <reified T> Session.get(path: String, query: Map<String, Any?> = emptyMap()): T {
        val response = api.request(HttpMethod.GET, "$BASE$path", emptyMap(), query, null)
        return json.decodeFromString(response.body.decodeToString())
    }

    /** Trailers first, then teasers, as TMDB lists them. */
    private fun DetailsDto.youTubeTrailers(): List<YouTubeTrailer> =
        relatedVideos
            .filter { it.site.equals("YouTube", ignoreCase = true) && it.type in TrailerTypes && !it.key.isNullOrBlank() }
            .sortedBy { TrailerTypes.indexOf(it.type) }
            .map { YouTubeTrailer(it.key!!, it.name) }

    private fun requireSession(): Session = checkNotNull(sessions.session.value) { "No profile is signed in" }

    /** Titles and overviews in the interface language, as far as TMDB has them. */
    private fun language() = Locale.getDefault().language

    private fun ResultDto.toItem(): SeerrItem? {
        val type = SeerrMediaType.entries.firstOrNull { it.path == mediaType } ?: return null
        val name = title ?: name ?: return null
        return SeerrItem(
            tmdbId = id,
            type = type,
            title = name,
            year = (releaseDate ?: firstAirDate)?.take(4)?.toIntOrNull(),
            overview = overview?.takeIf { it.isNotBlank() },
            posterUrl = posterPath?.let { "$TMDB_IMAGES/w342$it" },
            backdropUrl = backdropPath?.let { "$TMDB_IMAGES/w1280$it" },
            status = status(mediaInfo?.status),
            jellyfinId = mediaInfo?.jellyfinMediaId?.let(::parseJellyfinId),
        )
    }

    private fun DetailsDto.toItem(): SeerrItem? =
        ResultDto(id, mediaType, title, name, releaseDate, firstAirDate, overview, posterPath, backdropPath, mediaInfo).toItem()

    private companion object {
        const val TAG = "Seerr"
        const val BASE = "/JellyfinEnhanced/jellyseerr"
        const val TMDB_IMAGES = "https://image.tmdb.org/t/p"
        const val RECHECK_MS = 5 * 60_000L
        const val CAST_LIMIT = 20
        const val TIMEOUT_MS = 15_000
        const val FALLBACK_LANGUAGE = "en"
        val TrailerTypes = listOf("Trailer", "Teaser")
        /** Pending and approved; declined requests can be asked again. */
        val OpenRequestStatuses = setOf(1, 2)

        val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            coerceInputValues = true
        }

        fun status(value: Int?): SeerrStatus = SeerrStatus.entries.getOrNull((value ?: 1) - 1) ?: SeerrStatus.Unknown
    }
}

/**
 * Why a request was refused, from Jellyfin Enhanced's `code` ("no_request_permission")
 * or Seerr's `message` ("Movie Quota exceeded.", "Request for this media already exists.").
 */
internal fun requestFailure(status: Int, answer: String): SeerrRequestResult {
    val text = answer.lowercase()
    return when {
        "quota" in text -> SeerrRequestResult.QuotaUsedUp
        status == 409 || "already" in text -> SeerrRequestResult.AlreadyRequested
        status == 403 -> SeerrRequestResult.NotAllowed
        else -> SeerrRequestResult.Failed
    }
}

/** Seerr stores Jellyfin ids without dashes. */
internal fun parseJellyfinId(raw: String): UUID? {
    val hex = raw.replace("-", "")
    if (hex.length != 32) return null
    return runCatching { UUID.fromString("${hex.take(8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}") }.getOrNull()
}

@Serializable
private data class UserStatusDto(val active: Boolean = false, val userFound: Boolean = false, val reason: String? = null)

@Serializable
private data class QuotaDto(val movie: QuotaEntryDto? = null, val tv: QuotaEntryDto? = null)

@Serializable
private data class QuotaEntryDto(val limit: Int? = null, val remaining: Int? = null, val days: Int? = null)

@Serializable
private data class SearchDto(val results: List<ResultDto> = emptyList())

@Serializable
private data class ResultDto(
    val id: Int,
    val mediaType: String? = null,
    val title: String? = null,
    val name: String? = null,
    val releaseDate: String? = null,
    val firstAirDate: String? = null,
    val overview: String? = null,
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val mediaInfo: MediaInfoDto? = null,
)

@Serializable
private data class DetailsDto(
    val id: Int,
    val mediaType: String? = null,
    val title: String? = null,
    val name: String? = null,
    val releaseDate: String? = null,
    val firstAirDate: String? = null,
    val overview: String? = null,
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val voteAverage: Float? = null,
    val runtime: Int? = null,
    val episodeRunTime: List<Int> = emptyList(),
    val genres: List<GenreDto> = emptyList(),
    val seasons: List<SeasonDto> = emptyList(),
    val credits: CreditsDto? = null,
    val relatedVideos: List<VideoDto> = emptyList(),
    val mediaInfo: MediaInfoDto? = null,
)

@Serializable
private data class VideoDto(val key: String? = null, val name: String? = null, val site: String? = null, val type: String? = null)

@Serializable
private data class CreditsDto(val cast: List<CastDto> = emptyList())

@Serializable
private data class CastDto(val name: String, val character: String? = null, val profilePath: String? = null)

@Serializable
private data class GenreDto(val name: String)

@Serializable
private data class SeasonDto(val seasonNumber: Int, val episodeCount: Int? = null)

@Serializable
private data class MediaInfoDto(
    val status: Int? = null,
    val jellyfinMediaId: String? = null,
    val seasons: List<SeasonStatusDto> = emptyList(),
    val requests: List<MediaRequestDto> = emptyList(),
)

@Serializable
private data class SeasonStatusDto(val seasonNumber: Int, val status: Int? = null)

@Serializable
private data class MediaRequestDto(val status: Int? = null, val seasons: List<SeasonStatusDto> = emptyList())

@Serializable
private data class RequestDto(
    val mediaType: String,
    val mediaId: Int,
    val seasons: List<Int>? = null,
)
