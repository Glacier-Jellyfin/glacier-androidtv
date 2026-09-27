package io.github.glacier_jellyfin.androidtv.core.data.media

import io.github.glacier_jellyfin.androidtv.core.data.Session
import io.github.glacier_jellyfin.androidtv.core.data.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.showApi
import org.jellyfin.sdk.api.client.extensions.userDataApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.PersonKind
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DetailRepository @Inject constructor(
    private val sessions: SessionManager,
) {

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
                remote = dto.remoteTrailers.orEmpty().mapNotNull { it.url },
            ),
            seriesId = dto.seriesId,
            seasonId = dto.seasonId,
            premiereDate = dto.premiereDate?.toLocalDate(),
            seasonCount = dto.childCount.takeIf { dto.type == BaseItemKind.SERIES },
            episodeCount = dto.recursiveItemCount.takeIf { dto.type == BaseItemKind.SERIES },
        )
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
        ).content.items.map(mapper::item)
    }

    /** The episode to play for a show: in progress, else the next unwatched one. */
    suspend fun nextEpisode(seriesId: UUID): MediaItem? = withContext(Dispatchers.IO) {
        val session = requireSession()
        session.api.showApi.getNextUp(userId = session.userId, seriesId = seriesId, limit = 1, enableUserData = true)
            .content.items.firstOrNull()?.let(MediaMapper(session.api)::item)
    }

    suspend fun similar(id: UUID): List<MediaItem> = withContext(Dispatchers.IO) {
        val session = requireSession()
        val mapper = MediaMapper(session.api)
        session.api.libraryApi.getSimilarItems(itemId = id, userId = session.userId, limit = SIMILAR_LIMIT)
            .content.items.map(mapper::item)
    }

    suspend fun setPlayed(id: UUID, played: Boolean) {
        val api = requireSession().api.userDataApi
        withContext(Dispatchers.IO) {
            if (played) api.markPlayedItem(itemId = id) else api.markUnplayedItem(itemId = id)
        }
    }

    suspend fun setFavorite(id: UUID, favorite: Boolean) {
        val api = requireSession().api.userDataApi
        withContext(Dispatchers.IO) {
            if (favorite) api.markFavoriteItem(itemId = id) else api.unmarkFavoriteItem(itemId = id)
        }
    }

    private fun cast(session: Session, dto: BaseItemDto): List<CastMember> =
        dto.people.orEmpty()
            .filter { it.type == PersonKind.ACTOR || it.type == PersonKind.GUEST_STAR }
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
        fun MediaStream.toTrack() = Track(
            index = index,
            language = language,
            codec = codec,
            channels = channels,
            forced = isForced,
            hearingImpaired = isHearingImpaired,
            fallbackTitle = displayTitle ?: title,
        )
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
        const val SIMILAR_LIMIT = 12
        const val CAST_LIMIT = 20
    }
}
