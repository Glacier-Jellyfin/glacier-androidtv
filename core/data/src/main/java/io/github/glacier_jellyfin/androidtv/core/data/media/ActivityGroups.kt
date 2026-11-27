package io.github.glacier_jellyfin.androidtv.core.data.media

import java.util.UUID

/** A card of the activity page and the home row: a movie, an episode or a whole show. */
data class ActivityTitle(
    val key: String,
    /** The newest entry's item: an episode, movie or video. */
    val item: MediaItem,
    /** Set when this card stands for a whole show. */
    val seriesId: UUID?,
    val seriesTitle: String?,
    /** Distinct episodes behind the card, 1 for movies. */
    val episodeCount: Int,
    /** Distinct, newest first. */
    val users: List<ActivityUser>,
    val latestMs: Long,
    /** Whether the newest entry was watched to the end. */
    val completed: Boolean = true,
    /** How far the newest entry got, from 0 to 1. */
    val progress: Float = 1f,
)

/** The show an entry's item is an episode of, or null. */
private val ActivityEntry.episodeSeries: UUID?
    get() = item.seriesId.takeIf { item.kind == ItemKind.Episode }

/** A card while it is built: the episodes and users behind it. */
private class Card(
    val key: String,
    first: ActivityEntry,
    val seriesId: UUID?,
) {
    var item: MediaItem = first.item
    var latestMs: Long = first.timestampMs
    var completed: Boolean = first.completed
    var progress: Float = first.progress
    val episodes = HashSet<UUID>()
    val users = HashMap<UUID, Pair<ActivityUser, Long>>()

    fun add(entry: ActivityEntry) {
        episodes += entry.item.id
        val seen = users[entry.user.id]
        if (seen == null || seen.second < entry.timestampMs) users[entry.user.id] = entry.user to entry.timestampMs
        if (entry.timestampMs > latestMs) {
            latestMs = entry.timestampMs
            item = entry.item
            completed = entry.completed
            progress = entry.progress
        }
    }

    fun absorb(other: Card) {
        episodes += other.episodes
        other.users.forEach { (id, entry) ->
            val seen = users[id]
            if (seen == null || seen.second < entry.second) users[id] = entry
        }
        if (other.latestMs > latestMs) {
            latestMs = other.latestMs
            item = other.item
            completed = other.completed
            progress = other.progress
        }
    }

    fun toTitle() = ActivityTitle(
        key = key,
        item = item,
        seriesId = seriesId,
        seriesTitle = item.parentTitle.takeIf { seriesId != null },
        episodeCount = episodes.size.coerceAtLeast(1),
        users = users.values.sortedByDescending { it.second }.map { it.first },
        latestMs = latestMs,
        completed = completed,
        progress = progress,
    )
}

/** What others watched, as cards for the home row: [watchedTitles] without the entries of [self]. Newest first. */
fun watchedByOthers(
    entries: List<ActivityEntry>,
    self: UUID,
    limit: Int = 20,
): List<ActivityTitle> = watchedTitles(entries.filter { it.user.id != self }, limit)

/**
 * One card per title that [entries] watched. A user who watched two or more
 * episodes of a show gives one show card, one episode stays an episode card.
 * Cards of several users with the same key merge, and an episode card is folded
 * into the card of its show when there is one. Newest first.
 */
fun watchedTitles(entries: List<ActivityEntry>, limit: Int = Int.MAX_VALUE): List<ActivityTitle> {
    val watched = entries.filter { it.type == ActivityType.Watched }
    val cards = LinkedHashMap<String, Card>()
    watched.groupBy { it.user.id }.values.forEach { own ->
        val bySeries = own.filter { it.episodeSeries != null }.groupBy { it.episodeSeries }
        val collapsed = bySeries.filterValues { group -> group.map { it.item.id }.distinct().size >= 2 }.keys
        own.forEach { entry ->
            val series = entry.episodeSeries
            val asSeries = series != null && series in collapsed
            val key = if (asSeries) "series:$series" else "item:${entry.item.id}"
            cards.getOrPut(key) { Card(key, entry, series.takeIf { asSeries }) }.add(entry)
        }
    }
    val seriesCards = cards.values.filter { it.seriesId != null }.associateBy { it.seriesId }
    val kept = cards.values.filter { card ->
        val parent = if (card.seriesId == null) card.item.seriesId?.let { seriesCards[it] } else null
        parent?.absorb(card)
        parent == null
    }
    return kept.map { it.toTitle() }.sortedByDescending { it.latestMs }.take(limit)
}

/** One card per title that [entries] favorited, with everyone who did, newest first. Favorites never collapse. */
fun favoritedTitles(entries: List<ActivityEntry>): List<ActivityTitle> =
    entries.filter { it.type == ActivityType.Favorited }
        .groupBy { it.item.id }
        .values
        .map { group -> singleTitle(group) }
        .sortedByDescending { it.latestMs }

/** One card for the entries of one item, never collapsed into a show. */
private fun singleTitle(group: List<ActivityEntry>): ActivityTitle {
    val card = Card("item:${group.first().item.id}", group.first(), null)
    group.forEach(card::add)
    return card.toTitle()
}

/** A person of the feed and when they were last active. */
data class ActivityPerson(val user: ActivityUser, val lastActiveMs: Long)

/** The distinct people with their newest entry, the most recently active first. */
fun activityPeople(entries: List<ActivityEntry>): List<ActivityPerson> = entries.groupBy { it.user.id }
    .values
    .map { own ->
        val newest = own.maxBy { it.timestampMs }
        ActivityPerson(newest.user, newest.timestampMs)
    }
    .sortedByDescending { it.lastActiveMs }

/** What one person did, for their page. The counts are distinct titles of the loaded feed. */
data class PersonProfile(
    val person: ActivityPerson,
    /** Distinct titles as the rows show them: a show with several episodes counts once. */
    val watchedCount: Int,
    val favoriteCount: Int,
    /** Started and not finished, with their progress. */
    val watching: List<ActivityTitle>,
    /** Finished, the episodes of a show collapsed. */
    val watched: List<ActivityTitle>,
    val favorites: List<ActivityTitle>,
)

/** The profile of [userId] in [entries], or null when they have no entry. */
fun personProfile(entries: List<ActivityEntry>, userId: UUID): PersonProfile? {
    val own = entries.filter { it.user.id == userId }
    val person = activityPeople(own).firstOrNull() ?: return null
    val newestWatches = own.filter { it.type == ActivityType.Watched }
        .groupBy { it.item.id }
        .values
        .map { group -> group.maxBy { it.timestampMs } }
    val (finished, started) = newestWatches.partition { it.completed }
    val favorites = favoritedTitles(own)
    return PersonProfile(
        person = person,
        // A show counts once however many episodes, and a title being watched and watched before once too.
        watchedCount = newestWatches.map { it.episodeSeries ?: it.item.id }.distinct().size,
        favoriteCount = favorites.size,
        watching = started.sortedByDescending { it.timestampMs }.map { singleTitle(listOf(it)) },
        watched = watchedTitles(finished),
        favorites = favorites,
    )
}

/** The distinct users, in the order of their newest entry. */
fun people(entries: List<ActivityEntry>): List<ActivityUser> =
    entries.sortedByDescending { it.timestampMs }.map { it.user }.distinctBy { it.id }
