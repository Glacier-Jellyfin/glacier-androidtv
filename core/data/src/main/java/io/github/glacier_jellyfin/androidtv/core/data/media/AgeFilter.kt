package io.github.glacier_jellyfin.androidtv.core.data.media

import android.util.Log
import io.github.glacier_jellyfin.androidtv.core.data.ParentalControl
import io.github.glacier_jellyfin.androidtv.core.data.Protection
import io.github.glacier_jellyfin.androidtv.core.data.Session
import io.github.glacier_jellyfin.androidtv.core.data.SessionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.userApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Server filters for paged lists that leave out titles above the age limit. */
data class AgeLimits(val maxOfficialRating: String?, val hasOfficialRating: Boolean?)

/**
 * The profile's age limit ([Protection]) applied to titles. Whether a title is
 * above the limit is asked of the server, which knows every country's rating
 * system and lets episodes inherit the rating of their show. Titles without a
 * rating are recognised here: the server's own filter for them does not work
 * next to a parental control policy.
 *
 * Titles above the limit are hidden, or, with "PIN for locked titles", stay
 * visible and ask for the PIN when opened.
 */
@Singleton
class AgeFilter @Inject constructor(
    private val sessions: SessionManager,
    private val parental: ParentalControl,
) {
    private val mutex = Mutex()
    private var cacheKey: Any? = null
    private val blockedCache = HashMap<UUID, Boolean>()
    private var policyUser: String? = null
    private var policyMaxAge: Int? = null

    /** Titles above the limit are left out of lists (no PIN to unlock them). */
    val hides: Boolean
        get() = parental.lock.value.let { it.protection.restricts && !(it.protection.pinForLocked && it.hasPin) }

    /** [items] without the titles above the limit when [hides]; unchanged otherwise. */
    suspend fun visible(items: List<MediaItem>): List<MediaItem> {
        if (!hides || items.none { it.kind in Checked }) return items
        val blocked = blocked(items.filter { it.kind in Checked }.map { it.id })
        return items.filterNot { it.id in blocked }
    }

    /** Filters for a paged server query; null when nothing is hidden. */
    suspend fun limits(): AgeLimits? {
        if (!hides) return null
        val session = sessions.session.value ?: return null
        val protection = parental.lock.value.protection
        return AgeLimits(
            maxOfficialRating = mutex.withLock { effectiveAge(session, protection) }?.toString(),
            // Close enough for movies and shows, which carry their own rating.
            hasOfficialRating = true.takeIf { protection.blockUnrated },
        )
    }

    /**
     * The title opens only with the PIN (or not at all while [hides]). Titles
     * unlocked in this session are not, nor episodes of an unlocked show ([parents]).
     */
    suspend fun isLocked(id: UUID, parents: Collection<UUID> = emptyList()): Boolean {
        if (!parental.lock.value.protection.restricts) return false
        val unlocked = parental.unlockedItems.value
        if ((parents + id).any { it.toString() in unlocked }) return false
        return id in blocked(listOf(id))
    }

    private suspend fun blocked(ids: List<UUID>): Set<UUID> = withContext(Dispatchers.IO) {
        val session = sessions.session.value ?: return@withContext emptySet()
        val protection = parental.lock.value.protection
        if (!protection.restricts) return@withContext emptySet()
        mutex.withLock {
            val key = Triple(session.server.id, session.user.userId, protection)
            if (key != cacheKey) {
                cacheKey = key
                blockedCache.clear()
            }
            val unknown = ids.filterNot { it in blockedCache }.distinct()
            if (unknown.isNotEmpty()) {
                try {
                    val newlyBlocked = query(session, protection, unknown)
                    unknown.forEach { blockedCache[it] = it in newlyBlocked }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Failing closed would empty every row while the server is away; the next load asks again.
                    Log.w(TAG, "Checking age ratings failed", e)
                    return@withContext emptySet()
                }
            }
            ids.filterTo(HashSet()) { blockedCache[it] == true }
        }
    }

    private suspend fun query(session: Session, protection: Protection, ids: List<UUID>): Set<UUID> {
        val userId = UUID.fromString(session.user.userId)
        val maxRating = effectiveAge(session, protection)?.toString()
        val allowed = ids.chunked(CHUNK).flatMap { chunk ->
            session.api.libraryApi.getItems(
                userId = userId,
                ids = chunk,
                recursive = true,
                maxOfficialRating = maxRating,
                enableUserData = false,
                enableImages = false,
            ).content.items
        }
        val rated = if (protection.blockUnrated) allowed.filterRated(session, userId) else allowed
        return ids.toSet() - rated.mapTo(HashSet()) { it.id }
    }

    /** Episodes without a rating of their own take their show's. */
    private suspend fun List<BaseItemDto>.filterRated(session: Session, userId: UUID): List<BaseItemDto> {
        val seriesIds = filter { !isRated(it.officialRating) && it.type == BaseItemKind.EPISODE }.mapNotNull { it.seriesId }.distinct()
        val seriesRated = if (seriesIds.isEmpty()) {
            emptySet()
        } else {
            session.api.libraryApi.getItems(userId = userId, ids = seriesIds, recursive = true, enableUserData = false, enableImages = false)
                .content.items.filter { isRated(it.officialRating) }.mapTo(HashSet()) { it.id }
        }
        return filter { isRated(it.officialRating) || it.seriesId in seriesRated }
    }

    /**
     * Call with [mutex] held. The profile's limit, but never above the one of the user's server policy:
     * the server takes the query's value in place of the policy's.
     */
    private suspend fun effectiveAge(session: Session, protection: Protection): Int? {
        val age = protection.maxAge.age ?: return null
        if (policyUser != session.user.userId) {
            policyMaxAge = session.api.userApi.getCurrentUser().content.policy?.maxParentalRating
            policyUser = session.user.userId
        }
        return policyMaxAge?.let { minOf(it, age) } ?: age
    }

    companion object {
        private const val TAG = "AgeFilter"
        private const val CHUNK = 100

        /** Kinds the age limit applies to; music, collections and the like are never locked. */
        val Checked = setOf(ItemKind.Movie, ItemKind.Series, ItemKind.Episode)

        /** The server's markers for "no rating" (LocalizationManager). */
        private val Unrated = setOf("n/a", "unrated", "not rated", "nr")

        fun isRated(rating: String?): Boolean = !rating.isNullOrBlank() && rating.trim().lowercase() !in Unrated
    }
}
