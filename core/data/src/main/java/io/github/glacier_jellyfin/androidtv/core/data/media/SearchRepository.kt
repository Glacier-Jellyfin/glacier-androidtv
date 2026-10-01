package io.github.glacier_jellyfin.androidtv.core.data.media

import io.github.glacier_jellyfin.androidtv.core.data.Session
import io.github.glacier_jellyfin.androidtv.core.data.SessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.extensions.genreApi
import org.jellyfin.sdk.api.client.extensions.libraryApi
import org.jellyfin.sdk.api.client.extensions.personApi
import org.jellyfin.sdk.api.client.extensions.suggestionApi
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Search as the design describes it ("title, genre, person"): titles whose
 * name matches, then titles with a matching person, then with a matching genre.
 */
@Singleton
class SearchRepository @Inject constructor(
    private val sessions: SessionManager,
    private val ageFilter: AgeFilter,
) {

    suspend fun search(query: String): List<MediaItem> = withContext(Dispatchers.IO) {
        val term = query.trim()
        if (term.isEmpty()) return@withContext emptyList()
        val session = requireSession()
        val mapper = MediaMapper(session.api)
        coroutineScope {
            val titles = async { items(session) { copy(searchTerm = term) } }
            val byPerson = async {
                val people = session.api.personApi.getPersons(searchTerm = term, userId = session.userId, limit = MATCH_LIMIT)
                    .content.items.map { it.id }
                if (people.isEmpty()) emptyList() else items(session) { copy(personIds = people) }
            }
            val byGenre = async {
                val genres = session.api.genreApi.getGenres(searchTerm = term, userId = session.userId, limit = MATCH_LIMIT)
                    .content.items.map { it.id }
                if (genres.isEmpty()) emptyList() else items(session) { copy(genreIds = genres) }
            }
            ageFilter.screen(mergeResults(titles.await(), byPerson.await(), byGenre.await(), limit = RESULT_LIMIT) { it.id }.map(mapper::item))
        }
    }

    /** Shown before anything is typed ("Suggestions"). */
    suspend fun suggestions(): List<MediaItem> = withContext(Dispatchers.IO) {
        val session = requireSession()
        val mapper = MediaMapper(session.api)
        session.api.suggestionApi.getSuggestions(
            userId = session.userId,
            type = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES),
            limit = SUGGESTION_LIMIT,
        ).content.items.map(mapper::item).let { ageFilter.screen(it) }
    }

    private data class ItemFilter(
        val searchTerm: String? = null,
        val personIds: List<UUID> = emptyList(),
        val genreIds: List<UUID> = emptyList(),
    )

    private suspend fun items(session: Session, filter: ItemFilter.() -> ItemFilter) =
        ItemFilter().filter().let { f ->
            session.api.libraryApi.getItems(
                userId = session.userId,
                recursive = true,
                searchTerm = f.searchTerm,
                personIds = f.personIds,
                genreIds = f.genreIds,
                includeItemTypes = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES, BaseItemKind.MUSIC_ALBUM),
                fields = listOf(ItemFields.GENRES, ItemFields.SORT_NAME),
                enableUserData = true,
                limit = RESULT_LIMIT,
                // Title hits keep the server's relevance order; the others go newest first.
                sortBy = if (f.searchTerm != null) emptyList() else listOf(ItemSortBy.PRODUCTION_YEAR, ItemSortBy.SORT_NAME),
                sortOrder = if (f.searchTerm != null) emptyList() else listOf(SortOrder.DESCENDING),
            ).content.items
        }

    private fun requireSession(): Session = checkNotNull(sessions.session.value) { "No profile is signed in" }

    private val Session.userId: UUID get() = UUID.fromString(user.userId)

    private companion object {
        const val RESULT_LIMIT = 48
        const val SUGGESTION_LIMIT = 8
        /** People and genres whose titles are added to the results. */
        const val MATCH_LIMIT = 3
    }
}

/** Concatenates result groups in order, dropping repeats, up to [limit] entries. */
internal fun <T, K> mergeResults(vararg groups: List<T>, limit: Int, key: (T) -> K): List<T> {
    val seen = HashSet<K>()
    val merged = ArrayList<T>()
    for (group in groups) {
        for (item in group) {
            if (merged.size >= limit) return merged
            if (seen.add(key(item))) merged += item
        }
    }
    return merged
}
