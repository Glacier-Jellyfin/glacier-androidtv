package io.github.glacier_jellyfin.androidtv.core.data.media

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** The design's one shuffle switch, shared by the music pages and the player for this app session. */
@Singleton
class MusicShuffle @Inject constructor() {

    private val _on = MutableStateFlow(false)
    val on: StateFlow<Boolean> = _on.asStateFlow()

    fun set(on: Boolean) {
        _on.value = on
    }
}
