package io.github.glacier_jellyfin.androidtv.core.data.media

import io.github.glacier_jellyfin.androidtv.core.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The design's one shuffle switch, shared by the music pages and the player,
 * and the player's lyrics switch; both kept per profile (MusicSettings).
 */
@Singleton
class MusicShuffle @Inject constructor(
    private val settings: SettingsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val on: StateFlow<Boolean> = settings.settings.map { it.music.shuffle }
        .stateIn(scope, SharingStarted.Eagerly, settings.settings.value.music.shuffle)

    val lyrics: StateFlow<Boolean> = settings.settings.map { it.music.lyrics }
        .stateIn(scope, SharingStarted.Eagerly, settings.settings.value.music.lyrics)

    fun set(on: Boolean) {
        scope.launch { settings.update { it.copy(music = it.music.copy(shuffle = on)) } }
    }

    fun setLyrics(on: Boolean) {
        scope.launch { settings.update { it.copy(music = it.music.copy(lyrics = on)) } }
    }
}
