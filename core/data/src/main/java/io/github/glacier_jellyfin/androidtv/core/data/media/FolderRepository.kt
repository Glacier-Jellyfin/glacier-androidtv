package io.github.glacier_jellyfin.androidtv.core.data.media

import io.github.glacier_jellyfin.androidtv.core.data.Session
import io.github.glacier_jellyfin.androidtv.core.data.SessionManager
import io.github.glacier_jellyfin.androidtv.core.data.settings.FolderSettings
import io.github.glacier_jellyfin.androidtv.core.data.settings.FolderSort
import io.github.glacier_jellyfin.androidtv.core.data.settings.SettingsRepository
import io.github.glacier_jellyfin.androidtv.core.jellyfin.runSuspendCatching
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.userViewApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.MediaType
import java.time.LocalDateTime
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** A folder of a home video library, or the library itself when there are several. */
data class MediaFolder(val id: UUID, val name: String)

/** A photo: [thumbUrl] for its card, [fullUrl] screen-sized for the viewer. */
data class MediaPhoto(val id: UUID, val name: String, val thumbUrl: String, val fullUrl: String)

/** A video or photo of a folder, with the time it was taken, else the file's date. */
sealed interface FolderMedia {
    val id: UUID
    val name: String
    val date: LocalDateTime?

    data class Video(val item: MediaItem, override val date: LocalDateTime?) : FolderMedia {
        override val id: UUID get() = item.id
        override val name: String get() = item.title
    }

    data class Photo(val photo: MediaPhoto, override val date: LocalDateTime?) : FolderMedia {
        override val id: UUID get() = photo.id
        override val name: String get() = photo.name
    }
}

/**
 * What a folder holds: its subfolders by name, and its videos and photos ([media], by name too).
 * [id] is the folder shown, null for the list of several libraries. [trail] names the folders
 * above it, from the top: the library only when there are several. Empty at the top.
 */
data class FolderContent(
    val id: UUID?,
    val folders: List<MediaFolder>,
    val media: List<FolderMedia> = emptyList(),
    val trail: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = folders.isEmpty() && media.isEmpty()
}

/** Home video libraries, browsed by their folders on the server's disk. */
@Singleton
class FolderRepository @Inject constructor(
    private val sessions: SessionManager,
    private val ageFilter: AgeFilter,
    private val settings: SettingsRepository,
) {
    /**
     * The folder [folderId]. Without one, the top: the home video libraries as folders,
     * or the content of the only one right away.
     */
    suspend fun load(folderId: UUID?): FolderContent = withContext(Dispatchers.IO) {
        val session = checkNotNull(sessions.session.value) { "No profile is signed in" }
        coroutineScope {
            val libraries = async { libraries(session) }
            // The path is only shown, so the folder opens without it when it fails.
            val ancestors = folderId?.let { async { runSuspendCatching { ancestors(session, it) }.getOrNull() } }
            val parent = folderId ?: libraries.await().let { all ->
                if (all.size != 1) {
                    return@coroutineScope FolderContent(null, all.map { MediaFolder(it.id, it.name.orEmpty()) })
                }
                all.single().id
            }
            val items = children(session, parent)
            FolderContent(
                id = parent,
                folders = items.filter { it.isFolder == true }.map { MediaFolder(it.id, it.name.orEmpty()) },
                media = media(session, items),
                trail = ancestors?.await()?.let { trail(it, severalLibraries = libraries.await().size > 1) }.orEmpty(),
            )
        }
    }

    /** The videos and photos of [folderId] in the order the profile sorts folders by. */
    suspend fun media(folderId: UUID): List<FolderMedia> = withContext(Dispatchers.IO) {
        val session = checkNotNull(sessions.session.value) { "No profile is signed in" }
        media(session, children(session, folderId)).sortedFor(settings.settings.value.folders)
    }

    /** The videos of [folderId] in the order the folder page lists them, for playing one after another. */
    suspend fun videos(folderId: UUID): List<MediaItem> =
        media(folderId).filterIsInstance<FolderMedia.Video>().map { it.item }

    private suspend fun media(session: Session, items: List<BaseItemDto>): List<FolderMedia> {
        val mapper = MediaMapper(session.api)
        val videos = items.filter { it.isVideo }
        val screened = ageFilter.screen(videos.map(mapper::item)).associateBy { it.id }
        return items.mapNotNull { dto ->
            when {
                dto.isVideo -> screened[dto.id]?.let { FolderMedia.Video(it, dto.taken) }
                dto.isPhoto -> mapper.photo(dto)?.let { FolderMedia.Photo(it, dto.taken) }
                else -> null
            }
        }
    }

    private suspend fun libraries(session: Session) =
        session.api.userViewApi.getUserViews(userId = session.userId).content.items
            .filter { it.collectionType == CollectionType.HOMEVIDEOS }

    private suspend fun children(session: Session, parentId: UUID) =
        session.api.libraryApi.getItems(
            userId = session.userId,
            parentId = parentId,
            sortBy = listOf(ItemSortBy.SORT_NAME),
            fields = listOf(ItemFields.DATE_CREATED),
            enableUserData = true,
        ).content.items

    /** From the folder's parent up to the server's root. */
    private suspend fun ancestors(session: Session, folderId: UUID) =
        session.api.libraryApi.getAncestors(itemId = folderId, userId = session.userId).content

    private val BaseItemDto.isVideo: Boolean get() = isFolder != true && mediaType == MediaType.VIDEO

    private val BaseItemDto.isPhoto: Boolean get() = isFolder != true && type == BaseItemKind.PHOTO

    /**
     * Jellyfin keeps the time a photo or video was taken as its premiere date; the file's date otherwise.
     * The SDK hands both over in the TV's own time already.
     */
    private val BaseItemDto.taken: LocalDateTime? get() = premiereDate ?: dateCreated

    private val Session.userId: UUID get() = UUID.fromString(user.userId)
}

/**
 * [settings]' order: by date, undated last and same dates by name, or by name. [FolderSettings.descending]
 * turns either around, except that undated stay last.
 */
fun List<FolderMedia>.sortedFor(settings: FolderSettings): List<FolderMedia> {
    // As a library lists titles, so the A–Z rail's letters follow the same order.
    val byName = compareBy<FolderMedia, String>(NameOrder) { it.name }
    return when (settings.sort) {
        FolderSort.Name -> sortedWith(if (settings.descending) byName.reversed() else byName)
        FolderSort.Date -> {
            val (dated, undated) = partition { it.date != null }
            val byDate = compareBy<FolderMedia> { it.date }.then(byName)
            dated.sortedWith(if (settings.descending) byDate.reversed() else byDate) + undated.sortedWith(byName)
        }
    }
}

/**
 * Names of the folders above one, from the top: [ancestors] (parent first, up to the server's root)
 * up to the library, which counts only when there are [severalLibraries].
 */
internal fun trail(ancestors: List<BaseItemDto>, severalLibraries: Boolean): List<String> {
    val library = ancestors.indexOfFirst { it.type == BaseItemKind.COLLECTION_FOLDER }
    if (library < 0) return emptyList()
    val below = ancestors.take(if (severalLibraries) library + 1 else library)
    return below.reversed().map { it.name.orEmpty() }
}
