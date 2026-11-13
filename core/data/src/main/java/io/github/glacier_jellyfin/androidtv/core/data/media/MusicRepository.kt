package io.github.glacier_jellyfin.androidtv.core.data.media

import io.github.glacier_jellyfin.androidtv.core.data.Session
import io.github.glacier_jellyfin.androidtv.core.data.SessionManager
import io.github.glacier_jellyfin.androidtv.core.log.Log
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.api.client.extensions.instantMixApi
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.lyricApi
import org.jellyfin.sdk.api.client.extensions.playlistApi
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CreatePlaylistDto
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.SortOrder

/** Songs of albums, artists and playlists, and their lyrics. */
@Singleton
class MusicRepository @Inject constructor(private val sessions: SessionManager) {

    /** In disc and track order. */
    suspend fun albumTracks(albumId: UUID): List<MusicTrack> = withContext(Dispatchers.IO) {
        val session = requireSession()
        val mapper = MediaMapper(session.api)
        session.api.libraryApi.getItems(
            userId = session.userId,
            parentId = albumId,
            includeItemTypes = listOf(BaseItemKind.AUDIO),
            recursive = true,
            fields = TRACK_FIELDS,
            enableUserData = true,
            sortBy = listOf(ItemSortBy.PARENT_INDEX_NUMBER, ItemSortBy.INDEX_NUMBER, ItemSortBy.SORT_NAME),
        ).content.items.map(mapper::track)
    }

    /** Albums by [artistId], newest first. */
    suspend fun artistAlbums(artistId: UUID): List<MediaItem> = withContext(Dispatchers.IO) {
        val session = requireSession()
        val mapper = MediaMapper(session.api)
        session.api.libraryApi.getItems(
            userId = session.userId,
            albumArtistIds = listOf(artistId),
            includeItemTypes = listOf(BaseItemKind.MUSIC_ALBUM),
            recursive = true,
            fields = listOf(ItemFields.CHILD_COUNT, ItemFields.GENRES),
            enableUserData = true,
            sortBy = listOf(ItemSortBy.PRODUCTION_YEAR, ItemSortBy.PREMIERE_DATE, ItemSortBy.SORT_NAME),
            sortOrder = listOf(SortOrder.DESCENDING, SortOrder.DESCENDING, SortOrder.ASCENDING),
        ).content.items.map(mapper::item)
    }

    /** Every song of [albums], album after album: an artist's discography. */
    suspend fun tracksOf(albums: List<MediaItem>): List<MusicTrack> = coroutineScope {
        albums.map { album -> async { albumTracks(album.id) } }.awaitAll().flatten()
    }

    /**
     * Every song of the artist: the discography of [albums] (fetched when null), else
     * the songs tagged with the artist. Albums without an album artist leave the first empty.
     */
    suspend fun artistTracks(artistId: UUID, albums: List<MediaItem>? = null): List<MusicTrack> =
        tracksOf(albums ?: artistAlbums(artistId)).ifEmpty { songsBy(artistId) }

    /** Songs with [artistId] among their artists, newest album first, in disc and track order. */
    private suspend fun songsBy(artistId: UUID): List<MusicTrack> = withContext(Dispatchers.IO) {
        val session = requireSession()
        val mapper = MediaMapper(session.api)
        session.api.libraryApi.getItems(
            userId = session.userId,
            artistIds = listOf(artistId),
            includeItemTypes = listOf(BaseItemKind.AUDIO),
            recursive = true,
            fields = TRACK_FIELDS,
            enableUserData = true,
            sortBy = listOf(
                ItemSortBy.PRODUCTION_YEAR,
                ItemSortBy.ALBUM,
                ItemSortBy.PARENT_INDEX_NUMBER,
                ItemSortBy.INDEX_NUMBER,
            ),
            sortOrder = listOf(SortOrder.DESCENDING, SortOrder.ASCENDING, SortOrder.ASCENDING, SortOrder.ASCENDING),
        ).content.items.map(mapper::track)
    }

