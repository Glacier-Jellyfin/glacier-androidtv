package io.github.glacier_jellyfin.androidtv.library

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.data.media.FolderContent
import io.github.glacier_jellyfin.androidtv.core.data.media.FolderMedia
import io.github.glacier_jellyfin.androidtv.core.data.media.FolderRepository
import io.github.glacier_jellyfin.androidtv.core.data.media.PhotoDetails
import io.github.glacier_jellyfin.androidtv.core.data.playback.PlaybackRepository
import io.github.glacier_jellyfin.androidtv.core.data.playback.TranscodeStatus
import io.github.glacier_jellyfin.androidtv.core.data.settings.SettingsRepository
import io.github.glacier_jellyfin.androidtv.core.jellyfin.runSuspendCatching
import io.github.glacier_jellyfin.androidtv.core.log.Log
import io.github.glacier_jellyfin.androidtv.navigation.PhotoRoute
import io.github.glacier_jellyfin.androidtv.navigation.PlayerRoute
import io.github.glacier_jellyfin.androidtv.ui.UiEvent
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
    /** The options or the delete confirmation over the viewer, null while neither is open. */
    val menu: PhotoMenu? = null,
    /** The last photo or video was deleted: the screen goes back. */
    val closed: Boolean = false,
) {
    val current: FolderMedia? get() = media.getOrNull(index)
}

/** What is open over the viewer: the options of the current item, or the question before deleting it. */
enum class PhotoMenu { Options, ConfirmDelete }

/** The details panel of one photo or video: [details] once loaded, [failed] when they could not be. */
data class PhotoInfoState(
    val item: FolderMedia,
    val details: PhotoDetails? = null,
    val failed: Boolean = false,
) {
    val loading: Boolean get() = details == null && !failed
}

@HiltViewModel
class PhotoViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @param:ApplicationContext context: Context,
    private val repository: FolderRepository,
    private val settings: SettingsRepository,
    playback: PlaybackRepository,
    private val memory: BrowseMemory,
) : ViewModel() {
    private val route = savedStateHandle.toRoute<PhotoRoute>()

    private val _state = MutableStateFlow(PhotoState())
    val state: StateFlow<PhotoState> = _state.asStateFlow()

    /** The current video, playing right here. */
    private val inline = InlineVideo(
        context,
        playback,
        viewModelScope,
        onEnded = ::onVideoEnded,
        blurredBackground = { settings.settings.value.folders.blurredBackground },
    )
    val video: StateFlow<InlineVideoState> = inline.state

    /** Whether photos and portrait videos get a blurred backdrop (Settings). */
    val blurredBackground: StateFlow<Boolean> = settings.settings
        .map { it.folders.blurredBackground }
        .stateIn(viewModelScope, SharingStarted.Eagerly, settings.settings.value.folders.blurredBackground)

    /** The open details panel, null while it is closed; it stays with the item it was opened for. */
    private val _info = MutableStateFlow<PhotoInfoState?>(null)
    val info: StateFlow<PhotoInfoState?> = _info.asStateFlow()
    private var infoJob: Job? = null

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
                    _state.update {
                        it.copy(loading = false, media = media, index = start, slideshow = route.slideshow)
                    }
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

    /**
     * OK: during a slideshow it stops it and stays here (a video pauses); otherwise on a video the full player takes
     * over where it is, and on a photo the slideshow starts.
     */
    fun select() {
        val current = _state.value.current ?: return
        if (_state.value.slideshow) {
            stopSlideshow()
            if (current is FolderMedia.Video) inline.pause()
        } else if (current is FolderMedia.Video) {
            val position = inline.positionMs
            handedOver = current.id to position
            inline.release()
            viewModelScope.launch {
                _events.send(
                    UiEvent.Navigate(PlayerRoute(current.id.toString(), startMs = position)),
                )
            }
        } else {
            _state.update { it.copy(slideshow = true) }
            showCurrent()
        }
    }

    /**
     * Opens or closes ([menu] null) what is over the viewer. The options end a slideshow, and a video pauses
     * behind them or behind the delete question; closing without action leaves it paused (OK or play/pause
     * resumes). Deleting is only asked for what the server lets this user delete.
     */
    fun showMenu(menu: PhotoMenu?) {
        val current = _state.value.current
        when (menu) {
            PhotoMenu.Options -> {
                if (current == null) return
                stopSlideshow()
                if (current is FolderMedia.Video) inline.pause()
            }

            PhotoMenu.ConfirmDelete -> {
                if (current?.canDelete != true) return
                if (current is FolderMedia.Video) inline.pause()
            }

            null -> Unit
        }
        _state.update { it.copy(menu = menu) }
    }

    /**
     * Deletes the current item. It leaves the list at once and the viewer stays at the same place, which is
     * the next item; without any left the screen goes back. When the server refuses it comes back.
     */
    fun deleteCurrent() {
        val state = _state.value
        val item = state.current?.takeIf { it.canDelete } ?: return
        val at = state.index
        val rest = Browse(state.media, state.index).without(at)
        if (item is FolderMedia.Video) inline.release()
        _state.update {
            it.copy(menu = null, media = rest.items, index = rest.index, closed = rest.items.isEmpty())
        }
        showCurrent()
        viewModelScope.launch {
            runSuspendCatching { repository.delete(item.id) }
                .onSuccess { _events.send(UiEvent.Toast(R.string.photo_deleted, listOf(item.name))) }
                .onFailure { error ->
                    Log.w(TAG, "Deleting ${item.id} failed", error)
                    _state.update {
                        val back = Browse(it.media, it.index).with(item, at)
                        it.copy(media = back.items, index = back.index)
                    }
                    _events.send(UiEvent.Toast(R.string.photo_delete_failed))
                }
        }
    }

    /** Opens the details of the current item and ends a slideshow; the server is asked only now. */
    fun openDetails() {
        val item = _state.value.current ?: return
        stopSlideshow()
        _info.value = PhotoInfoState(item)
        infoJob?.cancel()
        infoJob = viewModelScope.launch {
            runSuspendCatching { repository.photoDetails(item.id) }
                .onSuccess { details -> _info.update { it?.copy(details = details) } }
                .onFailure { error ->
                    Log.w(TAG, "Loading the details of ${item.id} failed", error)
                    _info.update { it?.copy(failed = true) }
                }
        }
    }

    /** The transcoder's state for the video playing here, for the details; null when it does not transcode. */
    suspend fun transcodeStatus(): TranscodeStatus? = inline.transcodeStatus()

    fun closeDetails() {
        infoJob?.cancel()
        _info.value = null
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
        // The folder's grid focuses this item's card when it is shown again.
        _state.value.current?.let { memory.photoViewed(UUID.fromString(route.folderId), it.id) }
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
