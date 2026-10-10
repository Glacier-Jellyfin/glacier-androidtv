package io.github.glacier_jellyfin.androidtv.core.data.media

import io.github.glacier_jellyfin.androidtv.core.data.Session
import io.github.glacier_jellyfin.androidtv.core.data.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemSortBy
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Everything the profile marked with the heart, by kind, each sorted by name. */
data class Favorites(
    val movies: List<MediaItem> = emptyList(),
    val shows: List<MediaItem> = emptyList(),
    val episodes: List<MediaItem> = emptyList(),
    val albums: List<MediaItem> = emptyList(),
    val artists: List<MediaItem> = emptyList(),
    val songs: List<MusicTrack> = emptyList(),
)

/** The favorites page: one query for titles, one for songs. */
@Singleton
class FavoritesRepository @Inject constructor(
    private val sessions: SessionManager,
    private val ageFilter: AgeFilter,
) {
    suspend fun load(): Favorites = withContext(Dispatchers.IO) {
        val session = checkNotNull(sessions.session.value) { "No profile is signed in" }
        val mapper = MediaMapper(session.api)
        coroutineScope {
            val songs = async { runCatching { songs(session, mapper) }.getOrDefault(emptyList()) }
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

    private suspend fun titles(session: Session) =
        session.api.libraryApi.getItems(
            userId = session.userId,
            includeItemTypes = listOf(
                BaseItemKind.MOVIE,
                BaseItemKind.SERIES,
                BaseItemKind.EPISODE,
                BaseItemKind.MUSIC_ALBUM,
                BaseItemKind.MUSIC_ARTIST,
            ),
            recursive = true,
            isFavorite = true,
            sortBy = listOf(ItemSortBy.SORT_NAME),
            fields = listOf(ItemFields.OVERVIEW, ItemFields.GENRES, ItemFields.MEDIA_STREAMS),
            enableUserData = true,
            limit = TITLES,
        ).content.items

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
    }
}
