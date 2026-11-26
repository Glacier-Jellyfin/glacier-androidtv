package io.github.glacier_jellyfin.androidtv.core.data.media

import io.github.glacier_jellyfin.androidtv.core.data.Session
import io.github.glacier_jellyfin.androidtv.core.data.SessionManager
import io.github.glacier_jellyfin.androidtv.core.jellyfin.runSuspendCatching
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.userViewApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemSortBy

/** Everything the profile marked with the heart, by kind, each sorted by name. */
data class Favorites(
    val movies: List<MediaItem> = emptyList(),
    val shows: List<MediaItem> = emptyList(),
    val episodes: List<MediaItem> = emptyList(),
    val albums: List<MediaItem> = emptyList(),
    val artists: List<MediaItem> = emptyList(),
    val songs: List<MusicTrack> = emptyList(),
)

/** The favorites page: titles by library, music, and songs. */
@Singleton
class FavoritesRepository @Inject constructor(
    private val sessions: SessionManager,
    private val ageFilter: AgeFilter,
    private val recordingFolders: RecordingFolders,
) {
    suspend fun load(): Favorites = withContext(Dispatchers.IO) {
        val session = checkNotNull(sessions.session.value) { "No profile is signed in" }
        val mapper = MediaMapper(session.api)
        coroutineScope {
            val songs = async { runSuspendCatching { songs(session, mapper) }.getOrDefault(emptyList()) }
            val items = ageFilter.screen(titles(session).map(mapper::item))
            fun of(kind: ItemKind) = items.filter { it.kind == kind }
            Favorites(
                movies = of(ItemKind.Movie),
                shows = of(ItemKind.Series),
                episodes = of(ItemKind.Episode),
                albums = of(ItemKind.Album),
                artists = of(ItemKind.Artist),
                songs = songs.await(),
            )
        }
    }

    /**
     * Movies and shows are asked library by library, so the recordings stay out. Music
     * is not in those libraries and is asked server wide.
     */
    private suspend fun titles(session: Session): List<BaseItemDto> = coroutineScope {
        val recordings = recordingFolders.of(session.api, session.userId)
        val views = session.api.userViewApi.getUserViews(userId = session.userId).content.items
        val libraries = libraryKindsOf(views, setOf(LibraryKind.Movies, LibraryKind.Shows), recordings)
        val music = async {
            session.api.libraryApi.getItems(
                userId = session.userId,
                includeItemTypes = listOf(BaseItemKind.MUSIC_ALBUM, BaseItemKind.MUSIC_ARTIST),
                recursive = true,
                isFavorite = true,
                sortBy = listOf(ItemSortBy.SORT_NAME),
                fields = TITLE_FIELDS,
                enableUserData = true,
                limit = TITLES,
            ).content.items
        }
        val results = perLibrary(libraries) { library ->
            session.api.libraryApi.getItems(
                userId = session.userId,
                parentId = library.id,
                includeItemTypes = if (library.kind == LibraryKind.Shows) {
                    listOf(BaseItemKind.SERIES, BaseItemKind.EPISODE)
                } else {
                    listOf(library.itemType)
                },
                recursive = true,
                isFavorite = true,
                sortBy = listOf(ItemSortBy.SORT_NAME),
                fields = TITLE_FIELDS,
                enableUserData = true,
                limit = TITLES,
            ).content.items
        }
        mergeLimited(results, TITLES, bySortName) + music.await()
    }

    private suspend fun songs(session: Session, mapper: MediaMapper): List<MusicTrack> =
        session.api.libraryApi.getItems(
            userId = session.userId,
            includeItemTypes = listOf(BaseItemKind.AUDIO),
            recursive = true,
            isFavorite = true,
            sortBy = listOf(ItemSortBy.SORT_NAME),
            fields = listOf(ItemFields.MEDIA_STREAMS),
            enableUserData = true,
            limit = SONGS,
        ).content.items.map(mapper::track)

    private val Session.userId: UUID get() = UUID.fromString(user.userId)

    private companion object {
        const val TITLES = 600
        const val SONGS = 200
        val TITLE_FIELDS = listOf(ItemFields.OVERVIEW, ItemFields.GENRES, ItemFields.MEDIA_STREAMS)
    }
}
