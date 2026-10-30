package io.github.glacier_jellyfin.androidtv.library

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.glacier_jellyfin.androidtv.core.data.media.FolderContent
import io.github.glacier_jellyfin.androidtv.core.data.media.FolderMedia
import io.github.glacier_jellyfin.androidtv.core.data.media.FolderRepository
import io.github.glacier_jellyfin.androidtv.core.data.playback.PlaybackRepository
import io.github.glacier_jellyfin.androidtv.core.data.settings.SettingsRepository
import io.github.glacier_jellyfin.androidtv.core.jellyfin.runSuspendCatching
import io.github.glacier_jellyfin.androidtv.core.log.Log
import io.github.glacier_jellyfin.androidtv.navigation.PhotoRoute
import io.github.glacier_jellyfin.androidtv.navigation.PlayerRoute
import io.github.glacier_jellyfin.androidtv.ui.UiEvent
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class PhotoState(
    val loading: Boolean = true,
    val failed: Boolean = false,
    /** The folder's videos and photos in the folder page's order. */
    val media: List<FolderMedia> = emptyList(),
    val index: Int = 0,
    val slideshow: Boolean = false,
    /** The last move went to the next one, not back; the picture slides in from that side. */
    val forward: Boolean = true,
    /** How long the slideshow shows each photo (Settings). */
    val slideMs: Long = 5_000,
) {
    val current: FolderMedia? get() = media.getOrNull(index)
}

@HiltViewModel
class PhotoViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @param:ApplicationContext context: Context,
    private val repository: FolderRepository,
    private val settings: SettingsRepository,
    playback: PlaybackRepository,
) : ViewModel() {
    private val route = savedStateHandle.toRoute<PhotoRoute>()

    private val _state = MutableStateFlow(PhotoState())
    val state: StateFlow<PhotoState> = _state.asStateFlow()

    /** The current video, playing right here. */
    private val inline = InlineVideo(context, playback, viewModelScope, onEnded = ::onVideoEnded)
    val video: StateFlow<InlineVideoState> = inline.state

    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var slideshow: Job? = null

    /** OK on a video handed it to the full player at this position; back here it goes on from there. */
    private var handedOver: Pair<UUID, Long>? = null

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, failed = false) }
            runSuspendCatching { repository.media(UUID.fromString(route.folderId)) }
                .onSuccess { all ->
                    // The page's order and filter: all in one timeline, or only videos or photos.
                    val folders = settings.settings.value.folders
                    val media = mediaSections(FolderContent(null, emptyList(), all), folders).flatMap { it.media }
                    val start = media.indexOfFirst { it.id.toString() == route.photoId }.coerceAtLeast(0)
                    _state.update { it.copy(loading = false, media = media, index = start) }
                    showCurrent()
                }
                .onFailure { error ->
                    Log.w(TAG, "Loading the photos failed", error)
                    _state.update { it.copy(loading = false, failed = true) }
                }
        }
    }

    /** The next photo or video; during a slideshow a photo's time starts anew. */
    fun next() {
        if (step(1)) showCurrent()
    }

    fun previous() {
        if (step(-1)) showCurrent()
    }

    /** OK: on a video the full player takes over where it is; on a photo the slideshow starts or stops. */
    fun select() {
        val current = _state.value.current ?: return
        if (current is FolderMedia.Video) {
            val position = inline.positionMs
            handedOver = current.id to position
            inline.release()
            viewModelScope.launch { _events.send(UiEvent.Navigate(PlayerRoute(current.id.toString(), startMs = position))) }
        } else if (_state.value.slideshow) {
            stopSlideshow()
        } else {
            _state.update { it.copy(slideshow = true) }
            showCurrent()
        }
    }

    /** Play and pause: the video here, or the slideshow on a photo. */
    fun playPause() {
        if (_state.value.current is FolderMedia.Video) inline.togglePlay() else select()
    }

    /** Back on screen from the full player: the slideshow goes on with the next one, or the video plays on here. */
    fun onResume() {
        val (id, position) = handedOver ?: return
        handedOver = null
        val current = _state.value.current
        when {
            _state.value.slideshow -> advance()
            current is FolderMedia.Video && current.id == id -> inline.open(current.id, startMs = position)
        }
    }

    /** Leaving the screen, not for the full player: nothing plays on behind it. */
    fun onStop() {
        if (handedOver == null) inline.pause()
    }

    /** Moves by [by]; false at either end, where it stays. */
    private fun step(by: Int): Boolean {
        val current = _state.value
        val target = current.index + by
        if (target !in current.media.indices) return false
        _state.update { it.copy(index = target, forward = by > 0) }
        return true
    }

    /** A photo stays for the slideshow's time; a video plays here to its end. */
    private fun showCurrent() {
        slideshow?.cancel()
        when (val current = _state.value.current) {
            is FolderMedia.Video -> inline.open(current.id)
            is FolderMedia.Photo -> {
                inline.release()
                if (_state.value.slideshow) startPhotoTimer()
            }
            null -> inline.release()
        }
    }

    private fun startPhotoTimer() {
        val slideMs = settings.settings.value.folders.slideSeconds * 1000L
        _state.update { it.copy(slideMs = slideMs) }
        slideshow = viewModelScope.launch {
            delay(slideMs)
            advance()
        }
    }

    /** The slideshow's next step; after the last one it ends there. */
    private fun advance() {
        if (step(1)) showCurrent() else stopSlideshow()
    }

    private fun onVideoEnded() {
        if (_state.value.slideshow) advance()
    }

    private fun stopSlideshow() {
        slideshow?.cancel()
        slideshow = null
        _state.update { it.copy(slideshow = false) }
    }

    override fun onCleared() {
        inline.release()
    }

    private companion object {
        const val TAG = "Photos"
    }
}
