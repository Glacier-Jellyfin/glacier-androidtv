package io.github.glacier_jellyfin.androidtv.core.data.media

import io.github.glacier_jellyfin.androidtv.core.data.settings.SpotlightType
import java.time.LocalDateTime
import java.util.UUID
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryMergeTest {

    private fun view(id: UUID, type: CollectionType) =
        BaseItemDto(id = id, type = BaseItemKind.COLLECTION_FOLDER, collectionType = type)

    private fun genre(
        id: UUID,
        movies: Int? = null,
        series: Int? = null,
    ) = BaseItemDto(id = id, type = BaseItemKind.GENRE, movieCount = movies, seriesCount = series)

    @Test
    fun `two movie libraries and a recording folder give only the two`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val recordings = UUID.randomUUID()
        val shows = UUID.randomUUID()
        val views = listOf(
            view(a, CollectionType.MOVIES),
            view(recordings, CollectionType.MOVIES),
            view(shows, CollectionType.TVSHOWS),
            view(b, CollectionType.MOVIES),
        )
        assertEquals(listOf(a, b), libraryIdsOf(views, LibraryKind.Movies, setOf(recordings)))
        assertEquals(listOf(shows), libraryIdsOf(views, LibraryKind.Shows, setOf(recordings)))
    }

    @Test
    fun `no library of the kind gives none`() {
        val views = listOf(view(UUID.randomUUID(), CollectionType.MUSIC))
        assertTrue(libraryIdsOf(views, LibraryKind.Movies, emptySet()).isEmpty())
        val only = UUID.randomUUID()
        assertTrue(libraryIdsOf(listOf(view(only, CollectionType.MOVIES)), LibraryKind.Movies, setOf(only)).isEmpty())
    }

    @Test
    fun `a library id in the query stays the only one`() {
        val id = UUID.randomUUID()
        val query = LibraryQuery(LibraryKind.Movies, libraryId = id)
        assertEquals(listOf<UUID?>(id), query.parents { error("not asked") })
    }

    @Test
    fun `the tab over all libraries asks every library`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val query = LibraryQuery(LibraryKind.Shows)
        assertEquals(listOf<UUID?>(a, b), query.parents { listOf(a, b) })
        assertTrue(query.parents { emptyList() }.isEmpty())
    }

    @Test
    fun `collections and music are asked server wide`() {
        val collections = LibraryQuery(LibraryKind.Movies, scope = LibraryScope.Collections)
        assertEquals(listOf<UUID?>(null), collections.parents { error("not asked") })
        assertEquals(listOf<UUID?>(null), LibraryQuery(LibraryKind.Music).parents { error("not asked") })
    }

    @Test
    fun `genres of several libraries are one card with summed counts`() {
        val action = UUID.randomUUID()
        val drama = UUID.randomUUID()
        val merged = mergeGenres(
            listOf(genre(action, movies = 3), genre(drama, movies = 1, series = 2), genre(action, movies = 4)),
        )
        assertEquals(listOf(action, drama), merged.map { it.id })
        assertEquals(7, merged[0].movieCount)
        assertNull(merged[0].seriesCount)
        assertEquals(1, merged[1].movieCount)
        assertEquals(2, merged[1].seriesCount)
    }

    private fun title(
        id: UUID = UUID.randomUUID(),
        created: LocalDateTime? = null,
        name: String? = null,
    ) = BaseItemDto(id = id, type = BaseItemKind.MOVIE, dateCreated = created, name = name, sortName = name)

    private val day = LocalDateTime.of(2026, 1, 1, 0, 0)

    @Test
    fun `libraries per kind skip the recording folders and carry their kind`() {
        val movies = UUID.randomUUID()
        val shows = UUID.randomUUID()
        val recordings = UUID.randomUUID()
        val music = UUID.randomUUID()
        val views = listOf(
            view(movies, CollectionType.MOVIES),
            view(recordings, CollectionType.TVSHOWS),
            view(music, CollectionType.MUSIC),
            view(shows, CollectionType.TVSHOWS),
        )
        val both = setOf(LibraryKind.Movies, LibraryKind.Shows)
        assertEquals(
            listOf(KindLibrary(movies, LibraryKind.Movies), KindLibrary(shows, LibraryKind.Shows)),
            libraryKindsOf(views, both, setOf(recordings)),
        )
        assertEquals(
            listOf(KindLibrary(shows, LibraryKind.Shows)),
            libraryKindsOf(views, setOf(LibraryKind.Shows), setOf(recordings)),
        )
        assertEquals(BaseItemKind.MOVIE, KindLibrary(movies, LibraryKind.Movies).itemType)
        assertEquals(BaseItemKind.SERIES, KindLibrary(shows, LibraryKind.Shows).itemType)
    }

    @Test
    fun `a spotlight type draws from its library kinds`() {
        assertEquals(setOf(LibraryKind.Movies, LibraryKind.Shows), SpotlightType.All.libraryKinds)
        assertEquals(setOf(LibraryKind.Movies), SpotlightType.Movies.libraryKinds)
        assertEquals(setOf(LibraryKind.Shows), SpotlightType.Shows.libraryKinds)
    }

    @Test
    fun `limited results are sorted across libraries, distinct and cut`() {
        val shared = UUID.randomUUID()
        val a = listOf(title(created = day.plusDays(5)), title(shared, day.plusDays(3)), title(created = day))
        val b = listOf(title(created = day.plusDays(4)), title(shared, day.plusDays(3)), title(created = null))
        val merged = mergeLimited(listOf(a, b), 4, byDateCreatedDesc)
        assertEquals(4, merged.size)
        assertEquals(listOf(day.plusDays(5), day.plusDays(4), day.plusDays(3), day), merged.map { it.dateCreated })
        assertEquals(1, merged.count { it.id == shared })
    }

    @Test
    fun `titles sort by sort name ignoring case`() {
        val results = listOf(listOf(title(name = "b"), title(name = "C")), listOf(title(name = "A")))
        val merged = mergeLimited(results, 9, bySortName)
        assertEquals(listOf("A", "b", "C"), merged.map { it.name })
    }

    @Test
    fun `shuffled results hold the limit of distinct titles from the inputs`() {
        val shared = UUID.randomUUID()
        val a = List(6) { title() } + title(shared)
        val b = List(6) { title() } + title(shared)
        val inputs = (a + b).map { it.id }.toSet()
        val merged = mergeShuffled(listOf(a, b), 5, Random(42))
        assertEquals(5, merged.size)
        assertEquals(5, merged.map { it.id }.toSet().size)
        assertTrue(merged.all { it.id in inputs })
    }

    @Test
    fun `no library of the kind asks nothing`() = runBlocking {
        val result = perLibrary<BaseItemDto>(emptyList()) { error("not asked") }
        assertTrue(result.isEmpty())
        assertTrue(mergeLimited(result, 5, byDateCreatedDesc).isEmpty())
    }

    @Test
    fun `every library is asked once`() = runBlocking {
        val libraries = listOf(
            KindLibrary(UUID.randomUUID(), LibraryKind.Movies),
            KindLibrary(UUID.randomUUID(), LibraryKind.Shows),
        )
        val asked = perLibrary(libraries) { listOf(title(it.id)) }
        assertEquals(libraries.map { it.id }, asked.flatten().map { it.id })
    }
}
