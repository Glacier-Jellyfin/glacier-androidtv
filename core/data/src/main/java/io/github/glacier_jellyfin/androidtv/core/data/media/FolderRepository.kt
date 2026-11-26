package io.github.glacier_jellyfin.androidtv.core.data.media

import io.github.glacier_jellyfin.androidtv.core.data.Session
import io.github.glacier_jellyfin.androidtv.core.data.SessionManager
import io.github.glacier_jellyfin.androidtv.core.data.settings.FolderSettings
import io.github.glacier_jellyfin.androidtv.core.data.settings.FolderSort
import io.github.glacier_jellyfin.androidtv.core.data.settings.SettingsRepository
import io.github.glacier_jellyfin.androidtv.core.jellyfin.runSuspendCatching
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
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

/** A folder of a home video library, or the library itself when there are several; dated like a photo. */
data class MediaFolder(
    val id: UUID,
    override val name: String,
    override val date: LocalDateTime? = null,
) : FolderEntry

/** A photo: [thumbUrl] for its card, [fullUrl] screen-sized for the viewer. */
data class MediaPhoto(
    val id: UUID,
    val name: String,
    val thumbUrl: String,
    val fullUrl: String,
    val canDelete: Boolean = false,
    val takenAt: LocalDateTime? = null,
    val location: PhotoLocation = PhotoLocation(),
)

data class PhotoLocation(
    val names: List<String> = emptyList(),
    val latitude: Double? = null,
    val longitude: Double? = null,
)

data class PhotoDetails(
    val path: String?,
    val takenAt: LocalDateTime?,
    val createdAt: LocalDateTime?,
    val location: PhotoLocation,
    val width: Int?,
    val height: Int?,
    val description: String?,
    val camera: String?,
    val focalLength: Double?,
    val aperture: Double?,
    val exposureTime: Double?,
    val iso: Int?,
) {
    val fileName: String? get() = path?.substringAfterLast('/')?.substringAfterLast('\\')?.takeIf(String::isNotBlank)
}

/** A video or photo of a folder, with the time it was taken, else the file's date. */
sealed interface FolderMedia : FolderEntry {
    val id: UUID

    /** [added]: when it came to the library, for music videos sorted by that. */
    data class Video(
        val item: MediaItem,
        override val date: LocalDateTime?,
        val added: LocalDateTime? = null,
    ) : FolderMedia {
        override val id: UUID get() = item.id
        override val name: String get() = item.title
    }

    data class Photo(val photo: MediaPhoto, override val date: LocalDateTime?) : FolderMedia {
        override val id: UUID get() = photo.id
        override val name: String get() = photo.name
    }
}

