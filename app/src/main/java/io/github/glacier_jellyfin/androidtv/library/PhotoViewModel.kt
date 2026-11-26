package io.github.glacier_jellyfin.androidtv.library

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.data.media.DetailRepository
import io.github.glacier_jellyfin.androidtv.core.data.media.FolderContent
import io.github.glacier_jellyfin.androidtv.core.data.media.FolderMedia
import io.github.glacier_jellyfin.androidtv.core.data.media.FolderRepository
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaPhoto
import io.github.glacier_jellyfin.androidtv.core.data.media.PhotoLocation
import io.github.glacier_jellyfin.androidtv.core.data.playback.PlaybackRepository
import io.github.glacier_jellyfin.androidtv.core.data.settings.SettingsRepository
import io.github.glacier_jellyfin.androidtv.core.jellyfin.runSuspendCatching
import io.github.glacier_jellyfin.androidtv.core.log.Log
import io.github.glacier_jellyfin.androidtv.navigation.PhotoRoute
import io.github.glacier_jellyfin.androidtv.navigation.PlayerRoute
import io.github.glacier_jellyfin.androidtv.ui.UiEvent
import java.time.LocalDateTime
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
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
    val deleteTarget: MediaPhoto? = null,
    val deleting: Boolean = false,
    val modifiedPhoto: Pair<UUID, LocalDateTime>? = null,
    val resolvedPhoto: Pair<UUID, PhotoPlace>? = null,
) {
    val current: FolderMedia? get() = media.getOrNull(index)
    val photoPlace: PhotoPlace? get() = resolvedPhoto?.takeIf { it.first == current?.id }?.second

    val captionDate: LocalDateTime?
        get() {
            val item = current as? FolderMedia.Photo ?: return current?.date
            val modifiedAt = modifiedPhoto?.takeIf { it.first == item.id }?.second
            return item.photo.takenAt ?: listOfNotNull(item.date, modifiedAt).minOrNull()
        }

    /** Keep the next item selected, or go back one when the deleted photo was last. */
    internal fun afterPhotoDeleted(id: UUID): PhotoState {
        val remaining = media.filterNot { it.id == id }
        return copy(
            media = remaining,
            index = index.coerceAtMost(remaining.lastIndex.coerceAtLeast(0)),
            forward = index < remaining.size,
            deleteTarget = null,
            deleting = false,
        )
    }
}

@HiltViewModel
class PhotoViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @param:ApplicationContext context: Context,
    private val repository: FolderRepository,
    private val details: DetailRepository,
    private val settings: SettingsRepository,
    private val geocoder: PhotoGeocoder,
    playback: PlaybackRepository,
) : ViewModel() {
    private val route = savedStateHandle.toRoute<PhotoRoute>()

    private val _state = MutableStateFlow(PhotoState())
    val state: StateFlow<PhotoState> = _state.asStateFlow()
    private val detailedLocation = MutableStateFlow<Pair<UUID, PhotoLocation>?>(null)

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
        viewModelScope.launch {
            state.map { it.current as? FolderMedia.Photo }.distinctUntilChangedBy { it?.id }.collectLatest { item ->
                if (item == null || item.photo.takenAt != null) return@collectLatest
                val modifiedAt = runSuspendCatching { repository.photoModifiedAt(item.id) }.getOrNull()
                if (modifiedAt != null) _state.update { it.copy(modifiedPhoto = item.id to modifiedAt) }
            }
        }
        viewModelScope.launch {
            val photos = state.map { (it.current as? FolderMedia.Photo)?.photo to it.slideshow }.distinctUntilChanged()
            val locations = combine(photos, detailedLocation) { (photo, slideshow), detail ->
                photo?.let {
                    val listed = it.location
                    val fromDetails = detail?.takeIf { entry -> entry.first == it.id }?.second
                    val location = if (listed.names.isEmpty() && listed.coordinates == null) {
                        fromDetails ?: listed
                    } else {
                        listed
                    }
                    Triple(it.id, location, !slideshow)
                }
            }.distinctUntilChanged()
            combine(locations, settings.photoGeocoding) { location, options -> location to options }
                .collectLatest { (location, options) ->
                    _state.update { it.copy(resolvedPhoto = null) }
                    val (id, source, interactive) = location ?: return@collectLatest
                    val place = geocoder.resolve(
                        source,
                        options,
                        Locale.getDefault(),
                        interactive,
                    ) ?: return@collectLatest
                    _state.update { if (it.current?.id == id) it.copy(resolvedPhoto = id to place) else it }
                }
        }
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

    /** Browse by one item; during a slideshow the new photo's time starts anew. */
    fun browseBy(offset: Int) {
        if (step(offset)) showCurrent()
    }

    /** The full player takes over an inline video at its current position. */
    fun openVideoPlayer() {
        val current = _state.value.current as? FolderMedia.Video ?: return
        val position = inline.positionMs
        handedOver = current.id to position
        inline.release()
        viewModelScope.launch {
            _events.send(UiEvent.Navigate(PlayerRoute(current.id.toString(), startMs = position)))
        }
    }

    /** Play and pause: the video here, or the slideshow on a photo. */
    fun playPause() {
        val state = _state.value
        if (state.deleteTarget != null) return
        when (state.current) {
            is FolderMedia.Video -> inline.togglePlay()

            is FolderMedia.Photo -> if (state.slideshow) {
                stopSlideshow()
            } else {
                _state.update { it.copy(slideshow = true) }
                showCurrent()
            }

            null -> Unit
        }
    }

    suspend fun photoDetails(id: UUID) = repository.photoDetails(id).also { details ->
        if (state.value.current?.id == id) detailedLocation.value = id to details.location
    }

    fun openDelete() {
        val state = _state.value
        val photo = (state.current as? FolderMedia.Photo)?.photo ?: return
        if (!photo.canDelete || state.deleteTarget != null) return
        stopSlideshow()
        _state.update { it.copy(deleteTarget = photo) }
    }

    fun closeDelete() {
        _state.update { if (it.deleting) it else it.copy(deleteTarget = null) }
    }

    fun deletePhoto() {
        val state = _state.value
        val photo = state.deleteTarget ?: return
        if (state.deleting || state.current?.id != photo.id) return
        _state.update { it.copy(deleting = true) }
        viewModelScope.launch {
            runSuspendCatching { details.deletePhoto(photo.id) }
                .onSuccess {
                    _state.update { it.afterPhotoDeleted(photo.id) }
                    showCurrent()
                    _events.send(UiEvent.Toast(R.string.photo_deleted, listOf(photo.name)))
                }
                .onFailure { error ->
                    Log.w(TAG, "Deleting the photo failed", error)
                    _state.update { it.copy(deleteTarget = null, deleting = false) }
                    _events.send(UiEvent.Toast(R.string.photo_delete_failed))
                }
        }
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
        if (current.deleteTarget != null || target !in current.media.indices) return false
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

    fun stopSlideshow() {
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
