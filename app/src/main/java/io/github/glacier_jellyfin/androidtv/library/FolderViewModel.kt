package io.github.glacier_jellyfin.androidtv.library

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.glacier_jellyfin.androidtv.core.data.SessionManager
import io.github.glacier_jellyfin.androidtv.core.data.media.FolderContent
import io.github.glacier_jellyfin.androidtv.core.data.media.FolderMedia
import io.github.glacier_jellyfin.androidtv.core.data.media.FolderRepository
import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryKind
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaFolder
import io.github.glacier_jellyfin.androidtv.core.data.media.sortedFor
import io.github.glacier_jellyfin.androidtv.core.data.playback.PlaybackRepository
import io.github.glacier_jellyfin.androidtv.core.data.settings.FolderSettings
import io.github.glacier_jellyfin.androidtv.core.data.settings.FolderShow
import io.github.glacier_jellyfin.androidtv.core.data.settings.FolderSort
import io.github.glacier_jellyfin.androidtv.core.data.settings.FolderView
import io.github.glacier_jellyfin.androidtv.core.data.settings.SettingsRepository
import io.github.glacier_jellyfin.androidtv.core.jellyfin.runSuspendCatching
import io.github.glacier_jellyfin.androidtv.core.log.Log
import io.github.glacier_jellyfin.androidtv.navigation.FolderRoute
import io.github.glacier_jellyfin.androidtv.navigation.PhotoRoute
import io.github.glacier_jellyfin.androidtv.navigation.PlayerRoute
import io.github.glacier_jellyfin.androidtv.navigation.navEvent
import io.github.glacier_jellyfin.androidtv.ui.NavTarget
import io.github.glacier_jellyfin.androidtv.ui.UiEvent
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class FolderState(
    val loading: Boolean = true,
    val failed: Boolean = false,
    val content: FolderContent? = null,
    val userName: String = "",
    /** The toolbar's dropdown that is open, if any. */
    val menu: FolderMenu? = null,
)

/** The dropdowns at the right of a folder's toolbar. */
enum class FolderMenu { View, Sort }

/** What a block of media below the folders is: its heading. */
enum class MediaKind { Videos, Photos, Mixed }

/** A block of media below the folders, in the profile's order. */
data class MediaSection(val kind: MediaKind, val media: List<FolderMedia>)

/** A folder with videos and photos: only then the toolbar offers to show one kind. */
val FolderContent.canFilter: Boolean
    get() = media.any { it is FolderMedia.Video } && media.any { it is FolderMedia.Photo }

/**
 * [content]'s media for [settings] as one block: only videos, only photos, or both mixed. A folder
 * with one kind shows it, whatever the setting. Empty without media.
 */
fun mediaSections(content: FolderContent, settings: FolderSettings): List<MediaSection> {
    val sorted = content.media.sortedFor(settings)
    val show = if (content.canFilter) settings.show else FolderShow.All
    val section = when {
        show == FolderShow.Videos -> MediaSection(MediaKind.Videos, sorted.filterIsInstance<FolderMedia.Video>())
        show == FolderShow.Photos -> MediaSection(MediaKind.Photos, sorted.filterIsInstance<FolderMedia.Photo>())
        sorted.all { it is FolderMedia.Video } -> MediaSection(MediaKind.Videos, sorted)
        sorted.all { it is FolderMedia.Photo } -> MediaSection(MediaKind.Photos, sorted)
        else -> MediaSection(MediaKind.Mixed, sorted)
    }
    return listOf(section).filter { it.media.isNotEmpty() }
}

