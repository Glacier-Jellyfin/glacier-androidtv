package io.github.glacier_jellyfin.androidtv.core.data.media

import io.github.glacier_jellyfin.androidtv.core.data.Session
import io.github.glacier_jellyfin.androidtv.core.data.SessionManager
import io.github.glacier_jellyfin.androidtv.core.data.settings.FolderSettings
import io.github.glacier_jellyfin.androidtv.core.data.settings.FolderSort
import io.github.glacier_jellyfin.androidtv.core.data.settings.SettingsRepository
import io.github.glacier_jellyfin.androidtv.core.jellyfin.runSuspendCatching
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
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
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.MediaType

/** What a folder lists, sorted by [name] or [date]: a subfolder, video or photo. */
interface FolderEntry {
    val name: String
    val date: LocalDateTime?
}

/** A folder above the one shown, with the id that opens it. */
data class TrailFolder(val id: UUID, val name: String)

/** A folder of a home video library, or the library itself when there are several; dated like a photo. */
data class MediaFolder(
    val id: UUID,
    override val name: String,
    override val date: LocalDateTime? = null,
) : FolderEntry

/**
 * A photo: [thumbUrl] for its card, [fullUrl] screen-sized for the viewer. [blurHash] is the server's tiny
 * placeholder of the picture, [aspectRatio] its width over height; the viewer needs both to show the placeholder.
 */
data class MediaPhoto(
    val id: UUID,
    val name: String,
    val thumbUrl: String,
    val fullUrl: String,
    val blurHash: String? = null,
    val aspectRatio: Float? = null,
)

/** A video or photo of a folder, with the time it was taken, else the file's date. */
sealed interface FolderMedia : FolderEntry {
    val id: UUID

    /** The server lets this user delete it; only known where the viewer's list was loaded, else false. */
    val canDelete: Boolean

    /** [added]: when it came to the library, for music videos sorted by that. */
    data class Video(
        val item: MediaItem,
        override val date: LocalDateTime?,
        val added: LocalDateTime? = null,
        override val canDelete: Boolean = false,
    ) : FolderMedia {
        override val id: UUID get() = item.id
        override val name: String get() = item.title
    }

    data class Photo(
        val photo: MediaPhoto,
        override val date: LocalDateTime?,
        override val canDelete: Boolean = false,
    ) : FolderMedia {
        override val id: UUID get() = photo.id
        override val name: String get() = photo.name
    }
}

/**
 * What a folder holds: its subfolders and its videos and photos ([media]), both by name; the page sorts them for the profile.
 * [id] is the folder shown, null for the list of several libraries. [trail] lists the folders
 * above it, from the top: the library only when there are several. Empty at the top.
 */
data class FolderContent(
    val id: UUID?,
    val folders: List<MediaFolder>,
    val media: List<FolderMedia> = emptyList(),
    val trail: List<TrailFolder> = emptyList(),
) {
    val isEmpty: Boolean get() = folders.isEmpty() && media.isEmpty()
}

private const val SIZE_TIMEOUT_MS = 5_000

/** The total of a Content-Range header ("bytes 0-0/365513" is 365513); null when it is unknown ("*") or malformed. */
internal fun totalOfContentRange(header: String?): Long? =
    header?.substringAfterLast('/', "")?.trim()?.toLongOrNull()?.takeIf { it > 0 }

