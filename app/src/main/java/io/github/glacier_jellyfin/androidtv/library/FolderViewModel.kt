package io.github.glacier_jellyfin.androidtv.library

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.glacier_jellyfin.androidtv.R
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
import io.github.glacier_jellyfin.androidtv.navigation.NavTarget
import io.github.glacier_jellyfin.androidtv.navigation.PhotoRoute
import io.github.glacier_jellyfin.androidtv.navigation.PlayerRoute
import io.github.glacier_jellyfin.androidtv.navigation.navEvent
import io.github.glacier_jellyfin.androidtv.ui.UiEvent
import java.util.UUID
import javax.inject.Inject
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

data class FolderState(
    val loading: Boolean = true,
    val failed: Boolean = false,
    val content: FolderContent? = null,
    val userName: String = "",
    /** The toolbar's dropdown that is open, if any. */
    val menu: FolderMenu? = null,
    /** The card to focus after the reload on coming back; null otherwise. */
    val refocus: String? = null,
    /** The options (or the delete question) open over a card, if any. */
    val options: MediaOptions? = null,
)

/** The options of [item]'s card; [confirmDelete] is the question before deleting it. */
data class MediaOptions(val item: FolderMedia, val confirmDelete: Boolean = false)

/** What the options of a card offer: Open is a photo's viewer or a video's player, Slideshow only for photos. */
enum class MediaAction { Open, Slideshow, Delete }

/** The actions of [item]'s options in their order; Delete only when the server lets this user delete it. */
fun mediaActions(item: FolderMedia): List<MediaAction> = buildList {
    add(MediaAction.Open)
    if (item is FolderMedia.Photo) add(MediaAction.Slideshow)
    if (item.canDelete) add(MediaAction.Delete)
}

/** The content without the photo or video [id]. */
fun FolderContent.withoutMedia(id: UUID): FolderContent = copy(media = media.filterNot { it.id == id })

/**
 * The card to focus after a reload: [wanted] while it is still there, else the card at its old position in [before]
 * (the same index in [after], or the last one); null when [wanted] was not a card of the list at all.
 */