/**
 * What a folder holds: its subfolders and its videos and photos ([media]), both by name; the page sorts them for the profile.
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

/** Home video libraries, and music video libraries in their folder view, browsed by their folders on the server's disk. */
@Singleton
class FolderRepository @Inject constructor(
    private val sessions: SessionManager,
    private val ageFilter: AgeFilter,
    private val settings: SettingsRepository,
) {
    /**
     * The folder [folderId]. Without one, the top: the libraries of [type] as folders,
     * or the content of the only one right away.
     */
    suspend fun load(folderId: UUID?, type: CollectionType = CollectionType.HOMEVIDEOS): FolderContent = withContext(
        Dispatchers.IO,
    ) {
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
            val items = children(session, parent)
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
        media(session, children(session, folderId)).sortedFor(settings.settings.value.folders)
    }

    suspend fun photoDetails(id: UUID): PhotoDetails = withContext(Dispatchers.IO) {
        val session = checkNotNull(sessions.session.value) { "No profile is signed in" }
        val dto = session.api.libraryApi.getItem(itemId = id, userId = session.userId).content
        check(dto.id == id && dto.isPhoto) { "The item is not a photo" }
        PhotoDetails(
            path = dto.path?.takeIf(String::isNotBlank),
            takenAt = dto.premiereDate,
            createdAt = dto.dateCreated,
            location = dto.photoLocation,
            width = dto.width,
            height = dto.height,
            description = plainText(dto.overview),
            camera = listOfNotNull(dto.cameraMake, dto.cameraModel)
                .filter(String::isNotBlank).distinct().joinToString(" ").takeIf(String::isNotBlank),
            focalLength = dto.focalLength,
            aperture = dto.aperture,
            exposureTime = dto.exposureTime,
            iso = dto.isoSpeedRating,
        )
    }

    /** Jellyfin's original-file endpoint returns the file's modification time as an HTTP header. */
    suspend fun photoModifiedAt(id: UUID): LocalDateTime? = withContext(Dispatchers.IO) {
        val session = sessions.session.value ?: return@withContext null
        val connection = URL(session.api.createUrl("/Items/$id/File")).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = PHOTO_DATE_TIMEOUT_MS
            connection.readTimeout = PHOTO_DATE_TIMEOUT_MS
            connection.setRequestProperty("Authorization", session.authorization())
            connection.setRequestProperty("Range", "bytes=0-0")
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK && status != HttpURLConnection.HTTP_PARTIAL) return@withContext null
            connection.lastModified.takeIf { it > 0 }?.let {
                LocalDateTime.ofInstant(Instant.ofEpochMilli(it), ZoneId.systemDefault())
            }
        } finally {
            connection.disconnect()
        }
    }

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
                dto.isVideo -> screened[dto.id]?.let { FolderMedia.Video(it, dto.taken, dto.dateCreated) }
                dto.isPhoto -> mapper.photo(dto)?.let { FolderMedia.Photo(it, dto.taken) }
                else -> null
            }
        }
    }

    private suspend fun libraries(session: Session, type: CollectionType) =
        session.api.userViewApi.getUserViews(userId = session.userId).content.items
            .filter { it.collectionType == type }

    private suspend fun children(session: Session, parentId: UUID) = session.api.libraryApi.getItems(
        userId = session.userId,
        parentId = parentId,
        sortBy = listOf(ItemSortBy.SORT_NAME),
        fields = listOf(ItemFields.DATE_CREATED, ItemFields.CAN_DELETE, ItemFields.PRODUCTION_LOCATIONS),
        enableUserData = true,
    ).content.items

    /** From the folder's parent up to the server's root. */
    private suspend fun ancestors(session: Session, folderId: UUID) =
        session.api.libraryApi.getAncestors(itemId = folderId, userId = session.userId).content

    private val BaseItemDto.isVideo: Boolean get() = isFolder != true && mediaType == MediaType.VIDEO

    private val BaseItemDto.isPhoto: Boolean get() = isFolder != true && type == BaseItemKind.PHOTO

    /**
     * Jellyfin keeps the time a photo or video was taken as its premiere date; the file's (or folder's) date otherwise.
     * The SDK hands both over in the TV's own time already.
     */
    private val BaseItemDto.taken: LocalDateTime? get() = premiereDate ?: dateCreated

    private val Session.userId: UUID get() = UUID.fromString(user.userId)

    private companion object {
        const val PHOTO_DATE_TIMEOUT_MS = 2_500
    }
}

internal val BaseItemDto.photoLocation: PhotoLocation
    get() = PhotoLocation(productionLocations.orEmpty().filter(String::isNotBlank), latitude, longitude)

internal val BaseItemDto.isDeletablePhoto: Boolean
    get() = canDelete == true && type == BaseItemKind.PHOTO && isFolder != true

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
 * Names of the folders above one, from the top: [ancestors] (parent first, up to the server's root)
 * up to the library, which counts only when there are [severalLibraries].
 */
internal fun trail(ancestors: List<BaseItemDto>, severalLibraries: Boolean): List<String> {
    val library = ancestors.indexOfFirst { it.type == BaseItemKind.COLLECTION_FOLDER }
    if (library < 0) return emptyList()
    val below = ancestors.take(if (severalLibraries) library + 1 else library)
    return below.reversed().map { it.name.orEmpty() }
}
