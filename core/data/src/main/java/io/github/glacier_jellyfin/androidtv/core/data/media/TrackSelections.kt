package io.github.glacier_jellyfin.androidtv.core.data.media

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Audio and subtitle stream picked on a detail page; subtitle null means off. */
data class TrackSelection(val audio: Int?, val subtitle: Int?)

/**
 * Tracks picked on detail pages "for the next playback" (design). Kept for
 * the running app only: the lasting defaults are the user's Jellyfin
 * preferences, which the server already applies.
 */
@Singleton
class TrackSelections @Inject constructor() {
    private val _selections = MutableStateFlow<Map<UUID, TrackSelection>>(emptyMap())
    val selections: StateFlow<Map<UUID, TrackSelection>> = _selections.asStateFlow()

    fun set(itemId: UUID, selection: TrackSelection) = _selections.update { it + (itemId to selection) }

    fun get(itemId: UUID): TrackSelection? = _selections.value[itemId]
}