/** Home video libraries, and music video libraries in their folder view, browsed by their folders on the server's disk. */
@Singleton
class FolderRepository @Inject constructor(
    private val sessions: SessionManager,
    private val ageFilter: AgeFilter,
    private val settings: SettingsRepository,
    private val recordingFolders: RecordingFolders,
) {
    /**
     * The folder [folderId]. Without one, the top: the libraries of [type] as folders,
     * or the content of the only one right away. [withDelete] also asks whether this user may delete each
     * photo and video ([FolderMedia.canDelete]), for the page that offers it.
     */
    suspend fun load(
        folderId: UUID?,
        type: CollectionType = CollectionType.HOMEVIDEOS,
        withDelete: Boolean = false,
    ): FolderContent = withContext(Dispatchers.IO) {
        val session = checkNotNull(sessions.session.value) { "No profile is signed in" }
        coroutineScope {
            val libraries = async { libraries(session, type) }
            // The path is only shown, so the folder opens without it when it fails.
            val ancestors = folderId?.let { async { runSuspendCatching { ancestors(session, it) }.getOrNull() } }
            val parent = folderId ?: libraries.await().let { all ->
                if (all.size != 1) {
                    return@coroutineScope FolderContent(
                        null,
                        all.map { MediaFolder(it.id, it.name.orEmpty(), it.taken) },
                    )
                }
                all.single().id
            }
            val items = children(session, parent, withDelete)
            FolderContent(
                id = parent,
                folders = items.filter { it.isFolder == true }.map { MediaFolder(it.id, it.name.orEmpty(), it.taken) },
                media = media(session, items),
                trail = ancestors?.await()?.let { trail(it, severalLibraries = libraries.await().size > 1) }.orEmpty(),
            )
        }
    }

    /** The videos and photos of [folderId] in the order the profile sorts folders by. */
    suspend fun media(folderId: UUID): List<FolderMedia> = withContext(Dispatchers.IO) {
        val session = checkNotNull(sessions.session.value) { "No profile is signed in" }
        media(session, children(session, folderId, withDelete = true)).sortedFor(settings.settings.value.folders)
    }

    /** Deletes the photo or video [id] from the server, for everyone; only for what the server allows this user. */
    suspend fun delete(id: UUID) = withContext(Dispatchers.IO) {
        val session = checkNotNull(sessions.session.value) { "No profile is signed in" }
        session.api.libraryApi.deleteItem(itemId = id)
        Unit
    }

    /**
     * The file and camera details of the photo or video [id], fetched when the viewer's details open. A photo has no
     * media source, so its file size comes from the download's Content-Length when the server does not give it.
     */
    suspend fun photoDetails(id: UUID): PhotoDetails = withContext(Dispatchers.IO) {
        val session = checkNotNull(sessions.session.value) { "No profile is signed in" }
        val details = photoDetails(session.api.libraryApi.getItem(itemId = id, userId = session.userId).content)
        if (details.sizeBytes != null) {
            details
        } else {
            details.copy(sizeBytes = downloadSize(session.api.libraryApi.getDownloadUrl(id), session.authorization()))
        }
    }

    /**
     * The size of the file behind [url]: a GET for the first byte (the server answers HEAD with 405) says the total in
     * Content-Range. A server without range support answers 200 and its Content-Length is the size; the body is
     * never read. Null when the server does not say or fails.
     */
    private fun downloadSize(url: String, authorization: String): Long? = runCatching {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = SIZE_TIMEOUT_MS
            connection.readTimeout = SIZE_TIMEOUT_MS
            connection.setRequestProperty("Authorization", authorization)
            connection.setRequestProperty("Range", "bytes=0-0")
            when (connection.responseCode) {
                HttpURLConnection.HTTP_PARTIAL -> totalOfContentRange(connection.getHeaderField("Content-Range"))
                HttpURLConnection.HTTP_OK -> connection.contentLengthLong.takeIf { it > 0 }
                else -> null
            }
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    /** The videos of [folderId] in the order the folder page lists them, for playing one after another. */
    suspend fun videos(folderId: UUID): List<MediaItem> =
        media(folderId).filterIsInstance<FolderMedia.Video>().map { it.item }

    /**
     * The videos in [folderId] and every folder below it; without one, in every library of [type].
     * By name: the page sorts them for the profile.
     */
    suspend fun videosBelow(
        folderId: UUID?,
        type: CollectionType = CollectionType.HOMEVIDEOS,
    ): List<FolderMedia.Video> = withContext(Dispatchers.IO) {
        val session = checkNotNull(sessions.session.value) { "No profile is signed in" }
        val parents = folderId?.let(::listOf) ?: libraries(session, type).map { it.id }
        val items = coroutineScope {
            parents.map { parent ->
                async {
                    session.api.libraryApi.getItems(
                        userId = session.userId,
                        parentId = parent,
                        recursive = true,
                        mediaTypes = listOf(MediaType.VIDEO),
                        sortBy = listOf(ItemSortBy.SORT_NAME),
                        fields = listOf(ItemFields.DATE_CREATED),
                        enableUserData = true,
                    ).content.items
                }
            }.flatMap { it.await() }
        }
        media(session, items).filterIsInstance<FolderMedia.Video>()
    }

    private suspend fun media(session: Session, items: List<BaseItemDto>): List<FolderMedia> {
        val mapper = MediaMapper(session.api)
        val videos = items.filter { it.isVideo }
        val screened = ageFilter.screen(videos.map(mapper::item)).associateBy { it.id }
        return items.mapNotNull { dto ->
            when {
                dto.isVideo -> screened[dto.id]?.let {
                    FolderMedia.Video(it, dto.taken, dto.dateCreated, dto.canDelete == true)
                }

                dto.isPhoto -> mapper.photo(dto)?.let { FolderMedia.Photo(it, dto.taken, dto.canDelete == true) }

                else -> null
            }
        }
    }

    private suspend fun libraries(session: Session, type: CollectionType): List<BaseItemDto> {
        val recordings = recordingFolders.of(session.api, session.userId)
        return session.api.userViewApi.getUserViews(userId = session.userId).content.items
            .filter { it.collectionType == type && it.id !in recordings }
    }

    /** [withDelete] also asks whether this user may delete each item, which the viewer and the folder page need. */
    private suspend fun children(
        session: Session,
        parentId: UUID,
        withDelete: Boolean = false,
    ) = session.api.libraryApi.getItems(
        userId = session.userId,
        parentId = parentId,
        sortBy = listOf(ItemSortBy.SORT_NAME),
        // The aspect ratio sizes a photo's BlurHash placeholder when the item has no width and height.
        fields = listOfNotNull(
            ItemFields.DATE_CREATED,
            ItemFields.PRIMARY_IMAGE_ASPECT_RATIO,
            ItemFields.CAN_DELETE.takeIf { withDelete },
        ),
        enableUserData = true,
    ).content.items

    /** From the folder's parent up to the server's root. */
    private suspend fun ancestors(session: Session, folderId: UUID) =
        session.api.libraryApi.getAncestors(itemId = folderId, userId = session.userId).content

    private val BaseItemDto.isVideo: Boolean get() = isFolder != true && mediaType == MediaType.VIDEO

    private val BaseItemDto.isPhoto: Boolean get() = isFolder != true && type == BaseItemKind.PHOTO

    /**
     * Jellyfin keeps the time a photo or video was taken as its premiere date; the file's (or folder's) date otherwise.
     * The server stores them in UTC, so they are turned into the TV's own time.
     */
    private val BaseItemDto.taken: LocalDateTime? get() = (premiereDate ?: dateCreated)?.utcToLocal()

    private val Session.userId: UUID get() = UUID.fromString(user.userId)
}

/**
 * [settings]' order: by date, undated last and same dates by name, or by name. [FolderSettings.descending]
 * turns either around, except that undated stay last.
 */
fun <T : FolderEntry> List<T>.sortedFor(settings: FolderSettings): List<T> {
    // As a library lists titles, so the A–Z rail's letters follow the same order.
    val byName = compareBy<T, String>(NameOrder) { it.name }
    return when (settings.sort) {
        FolderSort.Name -> sortedWith(if (settings.descending) byName.reversed() else byName)

        FolderSort.Date -> {
            val (dated, undated) = partition { it.date != null }
            val byDate = compareBy<T> { it.date }.then(byName)
            dated.sortedWith(if (settings.descending) byDate.reversed() else byDate) + undated.sortedWith(byName)
        }
    }
}

/**
 * The folders above one, from the top: [ancestors] (parent first, up to the server's root)
 * up to the library, which counts only when there are [severalLibraries].
 */
internal fun trail(ancestors: List<BaseItemDto>, severalLibraries: Boolean): List<TrailFolder> {
    val library = ancestors.indexOfFirst { it.type == BaseItemKind.COLLECTION_FOLDER }
    if (library < 0) return emptyList()
    val below = ancestors.take(if (severalLibraries) library + 1 else library)
    return below.reversed().map { TrailFolder(it.id, it.name.orEmpty()) }
}