fun focusAfterReload(
    wanted: String,
    before: List<String>,
    after: List<String>,
): String? {
    if (wanted in after) return wanted
    val index = before.indexOf(wanted).takeIf { it >= 0 } ?: return null
    return after.getOrNull(index) ?: after.lastOrNull()
}

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
    private val memory: BrowseMemory,
) : ViewModel() {
    private val route = savedStateHandle.toRoute<FolderRoute>()
    private val folderId = route.folderId?.let(UUID::fromString)

    /** The folder's name; null at the top, which carries the kind's name. */
    val title: String? = route.title

    /** The top is the navigation bar's entry; a folder opened from another page goes back to it. */
    val inNavBar: Boolean get() = folderId == null

    /**
     * The entry to focus first: the one the user left (a folder rebuilt on the back stack carries it in its route),
     * for the top also from the memory when it was left without a folder open.
     */
    val restoredFocus: String? =
        route.focus ?: if (folderId == null) memory.folders().takeIf { it.path.isEmpty() }?.focusId else null

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
            runSuspendCatching { repository.load(folderId, withDelete = true) }
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
            runSuspendCatching { repository.load(folderId, withDelete = true) }
                .onSuccess { content ->
                    _state.update { it.copy(content = content, refocus = refocusFor(it.content, content)) }
                }
                .onFailure { Log.w(TAG, "Refreshing the folder failed", it) }
        }
    }

    /**
     * The card to focus after the reload that follows coming back: the item the photo viewer showed last, else the
     * card that had the focus; null when none is wanted or the reload is not the one after coming back.
     */
    private fun refocusFor(before: FolderContent?, after: FolderContent): String? {
        if (!refocusOnReload || before == null) return null
        refocusOnReload = false
        val wanted = viewedLast?.toString() ?: lastFocused ?: return null
        return focusAfterReload(wanted, cardIds(before), cardIds(after))
    }

    /** The ids of the media cards in the order the page shows them. */
    private fun cardIds(content: FolderContent) =
        mediaSections(content, folderSettings.value).flatMap { it.media }.map { it.id.toString() }

    /** Holding OK or the Menu key on [item]'s card: opens its options. */
    fun showOptions(item: FolderMedia) = _state.update { it.copy(options = MediaOptions(item), refocus = null) }

    /** Closes the options or the delete question without an action. */
    fun closeOptions() = _state.update { it.copy(options = null) }

    /** Delete in the options: asks first, and only for what the server lets this user delete. */
    fun askDelete() = _state.update { state ->
        state.options?.takeIf { it.item.canDelete }?.let { state.copy(options = it.copy(confirmDelete = true)) }
            ?: state
    }

    /**
     * Deletes the card's item after the question. The card leaves at once and the focus goes to the card that
     * takes its place; when the server refuses, it comes back with the focus.
     */
    fun deleteOptionsItem() {
        val state = _state.value
        val item = state.options?.item?.takeIf { it.canDelete } ?: return
        val before = state.content ?: return
        val after = before.withoutMedia(item.id)
        val next = focusAfterReload(item.id.toString(), cardIds(before), cardIds(after))
        _state.update { it.copy(options = null, content = after, refocus = next) }
        viewModelScope.launch {
            runSuspendCatching { repository.delete(item.id) }
                .onSuccess { _events.send(UiEvent.Toast(R.string.photo_deleted, listOf(item.name))) }
                .onFailure { error ->
                    Log.w(TAG, "Deleting ${item.id} failed", error)
                    _state.update {
                        it.copy(
                            content = it.content?.let { content -> content.copy(media = content.media + item) },
                            refocus = item.id.toString(),
                        )
                    }
                    _events.send(UiEvent.Toast(R.string.photo_delete_failed))
                }
        }
    }

    fun open(folder: MediaFolder) {
        memory.openFolder(folderId, folder)
        navigate(FolderRoute(folder.id.toString(), folder.name))
    }

    /**
     * A level of the breadcrumb: the folder [id] named [name] (null: the top), or the way back to it.
     * It replaces this folder; [focus] is the folder below it on the way here, to focus there.
     */
    fun openLevel(
        id: UUID?,
        name: String?,
        focus: UUID?,
    ) = navigate(
        FolderRoute(id?.toString(), name, focus?.toString()),
        replace = true,
        upward = true,
    )

    private var shownBefore = false

    /** The entry that had the focus last, and the item the photo viewer showed last since coming back. */
    private var lastFocused: String? = null
    private var viewedLast: UUID? = null

    /** The next reload is the one after coming back, which says where the focus goes. */
    private var refocusOnReload = false

    /**
     * The screen is shown (again): the folders below it were left, so Back from them lands here. Coming back, what
     * is on screen is reloaded quietly: the photo viewer can have deleted something meanwhile. The focus then goes
     * to the card of the item the viewer showed last (see [focusAfterReload] when that one is gone).
     */
    fun shown() {
        memory.folderShown(folderId)
        if (shownBefore) {
            viewedLast = memory.takeViewed(folderId)
            refocusOnReload = true
            refresh()
        }
        shownBefore = true
    }

    /** The grid reports the entry that has the focus, for the way back through the navigation bar. */
    fun focused(id: String) {
        lastFocused = id
        memory.folderFocused(folderId, id)
    }

    /**
     * A video plays, then the folder's next ones, from where the gallery's preview has come to;
     * a photo opens full screen.
     */
    fun open(media: FolderMedia) {
        val folder = _state.value.content?.id?.toString() ?: return
        _state.update { it.copy(options = null) }
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

    /** A photo opens full screen with the slideshow running. */
    fun startSlideshow(photo: FolderMedia.Photo) {
        val folder = _state.value.content?.id?.toString() ?: return
        _state.update { it.copy(options = null) }
        preview.release()
        navigate(PhotoRoute(folder, photo.id.toString(), slideshow = true))
    }

    /** The gallery's preview shows [media]: a video starts after a moment on it, anything else stops it. */
    fun showPreview(media: FolderMedia?) {
        if (media is FolderMedia.Video) preview.open(media.id, delayMs = PREVIEW_DELAY_MS) else preview.release()
    }

    /** Leaving the screen: the preview does not play on behind it. */
    fun onStop() {
        preview.release()
        refocusOnReload = false
        _state.update { it.copy(refocus = null) }
    }

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
        val event = sessions.navEvent(
            target,
            current = NavTarget.Library(LibraryKind.HomeVideos).takeIf { inNavBar },
        ) ?: return
        viewModelScope.launch { _events.send(event) }
    }

    private fun navigate(
        route: Any,
        replace: Boolean = false,
        upward: Boolean = false,
    ) {
        viewModelScope.launch { _events.send(UiEvent.Navigate(route, replace = replace, upward = upward)) }
    }

    private companion object {
        const val TAG = "Folder"

        /** How long the focus rests on a video before the preview opens its stream: passing through opens none. */
        const val PREVIEW_DELAY_MS = 700L
    }
}
