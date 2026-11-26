package io.github.glacier_jellyfin.androidtv.core.data.media

import io.github.glacier_jellyfin.androidtv.core.data.Session
import io.github.glacier_jellyfin.androidtv.core.data.SessionManager
import io.github.glacier_jellyfin.androidtv.core.data.settings.SettingsRepository
import io.github.glacier_jellyfin.androidtv.core.jellyfin.runSuspendCatching
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
    /** Favorite Live TV recordings, newest first; the lists above leave recordings out. */
    val recordings: List<MediaItem> = emptyList(),
)

/** The favorites page: titles by library, music, and songs. */
@Singleton
class FavoritesRepository @Inject constructor(
    private val sessions: SessionManager,
    private val ageFilter: AgeFilter,
    private val recordingFolders: RecordingFolders,
    private val liveTv: LiveTvRepository,
    private val settings: SettingsRepository,
    private val metadataLanguage: MetadataLanguage,
) {
    suspend fun load(): Favorites = withContext(Dispatchers.IO) {
        val session = checkNotNull(sessions.session.value) { "No profile is signed in" }
        val mapper = MediaMapper(session.api)
        coroutineScope {
            val songs = async { runSuspendCatching { songs(session, mapper) }.getOrDefault(emptyList()) }
            // No Live TV or a failing server: no row.
            val recordings = async {
                runSuspendCatching {
                    liveTv.recordingDtos()
                        .filter { it.userData?.isFavorite == true }
                        .newestRecordings(RECORDINGS)
                        .map(mapper::item)
                        .let { ageFilter.screen(it) }
                }.getOrDefault(emptyList())
            }
            val appearance = settings.settings.value.appearance
            val requested = Articles.requested(appearance.ignoreArticles)
            val articles = when (requested) {
                // Favorites span libraries: the server-wide language, else the interface language.
                Articles.AUTO -> Articles.resolveAuto(
                    null,
                    metadataLanguage.ofServer(session),
                    Locale.getDefault().language,
                )

                else -> requested
            }
            val dtos = titles(session)
            val places = artistPlaces(session, dtos, articles)
            val items = ageFilter.screen(
                dtos.map { dto ->
                    mapper.item(dto).copy(
                        sortTitle = NameOrder.sortTitle(
                            dto.name.orEmpty(),
                            dto.sortName,
                            dto.originalTitle,
                            dto.productionLocations ?: dto.albumArtists?.firstOrNull()?.id?.let(places::get),
                            articles,
                        ),
                    )
                },
            )

            // The server sorts by its own sort name, so the app sorts by the name the library lists use.
            // Episodes keep the server's order: an episode's name does not say where it belongs.
            fun of(kind: ItemKind) = items.filter { it.kind == kind }
                .sortedWith(compareBy(NameOrder) { it.sortTitle ?: it.title })
            Favorites(
                movies = of(ItemKind.Movie),
                shows = of(ItemKind.Series),
                episodes = items.filter { it.kind == ItemKind.Episode },
                albums = of(ItemKind.Album),
                artists = of(ItemKind.Artist),
                songs = songs.await(),
                recordings = recordings.await(),
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

    /**
     * The countries of the album artists of the favorite albums, by artist id: an album takes the language
     * of its artist. One request for all of them, a few ids at a time. Empty when articles are off.
     */
    private suspend fun artistPlaces(
        session: Session,
        dtos: List<BaseItemDto>,
        articles: String?,
    ): Map<UUID, List<String>> {
        if (articles == null) return emptyMap()
        val ids = dtos.filter { it.type == BaseItemKind.MUSIC_ALBUM }
            .mapNotNull { it.albumArtists?.firstOrNull()?.id }
            .distinct()
        if (ids.isEmpty()) return emptyMap()
        return runSuspendCatching {
            coroutineScope {
                ids.chunked(IDS_PER_REQUEST).map { chunk ->
                    async {
                        session.api.libraryApi.getItems(
                            userId = session.userId,
                            ids = chunk,
                            fields = listOf(ItemFields.PRODUCTION_LOCATIONS),
                            enableImages = false,
                            enableUserData = false,
                        ).content.items
                    }
                }.awaitAll().flatten()
            }.mapNotNull { artist -> artist.productionLocations?.takeIf { it.isNotEmpty() }?.let { artist.id to it } }
                .toMap()
        }.getOrDefault(emptyMap())
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

        /** What a card needs, and what the app sorts by (see [NameOrder.sortTitle]). */
        val TITLE_FIELDS = listOf(
            ItemFields.OVERVIEW,
            ItemFields.GENRES,
            ItemFields.MEDIA_STREAMS,
            ItemFields.SORT_NAME,
            ItemFields.ORIGINAL_TITLE,
            ItemFields.PRODUCTION_LOCATIONS,
        )
        const val RECORDINGS = 200

        /** Ids per request, so the address stays short. */
        const val IDS_PER_REQUEST = 40
    }
}