    /**
     * The songs or videos with [ids], in that order; ids the server does not know are left out.
     * Asked for in parts, so a long list does not make too long an address.
     */
    suspend fun tracks(ids: List<UUID>): List<MusicTrack> = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext emptyList()
        val session = requireSession()
        val mapper = MediaMapper(session.api)
        val byId = coroutineScope {
            ids.distinct().chunked(IDS_PER_REQUEST).map { part ->
                async {
                    session.api.libraryApi.getItems(
                        userId = session.userId,
                        ids = part,
                        fields = TRACK_FIELDS,
                        enableUserData = true,
                    ).content.items
                }
            }.awaitAll().flatten().associateBy { it.id }
        }
        ids.mapNotNull { byId[it] }.map(mapper::track)
    }

    /** The playlist's songs in its order; videos in a mixed playlist are left out. */
    suspend fun playlistTracks(playlistId: UUID): List<MusicTrack> = withContext(Dispatchers.IO) {
        val session = requireSession()
        val mapper = MediaMapper(session.api)
        session.api.playlistApi.getPlaylistItems(
            playlistId = playlistId,
            userId = session.userId,
            fields = TRACK_FIELDS,
            enableUserData = true,
        ).content.items
            .filter { it.type == BaseItemKind.AUDIO || it.mediaType == MediaType.AUDIO }
            .map(mapper::track)
    }

    /** What the music player plays of a playlist: its songs and music videos in its order. */
    suspend fun playlistQueue(playlistId: UUID): List<MusicTrack> = withContext(Dispatchers.IO) {
        val session = requireSession()
        val mapper = MediaMapper(session.api)
        session.api.playlistApi.getPlaylistItems(
            playlistId = playlistId,
            userId = session.userId,
            fields = TRACK_FIELDS,
            enableUserData = true,
        ).content.items
            .filter { it.type == BaseItemKind.AUDIO || it.type == BaseItemKind.MUSIC_VIDEO }
            .map(mapper::track)
    }

    /** Songs like [itemId] (a song, album, artist or playlist), picked by the server. */
    suspend fun instantMix(itemId: UUID): List<MusicTrack> = withContext(Dispatchers.IO) {
        val session = requireSession()
        val mapper = MediaMapper(session.api)
        session.api.instantMixApi.getInstantMixFromItem(
            itemId = itemId,
            userId = session.userId,
            limit = MIX_LIMIT,
            fields = TRACK_FIELDS,
        )
            .content.items
            .filter { it.type == BaseItemKind.AUDIO }
            .map(mapper::track)
    }

    /** The user's music playlists, by name. */
    suspend fun playlists(): List<MediaItem> = withContext(Dispatchers.IO) {
        val session = requireSession()
        val mapper = MediaMapper(session.api)
        session.api.libraryApi.getItems(
            userId = session.userId,
            includeItemTypes = listOf(BaseItemKind.PLAYLIST),
            mediaTypes = listOf(MediaType.AUDIO),
            recursive = true,
            fields = listOf(ItemFields.CHILD_COUNT),
            sortBy = listOf(ItemSortBy.SORT_NAME),
        ).content.items.map(mapper::item)
    }

    /** Adds [trackIds] at the end of the playlist. */
    suspend fun addToPlaylist(playlistId: UUID, trackIds: List<UUID>) {
        withContext(Dispatchers.IO) {
            val session = requireSession()
            session.api.playlistApi.addItemToPlaylist(playlistId = playlistId, ids = trackIds, userId = session.userId)
        }
    }

    /** A new music playlist of the user's, holding [trackIds]. */
    suspend fun createPlaylist(name: String, trackIds: List<UUID>) {
        withContext(Dispatchers.IO) {
            val session = requireSession()
            session.api.playlistApi.createPlaylist(
                CreatePlaylistDto(
                    name = name,
                    ids = trackIds,
                    userId = session.userId,
                    mediaType = MediaType.AUDIO,
                    users = emptyList(),
                    isPublic = false,
                ),
            )
        }
    }

    /** Removes entries ([MusicTrack.playlistItemId]) from the playlist. */
    suspend fun removeFromPlaylist(playlistId: UUID, entryIds: List<String>) {
        withContext(Dispatchers.IO) {
            requireSession().api.playlistApi
                .removeItemFromPlaylist(playlistId = playlistId.toString(), entryIds = entryIds)
        }
    }

    /** Null when the song has no lyrics. */
    suspend fun lyrics(trackId: UUID): Lyrics? = withContext(Dispatchers.IO) {
        val api = requireSession().api
        val dto = try {
            api.lyricApi.getLyrics(trackId).content
        } catch (e: InvalidStatusException) {
            // 404: nothing stored for this song.
            if (e.status == HTTP_NOT_FOUND) return@withContext null else throw e
        }
        parseLyrics(dto.lyrics.map { LyricLine(it.text, it.start?.let { ticks -> ticks / TICKS_PER_MS }) })
    }

    /**
     * The first theme song of a movie or show, or null. Fetched again as an
     * item for its format, which the theme song list leaves out.
     */
    suspend fun themeSong(itemId: UUID): MusicTrack? = withContext(Dispatchers.IO) {
        val session = requireSession()
        val song = session.api.libraryApi.getThemeSongs(
            itemId = itemId,
            userId = session.userId,
            inheritFromParent = true,
        )
            .content.items.firstOrNull() ?: return@withContext null
        session.api.libraryApi.getItems(userId = session.userId, ids = listOf(song.id), fields = TRACK_FIELDS)
            .content.items.firstOrNull()?.let(MediaMapper(session.api)::track)
    }

    /**
     * Genres, play statistics, the album's song count and the artist of [track]. Each part
     * that cannot be fetched is left out; null when the song itself cannot be.
     */
    suspend fun songDetails(track: MusicTrack): SongDetails? = withContext(Dispatchers.IO) {
        val session = requireSession()
        val dto = try {
            session.api.libraryApi.getItem(itemId = track.id, userId = session.userId).content
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Details of song ${track.id} unavailable", e)
            return@withContext null
        }
        val albumId = track.albumId ?: dto.albumId
        val artistRef = dto.artistItems?.firstOrNull() ?: dto.albumArtists?.firstOrNull()
        val (albumCount, artist) = coroutineScope {
            val count = async {
                albumId?.let { id ->
                    optional("album $id") {
                        session.api.libraryApi.getItem(itemId = id, userId = session.userId).content.childCount
                    }
                }
            }
            val info = async {
                artistRef?.let { ref ->
                    optional("artist ${ref.id}") { artistInfo(session, ref.id, ref.name) }
                }
            }
            count.await() to info.await()
        }
        SongDetails(
            genres = dto.genres.orEmpty().filter { it.isNotBlank() },
            playCount = dto.userData?.playCount ?: 0,
            lastPlayed = dto.userData?.lastPlayedDate?.toLocalDate(),
            albumTrackCount = albumCount?.takeIf { it > 0 },
            artist = artist,
        )
    }

    private suspend fun artistInfo(
        session: Session,
        id: UUID,
        fallbackName: String?,
    ): ArtistInfo? {
        val dto = session.api.libraryApi.getItem(itemId = id, userId = session.userId).content
        return ArtistInfo(
            id = id,
            name = dto.name?.takeIf { it.isNotBlank() } ?: fallbackName ?: return null,
            imageUrl = dto.imageTags?.get(ImageType.PRIMARY)?.let { tag ->
                session.api.imageApi.getItemImageUrl(
                    itemId = id,
                    imageType = ImageType.PRIMARY,
                    tag = tag,
                    maxWidth = ARTIST_IMAGE_WIDTH,
                )
            },
            overview = plainText(dto.overview),
        )
    }

    private suspend fun <T> optional(what: String, block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Details of $what unavailable", e)
        null
    }

    private fun requireSession(): Session = checkNotNull(sessions.session.value) { "No profile is signed in" }

    private val Session.userId: UUID get() = UUID.fromString(user.userId)

    private companion object {
        const val TICKS_PER_MS = 10_000L
        const val HTTP_NOT_FOUND = 404
        const val MIX_LIMIT = 100
        const val ARTIST_IMAGE_WIDTH = 400
        const val TAG = "MusicRepository"

        /** Ids per request for [tracks]; a hundred make an address of about 3.7 kB. */
        const val IDS_PER_REQUEST = 100
        val TRACK_FIELDS = listOf(ItemFields.MEDIA_STREAMS)
    }
}

/** LRC tags the server passes on as text: "[ar: Artist]", "[au: instrumental]". */
private val LrcTag = Regex("""^\[\s*([a-z]+)\s*:(.*)]$""", RegexOption.IGNORE_CASE)

/** Lyrics lines as the server sends them, without LRC tags; null when nothing is left to show. */
internal fun parseLyrics(raw: List<LyricLine>): Lyrics? {
    val tags = raw.mapNotNull { LrcTag.matchEntire(it.text.trim()) }
    val lines = raw.filter { LrcTag.matchEntire(it.text.trim()) == null }
    if (lines.all { it.text.isBlank() }) {
        val instrumental = tags.any { it.groupValues[2].trim().equals("instrumental", ignoreCase = true) }
        return if (instrumental) Lyrics(emptyList(), synced = false, instrumental = true) else null
    }
    val synced = lines.any { it.startMs != null }
    return Lyrics(if (synced) lines.filter { it.startMs != null } else lines, synced)
}
