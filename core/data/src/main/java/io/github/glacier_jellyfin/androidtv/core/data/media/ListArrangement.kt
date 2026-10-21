package io.github.glacier_jellyfin.androidtv.core.data.media

import java.util.UUID

/**
 * A title of a library list with what the sorts need: the name it is sorted under (see
 * [NameOrder.title]) and the values the other sorts and their groups go by.
 */
class IndexEntry(
    val id: UUID,
    val name: String,
    /** The month it was added, as year * 12 + month - 1. */
    val addedMonth: Int? = null,
    val year: Int? = null,
    val rating: Float? = null,
    val runtimeMs: Long? = null,
    /** Album artist of an album, first artist of a song. */
    val artist: String? = null,
    val albumId: String? = null,
    val album: String? = null,
    val disc: Int? = null,
    val track: Int? = null,
)

/**
 * The heading over a part of a library list sorted by something else than the title. The list
 * goes group by group in the chosen order, and A to Z inside a group.
 */
sealed interface ListGroup {
    /** Added in that month (1 to 12). */
    data class Month(val year: Int, val month: Int) : ListGroup
    data class Year(val year: Int) : ListGroup
    /** A community rating from [stars] up to just below the next whole star. */
    data class Stars(val stars: Int) : ListGroup
    /** A length from [fromMinutes] up to just below [toMinutes]. */
    data class Length(val fromMinutes: Int, val toMinutes: Int) : ListGroup
    /** An artist or an album. Albums of the same name stay apart by [id]. */
    data class Name(val id: String, val name: String) : ListGroup
    /** Without a value for the sort: always last. */
    data object Unknown : ListGroup
}

/** A list in the order the grid shows it, with the group of each title when it is grouped. */
class ArrangedList(val entries: List<IndexEntry>, val groups: List<ListGroup>?, private val letters: List<Char?>?) {

    val size: Int get() = entries.size

    /** The A–Z rail letter the title at [index] is filed under; null without a rail. */
    fun letterAt(index: Int): Char? = letters?.get(index)

    /**
     * The start of the row that holds [index] in a grid of [columns]: each group starts a new
     * row. Pages that start there keep the rows as they are when more is loaded before them.
     */
    fun rowStart(index: Int, columns: Int): Int {
        val at = index.coerceIn(0, (size - 1).coerceAtLeast(0))
        var groupStart = 0
        if (groups != null && at < size) {
            groupStart = at
            val group = groups[at]
            while (groupStart > 0 && groups[groupStart - 1] == group) groupStart--
        }
        return groupStart + (at - groupStart) / columns * columns
    }
}

object ListArrangement {

    /**
     * Puts [entries] (in any order) in the order of [query]. By title they go by name, turned round
     * for Z to A. Otherwise they go group by group (turned round for descending, the titles without
     * a value last) and by name inside a group, songs of an album by disc and track.
     */
    fun arrange(entries: List<IndexEntry>, query: LibraryQuery): ArrangedList {
        val keys = HashMap<UUID, String>(entries.size * 2)
        entries.forEach { keys[it.id] = NameOrder.key(it.name) }
        val byName = Comparator<IndexEntry> { a, b ->
            NameOrder.compareKeys(keys.getValue(a.id), keys.getValue(b.id)).takeIf { it != 0 } ?: a.name.compareTo(b.name)
        }
        if (!query.grouped) {
            val sorted = entries.sortedWith(byName).let { if (query.orderDescending) it.asReversed() else it }
            return ArrangedList(sorted, null, if (query.railed) sorted.map { railLetter(it.name) } else null)
        }
        val groupOf = HashMap<UUID, ListGroup>(entries.size * 2)
        entries.forEach { groupOf[it.id] = group(it, query.sort) }
        val nameKeys = HashMap<ListGroup, String>()
        val byGroup = Comparator<ListGroup> { a, b ->
            when {
                a == b -> 0
                a == ListGroup.Unknown -> 1
                b == ListGroup.Unknown -> -1
                else -> {
                    val order = when {
                        a is ListGroup.Name && b is ListGroup.Name -> {
                            val ka = nameKeys.getOrPut(a) { NameOrder.key(a.name) }
                            val kb = nameKeys.getOrPut(b) { NameOrder.key(b.name) }
                            NameOrder.compareKeys(ka, kb).takeIf { it != 0 } ?: a.name.compareTo(b.name).takeIf { it != 0 } ?: a.id.compareTo(b.id)
                        }
                        else -> rank(a).compareTo(rank(b))
                    }
                    if (query.orderDescending) -order else order
                }
            }
        }
        val inGroup = if (query.sort == LibrarySort.Album) {
            compareBy<IndexEntry, Int?>(nullsLast()) { it.disc }.thenBy(nullsLast()) { it.track }.then(byName)
        } else byName
        val sorted = entries.sortedWith { a, b ->
            byGroup.compare(groupOf.getValue(a.id), groupOf.getValue(b.id)).takeIf { it != 0 } ?: inGroup.compare(a, b)
        }
        val groups = sorted.map { groupOf.getValue(it.id) }
        val letters = if (query.railed) groups.map { (it as? ListGroup.Name)?.let { name -> railLetter(name.name) } } else null
        return ArrangedList(sorted, groups, letters)
    }

    /** The group [entry] falls in when sorted by [sort]. */
    fun group(entry: IndexEntry, sort: LibrarySort): ListGroup = when (sort) {
        LibrarySort.DateAdded -> entry.addedMonth?.let { ListGroup.Month(it / 12, it % 12 + 1) }
        LibrarySort.Year -> entry.year?.let { ListGroup.Year(it) }
        LibrarySort.Rating -> entry.rating?.let { ListGroup.Stars(it.toInt()) }
        LibrarySort.Runtime -> entry.runtimeMs?.takeIf { it > 0 }?.let { length(it) }
        LibrarySort.Artist -> entry.artist?.takeIf { it.isNotBlank() }?.let { ListGroup.Name(NameOrder.key(it), it) }
        LibrarySort.Album -> entry.album?.takeIf { it.isNotBlank() }?.let { ListGroup.Name(entry.albumId ?: NameOrder.key(it), it) }
        LibrarySort.Title -> null
    } ?: ListGroup.Unknown

    /**
     * Steps that fit songs, albums and films alike: by the minute below ten minutes, by ten
     * minutes below an hour, by half an hour above.
     */
    private fun length(ms: Long): ListGroup.Length {
        val minutes = (ms / 60_000).toInt()
        val step = when {
            minutes < 10 -> 1
            minutes < 60 -> 10
            else -> 30
        }
        val from = minutes / step * step
        return ListGroup.Length(from, from + step)
    }

    private fun rank(group: ListGroup): Long = when (group) {
        is ListGroup.Month -> group.year * 12L + group.month
        is ListGroup.Year -> group.year.toLong()
        is ListGroup.Stars -> group.stars.toLong()
        is ListGroup.Length -> group.fromMinutes.toLong()
        is ListGroup.Name, ListGroup.Unknown -> 0
    }
}
