package io.github.glacier_jellyfin.androidtv.core.data.media

import io.github.glacier_jellyfin.androidtv.core.data.Session
import io.github.glacier_jellyfin.androidtv.core.data.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.personApi
import org.jellyfin.sdk.api.client.extensions.playlistApi
import org.jellyfin.sdk.api.client.extensions.showApi
import org.jellyfin.sdk.api.client.extensions.trickPlayApi
import org.jellyfin.sdk.api.client.extensions.userDataApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemPerson
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.PersonKind
import org.jellyfin.sdk.model.api.SortOrder
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DetailRepository @Inject constructor(
    private val sessions: SessionManager,
    private val ageFilter: AgeFilter,
) {

    private val _playedChanged = MutableSharedFlow<UUID>(extraBufferCapacity = 8)

    /** A title the user marked watched or unwatched: lists showing its state or progress are stale. */
    val playedChanged: SharedFlow<UUID> = _playedChanged.asSharedFlow()

    suspend fun details(id: UUID): ItemDetails = withContext(Dispatchers.IO) {
        val session = requireSession()
        val dto = session.api.libraryApi.getItem(itemId = id, userId = session.userId).content
        val mapper = MediaMapper(session.api)
        ItemDetails(
            item = mapper.item(dto),
            cast = cast(session, dto),
            tracks = tracks(dto),
            trailers = Trailers(
                localCount = dto.localTrailerCount ?: 0,
                youTube = dto.remoteTrailers.orEmpty()
                    .mapNotNull { link -> link.url?.let(::youTubeId)?.let { YouTubeTrailer(it, link.name?.takeIf(String::isNotBlank)) } }
                    // Metadata providers often list the same video twice.
                    .distinctBy { it.videoId },
            ),
            seriesId = dto.seriesId,
            seasonId = dto.seasonId,
            premiereDate = dto.premiereDate?.toLocalDate(),
            seasonCount = dto.childCount.takeIf { dto.type == BaseItemKind.SERIES },
            episodeCount = dto.recursiveItemCount.takeIf { dto.type == BaseItemKind.SERIES },
            chapters = chapters(session, dto),
            trickplay = trickplay(session, dto),
            tagline = dto.taglines?.firstOrNull { it.isNotBlank() }?.trim(),
            directors = dto.people.orEmpty()
                .filter { it.type == PersonKind.DIRECTOR }
                .mapNotNull { it.name?.takeIf(String::isNotBlank) }
                .distinct(),
            studios = dto.studios.orEmpty().mapNotNull { it.name?.takeIf(String::isNotBlank) },
        )
    }

    private fun chapters(session: Session, dto: BaseItemDto): List<Chapter> =
        dto.chapters.orEmpty().mapIndexed { index, chapter ->
            Chapter(
                name = chapter.name.orEmpty(),
                startMs = chapter.startPositionTicks / TICKS_PER_MS,
                imageUrl = chapter.imageTag?.let { tag ->
                    session.api.imageApi.getItemImageUrl(itemId = dto.id, imageType = ImageType.CHAPTER, imageIndex = index, tag = tag, maxWidth = 600)
                },
            )
        }

    /** The resolution closest to the design's 340-wide preview. */
    private fun trickplay(session: Session, dto: BaseItemDto): Trickplay? {
        val source = dto.mediaSources?.firstOrNull()?.id ?: dto.id.toString().replace("-", "")
        val sizes = dto.trickplay?.get(source) ?: dto.trickplay?.values?.firstOrNull() ?: return null
        val info = sizes.values.minByOrNull { kotlin.math.abs(it.width - TRICKPLAY_WIDTH) } ?: return null
        val perTile = (info.tileWidth * info.tileHeight).coerceAtLeast(1)
        val tiles = (info.thumbnailCount + perTile - 1) / perTile
        val sourceId = runCatching { UUID.fromString(source.replaceFirst(UUID_PARTS, "$1-$2-$3-$4-$5")) }.getOrNull()
        return Trickplay(
            width = info.width,
            height = info.height,
            columns = info.tileWidth,
            rows = info.tileHeight,
            count = info.thumbnailCount,
            intervalMs = info.interval.toLong(),
            tileUrls = (0 until tiles).map { index ->
                session.api.trickPlayApi.getTrickplayTileImageUrl(itemId = dto.id, width = info.width, index = index, mediaSourceId = sourceId)
            },
        )
    }

    /** Trailer files of [id], in the server's order. */
    suspend fun localTrailers(id: UUID): List<LocalTrailer> = withContext(Dispatchers.IO) {
        val session = requireSession()
        session.api.libraryApi.getLocalTrailers(itemId = id, userId = session.userId).content.map { dto ->
            LocalTrailer(
                id = dto.id,
                name = dto.name?.takeIf { it.isNotBlank() },
                durationMs = dto.runTimeTicks?.let { it / TICKS_PER_MS },
                imageUrl = dto.imageTags?.get(ImageType.PRIMARY)?.let { tag ->
                    session.api.imageApi.getItemImageUrl(itemId = dto.id, imageType = ImageType.PRIMARY, tag = tag, maxWidth = 600)
                },
            )
        }
    }

    suspend fun seasons(seriesId: UUID): List<Season> = withContext(Dispatchers.IO) {
        val session = requireSession()
        session.api.showApi.getSeasons(seriesId = seriesId, userId = session.userId, enableUserData = true).content.items.map {
            Season(
                id = it.id,
                number = it.indexNumber,
                name = it.name.orEmpty(),
                unwatchedCount = it.userData?.unplayedItemCount,
            )
        }
    }

    suspend fun episodes(seriesId: UUID, seasonId: UUID): List<MediaItem> = withContext(Dispatchers.IO) {
        val session = requireSession()
        val mapper = MediaMapper(session.api)
        session.api.showApi.getEpisodes(
            seriesId = seriesId,
            userId = session.userId,
            seasonId = seasonId,
            fields = listOf(ItemFields.OVERVIEW, ItemFields.MEDIA_STREAMS),
            enableUserData = true,
        ).content.items.map(mapper::item).let { ageFilter.screen(it) }
    }

    /** The episode to play for a show: in progress, else the next unwatched one. */
    suspend fun nextEpisode(seriesId: UUID): MediaItem? = withContext(Dispatchers.IO) {
        val session = requireSession()
        session.api.showApi.getNextUp(userId = session.userId, seriesId = seriesId, limit = 1, enableUserData = true)
            .content.items.firstOrNull()?.let(MediaMapper(session.api)::item)
    }

    /** The episodes before and after [episodeId] in the whole show, across seasons. */
    suspend fun neighbours(seriesId: UUID, episodeId: UUID): EpisodeNeighbours = withContext(Dispatchers.IO) {
        val session = requireSession()
        val mapper = MediaMapper(session.api)
        val items = session.api.showApi.getEpisodes(
            seriesId = seriesId,
            userId = session.userId,
            adjacentTo = episodeId,
            enableUserData = true,
        ).content.items
        val index = items.indexOfFirst { it.id == episodeId }
        if (index < 0) return@withContext EpisodeNeighbours(null, null)
        EpisodeNeighbours(
            previous = items.getOrNull(index - 1)?.let(mapper::item),
            next = items.getOrNull(index + 1)?.let(mapper::item),
        )
    }

    /** An artist's music videos, newest first. */
    suspend fun artistVideos(artistId: UUID): List<MediaItem> = withContext(Dispatchers.IO) {
        val session = requireSession()
        val mapper = MediaMapper(session.api)
        session.api.libraryApi.getItems(
            userId = session.userId,
            artistIds = listOf(artistId),
            includeItemTypes = listOf(BaseItemKind.MUSIC_VIDEO),
            recursive = true,
            enableUserData = true,
            sortBy = listOf(ItemSortBy.PRODUCTION_YEAR, ItemSortBy.SORT_NAME),
            sortOrder = listOf(SortOrder.DESCENDING, SortOrder.ASCENDING),
        ).content.items.map(mapper::item)
    }

    /** A playlist's videos in its order; songs are left out. */
    suspend fun playlistVideos(playlistId: UUID): List<MediaItem> = withContext(Dispatchers.IO) {
        val session = requireSession()
        val mapper = MediaMapper(session.api)
        session.api.playlistApi.getPlaylistItems(playlistId = playlistId, userId = session.userId, enableUserData = true)
            .content.items
            .filter { it.mediaType == MediaType.VIDEO }
            .map(mapper::item)
            .let { ageFilter.screen(it) }
    }

    /** What the video player plays one after another: a playlist's videos, or an artist's music videos. */
    suspend fun videoQueue(sourceId: UUID): List<MediaItem> =
        if (details(sourceId).item.kind == ItemKind.Artist) artistVideos(sourceId) else playlistVideos(sourceId)

    /** [id]'s neighbours in [queue] (see [videoQueue]). */
    fun neighboursIn(queue: List<MediaItem>, id: UUID): EpisodeNeighbours {
        val index = queue.indexOfFirst { it.id == id }
        if (index < 0) return EpisodeNeighbours(null, null)
        return EpisodeNeighbours(queue.getOrNull(index - 1), queue.getOrNull(index + 1))
    }

    suspend fun similar(id: UUID): List<MediaItem> = withContext(Dispatchers.IO) {
        val session = requireSession()
        val mapper = MediaMapper(session.api)
        session.api.libraryApi.getSimilarItems(itemId = id, userId = session.userId, limit = SIMILAR_LIMIT)
            .content.items.map(mapper::item).let { ageFilter.screen(it) }
    }

    /** Movies of a Jellyfin collection in release order (design: "chronological"). */
    suspend fun collectionItems(id: UUID): List<MediaItem> = withContext(Dispatchers.IO) {
        val session = requireSession()
        val mapper = MediaMapper(session.api)
        session.api.libraryApi.getItems(
            userId = session.userId,
            parentId = id,
            fields = listOf(ItemFields.GENRES, ItemFields.OVERVIEW),
            enableUserData = true,
            sortBy = listOf(ItemSortBy.PRODUCTION_YEAR, ItemSortBy.PREMIERE_DATE, ItemSortBy.SORT_NAME),
            sortOrder = listOf(SortOrder.ASCENDING),
        ).content.items.map(mapper::item).let { ageFilter.screen(it) }
    }

    suspend fun person(id: UUID): PersonDetails = withContext(Dispatchers.IO) {
        val session = requireSession()
        val mapper = MediaMapper(session.api)
        val dto = session.api.libraryApi.getItem(itemId = id, userId = session.userId).content
        val credits = session.api.libraryApi.getItems(
            userId = session.userId,
            personIds = listOf(id),
            recursive = true,
            includeItemTypes = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES),
            fields = listOf(ItemFields.PEOPLE, ItemFields.GENRES),
            enableUserData = true,
            sortBy = listOf(ItemSortBy.PRODUCTION_YEAR, ItemSortBy.SORT_NAME),
            sortOrder = listOf(SortOrder.DESCENDING),
        ).content.items.map { item ->
            Credit(mapper.item(item), item.people?.firstOrNull { it.id == id }?.role?.takeIf { it.isNotBlank() })
        }.let { credits ->
            val kept = ageFilter.screen(credits.map { it.item }).associateBy { it.id }
            credits.mapNotNull { credit -> kept[credit.item.id]?.let { credit.copy(item = it) } }
        }
        PersonDetails(
            id = dto.id,
            name = dto.name.orEmpty(),
            biography = plainText(dto.overview),
            born = dto.premiereDate?.toLocalDate(),
            birthplace = dto.productionLocations?.firstOrNull(),
            imageUrl = dto.imageTags?.get(ImageType.PRIMARY)?.let { tag ->
                session.api.imageApi.getItemImageUrl(itemId = dto.id, imageType = ImageType.PRIMARY, tag = tag, maxWidth = 600)
            },
            isFavorite = dto.userData?.isFavorite ?: false,
            credits = credits,
            tmdbId = dto.providerIds?.get("Tmdb")?.toIntOrNull(),
        )
    }

    /**
     * The library's person for a TMDB cast member: the one carrying that TMDB id,
     * else the only one with exactly that name. Null when the library has no match.
     */
    suspend fun findPerson(tmdbId: Int, name: String): UUID? = withContext(Dispatchers.IO) {
        if (name.isBlank()) return@withContext null
        val session = requireSession()
        val people = session.api.personApi.getPersons(
            searchTerm = name,
            userId = session.userId,
            fields = listOf(ItemFields.PROVIDER_IDS),
            limit = PERSON_MATCH_LIMIT,
        ).content.items
        people.firstOrNull { it.providerIds?.get("Tmdb")?.toIntOrNull() == tmdbId }?.id
            ?: people.filter { it.name.equals(name, ignoreCase = true) }.singleOrNull()?.id
    }

    suspend fun setPlayed(id: UUID, played: Boolean) {
        val api = requireSession().api.userDataApi
        withContext(Dispatchers.IO) {
            if (played) api.markPlayedItem(itemId = id) else api.markUnplayedItem(itemId = id)
        }
        _playedChanged.tryEmit(id)
    }

    suspend fun setFavorite(id: UUID, favorite: Boolean) {
        val api = requireSession().api.userDataApi
        withContext(Dispatchers.IO) {
            if (favorite) api.markFavoriteItem(itemId = id) else api.unmarkFavoriteItem(itemId = id)
        }
    }

    private fun cast(session: Session, dto: BaseItemDto): List<CastMember> =
        actingPeople(dto.people.orEmpty())
            .take(CAST_LIMIT)
            .map { person ->
                CastMember(
                    id = person.id,
                    name = person.name.orEmpty(),
                    role = person.role?.takeIf { it.isNotBlank() },
                    imageUrl = person.primaryImageTag?.let { tag ->
                        session.api.imageApi.getItemImageUrl(itemId = person.id, imageType = ImageType.PRIMARY, tag = tag, maxWidth = 280)
                    },
                )
            }

    private fun tracks(dto: BaseItemDto): TrackChoices? {
        val source = dto.mediaSources?.firstOrNull() ?: return null
        val streams = source.mediaStreams ?: dto.mediaStreams ?: return null
        val audio = streams.filter { it.type == MediaStreamType.AUDIO }.map { it.toTrack() }
        val subtitles = streams.filter { it.type == MediaStreamType.SUBTITLE }.map { it.toTrack() }
        if (audio.isEmpty() && subtitles.isEmpty()) return null
        return TrackChoices(
            audio = audio,
            subtitles = subtitles,
            defaultAudio = source.defaultAudioStreamIndex ?: audio.firstOrNull()?.index,
            // The server uses -1 for "no subtitles".
            defaultSubtitle = source.defaultSubtitleStreamIndex?.takeIf { it >= 0 },
        )
    }

    private fun requireSession(): Session = checkNotNull(sessions.session.value) { "No profile is signed in" }

    private val Session.userId: UUID get() = UUID.fromString(user.userId)

    private companion object {
        const val TICKS_PER_MS = 10_000L
        const val TRICKPLAY_WIDTH = 320
        val UUID_PARTS = Regex("^(.{8})(.{4})(.{4})(.{4})(.{12})$")
        const val SIMILAR_LIMIT = 12
        const val CAST_LIMIT = 20
        const val PERSON_MATCH_LIMIT = 20
    }
}

/**
 * Actors and guest stars, one entry per person: someone with several roles is listed
 * once, roles joined. The cast row keys its cards by person.
 */
internal fun actingPeople(people: List<BaseItemPerson>): List<BaseItemPerson> =
    people
        .filter { it.type == PersonKind.ACTOR || it.type == PersonKind.GUEST_STAR }
        .groupBy { it.id }
        .map { (_, entries) ->
            val roles = entries.mapNotNull { it.role?.takeIf(String::isNotBlank) }.distinct()
            entries.first().copy(role = roles.joinToString(" / ").takeIf { it.isNotEmpty() })
        }