@HiltViewModel
class FolderViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @param:ApplicationContext context: Context,
    private val repository: FolderRepository,
    private val sessions: SessionManager,
    private val settings: SettingsRepository,
    playback: PlaybackRepository,
) : ViewModel() {
    private val route = savedStateHandle.toRoute<FolderRoute>()
    private val folderId = route.folderId?.let(UUID::fromString)

    /** The folder's name; null at the top, which carries the kind's name. */
    val title: String? = route.title

    /** The top is the navigation bar's entry; a folder opened from another page goes back to it. */
    val inNavBar: Boolean get() = folderId == null

    private val _state = MutableStateFlow(FolderState(userName = sessions.session.value?.user?.name.orEmpty()))
    val state: StateFlow<FolderState> = _state.asStateFlow()

    /** View, mixing and order of the profile, the same in every folder. */
    val folderSettings: StateFlow<FolderSettings> = settings.settings
        .map { it.folders }
        .stateIn(viewModelScope, SharingStarted.Eagerly, settings.settings.value.folders)

    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /** The gallery's preview: the focused video plays in it. */
    private val preview = InlineVideo(context, playback, viewModelScope)
    val previewVideo: StateFlow<InlineVideoState> = preview.state

    init {
        load()
        // Progress after watching: reload quietly.
        viewModelScope.launch { playback.stopped.collect { refresh() } }
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, failed = false) }
            runSuspendCatching { repository.load(folderId) }
                .onSuccess { content -> _state.update { it.copy(loading = false, content = content) } }
                .onFailure { error ->
                    Log.w(TAG, "Loading the folder failed", error)
                    _state.update { it.copy(loading = false, failed = true) }
                }
        }
    }

    /** In the background: what is on screen stays if it fails. */
    private fun refresh() {
        viewModelScope.launch {
            runSuspendCatching { repository.load(folderId) }
                .onSuccess { content -> _state.update { it.copy(content = content) } }
                .onFailure { Log.w(TAG, "Refreshing the folder failed", it) }
        }
    }

    fun open(folder: MediaFolder) = navigate(FolderRoute(folder.id.toString(), folder.name))

    /**
     * A video plays, then the folder's next ones, from where the gallery's preview has come to;
     * a photo opens full screen.
     */
    fun open(media: FolderMedia) {
        val folder = _state.value.content?.id?.toString() ?: return
        when (media) {
            is FolderMedia.Video -> {
                val startMs = preview.positionMs.takeIf { previewVideo.value.id == media.id && it > 0 }
                preview.release()
                navigate(PlayerRoute(media.id.toString(), queueOf = folder, startMs = startMs))
            }
            is FolderMedia.Photo -> {
                preview.release()
                navigate(PhotoRoute(folder, media.id.toString()))
            }
        }
    }

    /** The gallery's preview shows [media]: a video starts after a moment on it, anything else stops it. */
    fun showPreview(media: FolderMedia?) {
        if (media is FolderMedia.Video) preview.open(media.id, delayMs = PREVIEW_DELAY_MS) else preview.release()
    }

    /** Leaving the screen: the preview does not play on behind it. */
    fun onStop() = preview.release()

    override fun onCleared() = preview.release()

    fun setView(view: FolderView) {
        _state.update { it.copy(menu = null) }
        save { it.copy(view = view) }
    }

    fun setShow(show: FolderShow) = save { it.copy(show = show) }

    /** Opens [menu], or closes it when it is open; null closes any. */
    fun toggleMenu(menu: FolderMenu?) = _state.update { it.copy(menu = if (menu == it.menu) null else menu) }

    /** Like a library's sort menu: picking the active sort again turns the order around. */
    fun setSort(sort: FolderSort) {
        _state.update { it.copy(menu = null) }
        save { if (it.sort == sort) it.copy(descending = !it.descending) else it.copy(sort = sort, descending = false) }
    }

    private fun save(transform: (FolderSettings) -> FolderSettings) {
        viewModelScope.launch {
            runSuspendCatching { settings.update { it.copy(folders = transform(it.folders)) } }
                .onFailure { Log.w(TAG, "Saving the folder view failed", it) }
        }
    }

    fun onNav(target: NavTarget) {
        val event = sessions.navEvent(target, current = NavTarget.Library(LibraryKind.HomeVideos).takeIf { inNavBar }) ?: return
        viewModelScope.launch { _events.send(event) }
    }

    private fun navigate(route: Any) {
        viewModelScope.launch { _events.send(UiEvent.Navigate(route)) }
    }

    private companion object {
        const val TAG = "Folder"

        /** How long the focus rests on a video before the preview opens its stream: passing through opens none. */
        const val PREVIEW_DELAY_MS = 700L
    }
}
