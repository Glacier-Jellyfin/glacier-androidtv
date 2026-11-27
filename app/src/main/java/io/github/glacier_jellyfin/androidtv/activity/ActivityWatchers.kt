package io.github.glacier_jellyfin.androidtv.activity

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import io.github.glacier_jellyfin.androidtv.core.data.SessionManager
import io.github.glacier_jellyfin.androidtv.core.data.media.ActivityEntry
import io.github.glacier_jellyfin.androidtv.core.data.media.ActivityRepository
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaItem
import io.github.glacier_jellyfin.androidtv.core.data.media.Watchers
import io.github.glacier_jellyfin.androidtv.core.data.media.buildWatchers
import io.github.glacier_jellyfin.androidtv.core.data.settings.SettingsRepository
import io.github.glacier_jellyfin.androidtv.core.designsystem.AvatarPerson
import io.github.glacier_jellyfin.androidtv.core.jellyfin.runSuspendCatching
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import org.jellyfin.sdk.model.serializer.toUUIDOrNull

/**
 * What the last activity feed says about other people, for the cards and the detail page. It follows the
 * feed whenever somebody reads it, and Settings › Activity: the person filter and the avatar switch.
 */
@Singleton
class ActivityWatchers @Inject constructor(
    private val repository: ActivityRepository,
    private val settings: SettingsRepository,
    sessions: SessionManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val self = sessions.session.map { session -> session?.user?.userId?.toUUIDOrNull() to session }

    /** The entries of the feed for this profile's session, without the profile itself. */
    private val others: StateFlow<List<ActivityEntry>> = combine(repository.latest, self) { latest, (id, session) ->
        if (latest == null || session == null || latest.key != repository.keyOf(session)) {
            emptyList()
        } else {
            latest.entries.filter { it.user.id != id }
        }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** The other people's entries after the person filter, newest first. */
    val entries: StateFlow<List<ActivityEntry>> = combine(
        others,
        settings.settings.map { it.activity.hiddenUsers }.distinctUntilChanged(),
    ) { all, hidden -> all.withoutHidden(hidden, self = null).sortedByDescending { it.timestampMs } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Who watched what, empty while "Avatars on cards" is off. */
    val cards: StateFlow<Watchers> = combine(
        others,
        settings.settings.map { it.activity }.distinctUntilChanged(),
        self,
    ) { all, activity, (id, _) ->
        if (activity.avatars) buildWatchers(all, id, activity.hiddenUsers) else Watchers.None
    }.stateIn(scope, SharingStarted.Eagerly, Watchers.None)

    /** Reads the feed (cached for a moment) so that [entries] and [cards] know it. Failures leave them as they are. */
    suspend fun refresh() {
        runSuspendCatching { repository.feed() }
    }
}

/** The people behind the avatars on cards. */
val LocalWatchers = staticCompositionLocalOf { Watchers.None }

/** The people who watched this title, as avatars; empty when nobody did or avatars on cards are off. */
@Composable
fun MediaItem.watchedBy(): List<AvatarPerson> {
    val watchers = LocalWatchers.current
    return remember(watchers, id) { watchers.of(this).map { it.toAvatar() } }
}
