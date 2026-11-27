package io.github.glacier_jellyfin.androidtv.core.data.media

import java.util.UUID

/**
 * Who watched what, for the avatars on cards: users per title and per show, newest first.
 * Built from the activity feed by [buildWatchers].
 */
data class Watchers(
    /** Movies, episodes and videos by their own id. */
    val byItem: Map<UUID, List<ActivityUser>> = emptyMap(),
    /** Shows by their id, with everyone who watched any episode. */
    val bySeries: Map<UUID, List<ActivityUser>> = emptyMap(),
) {
    /** The people who watched [item], or of a show any of its episodes. Empty for anything else. */
    fun of(item: MediaItem): List<ActivityUser> = when (item.kind) {
        ItemKind.Series -> bySeries[item.id]
        ItemKind.Movie, ItemKind.Episode, ItemKind.Other -> byItem[item.id]
        else -> null
    }.orEmpty()

    companion object {
        val None = Watchers()
    }
}

/**
 * The watchers of [entries]. Favorites do not count, nor does the profile itself ([self]) or anyone in
 * [hidden] (user ids, from Settings › Activity).
 */
fun buildWatchers(
    entries: List<ActivityEntry>,
    self: UUID?,
    hidden: Set<String> = emptySet(),
): Watchers {
    val watched = entries
        .filter { it.type == ActivityType.Watched && it.user.id != self && it.user.id.toString() !in hidden }
        .sortedByDescending { it.timestampMs }
    if (watched.isEmpty()) return Watchers.None
    return Watchers(
        byItem = watched.groupBy { it.item.id }.mapValues { (_, list) -> list.map { it.user }.distinctBy { it.id } },
        bySeries = watched
            .mapNotNull { entry -> entry.item.seriesId?.takeIf { entry.item.kind == ItemKind.Episode }?.to(entry.user) }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, users) -> users.distinctBy { it.id } },
    )
}
