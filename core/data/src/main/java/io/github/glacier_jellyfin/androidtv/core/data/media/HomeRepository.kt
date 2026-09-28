package io.github.glacier_jellyfin.androidtv.core.data.media

import io.github.glacier_jellyfin.androidtv.core.data.Session
import io.github.glacier_jellyfin.androidtv.core.data.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.showApi
import org.jellyfin.sdk.api.client.extensions.userApi
import org.jellyfin.sdk.api.client.extensions.userDataApi
import org.jellyfin.sdk.api.client.extensions.userViewApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.MediaType
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HomeRepository @Inject constructor(
    private val sessions: SessionManager,
) {

    /** Loads all home rows in parallel for the signed-in profile. */
    suspend fun load(): HomeContent = withContext(Dispatchers.IO) {
        val session = requireSession()
        val mapper = MediaMapper(session.api)
        val userId = session.userId

        val librariesAsync = async { libraries(session, mapper) }
        val resumeAsync = async { resume(session) }
        val nextUpAsync = async { nextUp(session) }
        val excludesAsync = async {
            session.api.userApi.getCurrentUser().content.configuration?.latestItemsExcludes.orEmpty().toSet()
        }

        val libraries = librariesAsync.await()
        val excludes = excludesAsync.await()
        val latest = libraries
            .filterNot { it.id in excludes }
            .map { library -> async { library to latest(session, userId, library).map(mapper::item) } }
            .awaitAll()
            .filter { (_, items) -> items.isNotEmpty() }

        HomeContent(
            libraries = libraries,
            continueWatching = mergeContinueWatching(resumeAsync.await(), nextUpAsync.await()).map(mapper::item),
            latest = latest,
        )
    }

    suspend fun setFavorite(itemId: UUID, favorite: Boolean) {
        val api = requireSession().api.userDataApi
        withContext(Dispatchers.IO) {
            if (favorite) api.markFavoriteItem(itemId) else api.unmarkFavoriteItem(itemId)
        }
    }

    private suspend fun libraries(session: Session, mapper: MediaMapper): List<Library> = coroutineScope {
        session.api.userViewApi.getUserViews(userId = session.userId).content.items
            .mapNotNull { view -> view.collectionType.toLibraryKind()?.let { view to it } }
            .map { (view, kind) -> async { mapper.library(view, kind, count(session, view.id, kind)) } }
            .awaitAll()
    }

    private suspend fun count(session: Session, libraryId: UUID, kind: LibraryKind): Int? = runCatching {
        session.api.libraryApi.getItems(
            userId = session.userId,
            parentId = libraryId,
            recursive = true,
            includeItemTypes = listOf(kind.countedType),
            limit = 0,
            enableTotalRecordCount = true,
        ).content.totalRecordCount
    }.getOrNull()

    private suspend fun resume(session: Session): List<BaseItemDto> =
        session.api.libraryApi.getResumeItems(
            userId = session.userId,
            limit = ROW_LIMIT,
            mediaTypes = listOf(MediaType.VIDEO),
            fields = FIELDS,
            enableUserData = true,
        ).content.items

    private suspend fun nextUp(session: Session): List<BaseItemDto> =
        session.api.showApi.getNextUp(
            userId = session.userId,
            limit = ROW_LIMIT,
            fields = FIELDS,
            enableUserData = true,
            // Episodes already started are in the resume list.
            enableResumable = false,
        ).content.items

    private suspend fun latest(session: Session, userId: UUID, library: Library): List<BaseItemDto> {
        val items = session.api.libraryApi.getLatestMedia(
            userId = userId,
            parentId = library.id,
            fields = FIELDS,
            enableUserData = true,
            limit = ROW_LIMIT,
        ).content
        return if (library.kind == LibraryKind.Shows) asShows(session, userId, items) else items
    }

    /**
     * "New in shows" lists shows: the server hands out a season or an episode
     * when only that part is new, which would put a season poster in the row
     * and open the season. Those become their show, once each, in the same order.
     */
    private suspend fun asShows(session: Session, userId: UUID, items: List<BaseItemDto>): List<BaseItemDto> {
        val showIds = items.map { if (it.type == BaseItemKind.SERIES) it.id else it.seriesId }
        val missing = showIds.filterNotNull().filter { id -> items.none { it.id == id } }.distinct()
        val shows = if (missing.isEmpty()) emptyMap() else session.api.libraryApi.getItems(
            userId = userId,
            ids = missing,
            fields = FIELDS,
            enableUserData = true,
        ).content.items.associateBy { it.id }
        return items.mapIndexedNotNull { index, item ->
            val id = showIds[index] ?: return@mapIndexedNotNull item
            if (item.id == id) item else shows[id]
        }.distinctBy { it.id }
    }

    private fun requireSession(): Session = checkNotNull(sessions.session.value) { "No profile is signed in" }

    private val Session.userId: UUID get() = UUID.fromString(user.userId)

    private val LibraryKind.countedType: BaseItemKind
        get() = when (this) {
            LibraryKind.Movies -> BaseItemKind.MOVIE
            LibraryKind.Shows -> BaseItemKind.SERIES
            LibraryKind.Music -> BaseItemKind.MUSIC_ALBUM
        }

    private companion object {
        const val ROW_LIMIT = 16
        val FIELDS = listOf(ItemFields.OVERVIEW, ItemFields.GENRES, ItemFields.MEDIA_STREAMS)
    }
}

/**
 * Started titles first (most recent first, as the server orders them, one
 * per show), then next episodes of shows that are not already in the list.
 */
internal fun mergeContinueWatching(resume: List<BaseItemDto>, nextUp: List<BaseItemDto>): List<BaseItemDto> {
    // One entry per show: the most recently watched of its started episodes.
    val started = resume.distinctBy { it.seriesId ?: it.id }
    val seriesInResume = started.mapNotNullTo(HashSet()) { it.seriesId }
    val ids = started.mapTo(HashSet()) { it.id }
    return started + nextUp.filter { it.id !in ids && (it.seriesId == null || it.seriesId !in seriesInResume) }
}
