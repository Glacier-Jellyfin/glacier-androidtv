package io.github.glacier_jellyfin.androidtv.music

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.glacier_jellyfin.androidtv.core.data.media.MusicRepository
import io.github.glacier_jellyfin.androidtv.navigation.MusicRoute
import io.github.glacier_jellyfin.androidtv.ui.UiEvent
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.merge
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
    music: MusicRepository,
) : ViewModel() {

    val state: StateFlow<MusicUiState> = controller.state
    val progress: StateFlow<MusicProgress> = controller.progress

    private val notes = Channel<UiEvent>(Channel.BUFFERED)
    val events: Flow<UiEvent> = merge(controller.events, notes.receiveAsFlow())

    val musicActions = MusicActions(viewModelScope, controller, music, toast = { notes.send(it) })

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
    fun toggleFavorite() = controller.toggleFavorite()

    /** Options for a song of the queue (hold OK, or the menu key). */
    fun queueOptions(index: Int) {
        musicActions.open(queueTarget(index) ?: return)
    }

    /** Options for the song that plays (the menu key outside the queue). */
    fun currentOptions() = queueOptions(state.value.index)

    /** The add button: the song that plays goes into a playlist, picked right away. */
    fun addCurrentToPlaylist() {
        musicActions.openPlaylists(queueTarget(state.value.index) ?: return)
    }

    private fun queueTarget(index: Int): MusicTarget? {
        val current = state.value
        val track = current.queue.getOrNull(index)?.track ?: return null
        val playing = index == current.index
        return MusicTarget(
            title = track.title,
            subtitle = listOfNotNull(track.artist, track.album).joinToString(" · ").ifEmpty { null },
            tracks = { listOf(track) },
            queueIndex = index.takeUnless { playing },
            playing = playing,
            mixFrom = track.id,
        )
    }

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
