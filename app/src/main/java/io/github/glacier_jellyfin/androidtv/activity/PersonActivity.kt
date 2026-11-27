package io.github.glacier_jellyfin.androidtv.activity

import io.github.glacier_jellyfin.androidtv.core.data.media.ActivityEntry
import io.github.glacier_jellyfin.androidtv.core.data.media.ActivityType
import io.github.glacier_jellyfin.androidtv.core.data.media.ActivityUser
import java.util.UUID

/** What one person did with one title: when last, whether they favorited it and how far they watched. */
data class PersonActivity(
    val user: ActivityUser,
    /** Their newest entry of any kind. */
    val timestampMs: Long,
    val favorite: Boolean,
    /** From 0 to 1; null when they only favorited it. */
    val progress: Float?,
    val completed: Boolean,
)

/** The status line of a person's card. */
sealed interface PersonStatus {
    data object Favorite : PersonStatus

    data object Watched : PersonStatus

    data class Percent(val percent: Int) : PersonStatus
}

/**
 * The people in [entries] who did something with the title [itemId], newest first. [entries] holds the other people's
 * entries after the person filter. Watching and favoriting of one person fold into one card.
 */
fun personActivities(entries: List<ActivityEntry>, itemId: UUID): List<PersonActivity> =
    entries.filter { it.item.id == itemId }
        .groupBy { it.user.id }
        .values
        .map { own ->
            val newestWatch = own.filter { it.type == ActivityType.Watched }.maxByOrNull { it.timestampMs }
            PersonActivity(
                user = own.first().user,
                timestampMs = own.maxOf { it.timestampMs },
                favorite = own.any { it.type == ActivityType.Favorited },
                progress = newestWatch?.progress,
                completed = newestWatch?.completed == true,
            )
        }
        .sortedByDescending { it.timestampMs }

/** Watched to the end, how many percent, or a favorite that was never watched. */
fun statusOf(activity: PersonActivity): PersonStatus {
    val progress = activity.progress
    return when {
        activity.completed -> PersonStatus.Watched
        progress != null -> PersonStatus.Percent((progress * PERCENT).toInt().coerceIn(0, PERCENT))
        else -> PersonStatus.Favorite
    }
}

/** The bar of a card: full once watched to the end, else how far; null for a favorite that was never watched. */
fun barOf(activity: PersonActivity): Float? = when {
    activity.completed -> 1f
    else -> activity.progress?.coerceIn(0f, 1f)
}

private const val PERCENT = 100
