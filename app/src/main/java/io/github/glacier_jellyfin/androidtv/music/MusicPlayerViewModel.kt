package io.github.glacier_jellyfin.androidtv.music

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.glacier_jellyfin.androidtv.navigation.MusicRoute
import io.github.glacier_jellyfin.androidtv.ui.UiEvent
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import java.util.UUID
import javax.inject.Inject

/**
 * The full-screen player, a view on [MusicController]: closing it leaves the
 * music playing.
 */
@HiltViewModel
class MusicPlayerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val controller: MusicController,
) : ViewModel() {

    val state: StateFlow<MusicUiState> = controller.state
    val progress: StateFlow<MusicProgress> = controller.progress
    val events: Flow<UiEvent> = controller.events

    /** Fires when the screen should close. */
    private val _finished = Channel<Unit>(Channel.CONFLATED)
    val finished = _finished.receiveAsFlow()

    private var closing = false

    init {
        controller.screenShown()
        val route = savedStateHandle.toRoute<MusicRoute>()
        when {
            // Once per route: coming back to this screen (or recreating it) keeps the queue that plays.
            route.sourceId != null && savedStateHandle.get<Boolean>(KEY_STARTED) != true -> {
                savedStateHandle[KEY_STARTED] = true
                controller.play(UUID.fromString(route.sourceId), route.startTrackId)
            }
            // Opened from the mini player after the music ended.
            controller.player.value == null && !controller.state.value.loading -> close()
        }
    }

    fun load() = controller.retry()
    fun togglePlay() = controller.togglePlay()
    fun next() = controller.next()
    fun previous() = controller.previous()
    fun seekBy(deltaMs: Long) = controller.seekBy(deltaMs)
    fun seekTo(positionMs: Long) = controller.seekTo(positionMs)
    fun playAt(index: Int) = controller.playAt(index)
    fun toggleShuffle() = controller.toggleShuffle()
    fun cycleRepeat() = controller.cycleRepeat()
    fun toggleLyrics() = controller.toggleLyrics()

    /** Back: the screen goes, the music stays. Once only (newer Android delivers Back twice). */
    fun close() {
        if (closing) return
        closing = true
        _finished.trySend(Unit)
    }

    /** Gives up after a failure: ends the music and closes the screen. */
    fun stop() {
        controller.stop()
        close()
    }

    override fun onCleared() {
        controller.screenGone()
    }

    private companion object {
        const val KEY_STARTED = "started"
    }
}
