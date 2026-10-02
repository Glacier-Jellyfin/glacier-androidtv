package io.github.glacier_jellyfin.androidtv.core.data.media

import io.github.glacier_jellyfin.androidtv.core.data.AccountRepository
import io.github.glacier_jellyfin.androidtv.core.data.StoredUser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.showApi
import org.jellyfin.sdk.api.client.extensions.userApi
import org.jellyfin.sdk.api.client.extensions.userViewApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.SortOrder
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** A title for the Android TV home screen. */
data class HomeScreenTitle(
    val item: MediaItem,
    /** Exact running time; null when the server does not know it. */
    val durationMs: Long?,
    /** When the profile last played it; null for titles not started. */
    val lastPlayedAt: Long?,
    /** The next episode of a show rather than a started title. */
    val nextUp: Boolean = false,
)

/** What the Android TV home screen shows of one profile. */
data class HomeScreenContent(
    val serverId: String,
    val userId: String,
    /** Started titles first, then next episodes, as on Glacier's own home screen. */
    val continueWatching: List<HomeScreenTitle>,
    /** Movies and shows added to the server last. */
    val recentlyAdded: List<HomeScreenTitle>,
    /** Movies and shows released last. */
    val recentReleases: List<HomeScreenTitle>,
)

/**
 * Content for the Android TV home screen (channels and "Watch next"). Like
 * streaming apps, it shows the profile used last, signed in or not to the app
 * right now; it runs in the background too, so it does not need a session.
 * Titles above the profile's age limit are always left out: the home screen
 * is open to everyone in front of the TV and has no PIN.
 */
@Singleton
class HomeScreenRepository @Inject constructor(
    private val jellyfin: Jellyfin,
    private val accounts: AccountRepository,
) {
    /** Null when no profile on this device is signed in. */
    suspend fun load(): HomeScreenContent? = withContext(Dispatchers.IO) {
        val state = accounts.current()
        val user = state.users.filter { it.accessToken != null }.maxByOrNull { it.lastUsedAt } ?: return@withContext null
        val server = state.servers.firstOrNull { it.id == user.serverId } ?: return@withContext null
        val api = jellyfin.createApi(baseUrl = server.address, accessToken = user.accessToken)
        val userId = UUID.fromString(user.userId)
        val mapper = MediaMapper(api)
        coroutineScope {
            val resume = async { resume(api, userId) }
            val nextUp = async { nextUp(api, userId) }
            val added = async { recentlyAdded(api, userId) }
            val releases = async { recentReleases(api, userId) }
            val started = resume.await()
            val startedIds = started.mapTo(HashSet()) { it.id }
            val continueWatching = mergeContinueWatching(started, nextUp.await())
            val recentlyAdded = added.await()
            val recentReleases = releases.await()
            val blocked = blocked(api, userId, user, (continueWatching + recentlyAdded + recentReleases).map { it.id }.distinct())
            fun titles(list: List<BaseItemDto>) = list.filterNot { it.id in blocked }
                .map { title(mapper, it, nextUp = it.type == BaseItemKind.EPISODE && it.id !in startedIds) }
            HomeScreenContent(
                serverId = server.id,
                userId = user.userId,
                continueWatching = titles(continueWatching),
                recentlyAdded = titles(recentlyAdded),
                recentReleases = titles(recentReleases),
            )
        }
    }

    private suspend fun blocked(api: ApiClient, userId: UUID, user: StoredUser, ids: List<UUID>): Set<UUID> {
        val protection = user.protection
        if (!protection.restricts || ids.isEmpty()) return emptySet()
        val maxAge = protection.maxAge.age?.let { age -> policyMaxAge(api)?.let { minOf(it, age) } ?: age }
        return blockedTitles(api, userId, maxAge, protection.blockUnrated, ids)
    }

    private fun title(mapper: MediaMapper, dto: BaseItemDto, nextUp: Boolean) = HomeScreenTitle(
        item = mapper.item(dto),
        durationMs = dto.runTimeTicks?.let { it / TICKS_PER_MS },
        lastPlayedAt = dto.userData?.lastPlayedDate?.toInstant(ZoneOffset.UTC)?.toEpochMilli(),
        nextUp = nextUp,
    )

    private suspend fun resume(api: ApiClient, userId: UUID): List<BaseItemDto> =
        api.libraryApi.getResumeItems(
            userId = userId,
            limit = LIMIT,
            mediaTypes = listOf(MediaType.VIDEO),
            fields = FIELDS,
            enableUserData = true,
        ).content.items

    private suspend fun nextUp(api: ApiClient, userId: UUID): List<BaseItemDto> =
        api.showApi.getNextUp(
            userId = userId,
            limit = LIMIT,
            fields = FIELDS,
            enableUserData = true,
            // Episodes already started are in the resume list.
            enableResumable = false,
        ).content.items

    /**
     * Movies and shows of every movie and show library the user did not leave out
     * of "latest" on the server; a show counts from its newest episode.
     */
    private suspend fun recentlyAdded(api: ApiClient, userId: UUID): List<BaseItemDto> = coroutineScope {
        val excludes = api.userApi.getCurrentUser().content.configuration?.latestItemsExcludes.orEmpty().toSet()
        val libraries = api.userViewApi.getUserViews(userId = userId).content.items
            .filter { it.id !in excludes }
            .mapNotNull { view ->
                when (view.collectionType) {
                    CollectionType.MOVIES -> Triple(view.id, BaseItemKind.MOVIE, ItemSortBy.DATE_CREATED)
                    CollectionType.TVSHOWS -> Triple(view.id, BaseItemKind.SERIES, ItemSortBy.DATE_LAST_CONTENT_ADDED)
                    else -> null
                }
            }
        libraries.map { (libraryId, type, sort) ->
            async {
                api.libraryApi.getItems(
                    userId = userId,
                    parentId = libraryId,
                    recursive = true,
                    includeItemTypes = listOf(type),
                    sortBy = listOf(sort),
                    sortOrder = listOf(SortOrder.DESCENDING),
                    fields = FIELDS + listOf(ItemFields.DATE_CREATED, ItemFields.DATE_LAST_MEDIA_ADDED),
                    enableUserData = true,
                    limit = LIMIT,
                ).content.items
            }
        }.awaitAll()
            .flatten()
            .sortedByDescending { it.dateLastMediaAdded ?: it.dateCreated }
            .distinctBy { it.id }
            .take(LIMIT)
    }

    private suspend fun recentReleases(api: ApiClient, userId: UUID): List<BaseItemDto> =
        api.libraryApi.getItems(
            userId = userId,
            recursive = true,
            includeItemTypes = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES),
            // Announced titles carry future dates.
            maxPremiereDate = LocalDateTime.now(ZoneOffset.UTC),
            sortBy = listOf(ItemSortBy.PREMIERE_DATE),
            sortOrder = listOf(SortOrder.DESCENDING),
            fields = FIELDS,
            enableUserData = true,
            limit = LIMIT,
        ).content.items

    private companion object {
        const val LIMIT = 20
        const val TICKS_PER_MS = 10_000L
        val FIELDS = listOf(ItemFields.OVERVIEW, ItemFields.GENRES)
    }
}
