package io.github.glacier_jellyfin.androidtv.core.data.media

import java.util.UUID

/**
 * The people to offer in "Show activity from": those of the feed first, then hidden ones that are no longer in the
 * feed, from their stored [hiddenNames] (id to name), so they can be switched on again. They have no image, so the UI
 * draws their initials. Hidden ids without a stored name or that are not a user id are left out.
 */
fun mergeActivityPeople(
    feed: List<ActivityUser>,
    hidden: Set<String>,
    hiddenNames: Map<String, String>,
): List<ActivityUser> {
    val inFeed = feed.mapTo(HashSet()) { it.id.toString() }
    val stored = hidden.mapNotNull { id ->
        val name = hiddenNames[id]?.takeIf { it.isNotBlank() }
        val uuid = runCatching { UUID.fromString(id) }.getOrNull()
        if (name == null || uuid == null || id in inFeed) null else ActivityUser(uuid, name, imageUrl = null)
    }
    return feed + stored.sortedBy { it.name.lowercase() }
}
