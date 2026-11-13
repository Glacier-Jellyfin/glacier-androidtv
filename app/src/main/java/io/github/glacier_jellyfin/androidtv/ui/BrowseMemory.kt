package io.github.glacier_jellyfin.androidtv.ui

import io.github.glacier_jellyfin.androidtv.core.data.SessionManager
import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryKind
import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryScope
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaFolder
import io.github.glacier_jellyfin.androidtv.livetv.LiveTvView
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * What a library opened from the navigation bar showed last: its tab and the title with the focus.
 * Sort and the heart / "Unwatched" toggles are not here: the profile settings keep them for good.
 */
data class LibraryMemory(
    val scope: LibraryScope,
    /** Null after a tab, sort or filter change: the old title may not be in the new list. */
    val focusId: String? = null,
    /** The folders open in the music videos' folder view, from the top; empty at the top. */
    val folderPath: List<MediaFolder> = emptyList(),
)

/**
 * Where "Fotos & Videos" was left: the folders opened from its top (each its own screen on the back
 * stack), and the entry with the focus in the deepest one, or in the top without folders.
 */
data class FolderMemory(val path: List<MediaFolder> = emptyList(), val focusId: String? = null)

/** The Live TV view last used. The heart filter and the sort are the profile settings'. */
data class LiveTvMemory(val view: LiveTvView)

/**
 * Where the user left each page of the navigation bar. A way back through the bar starts the page
 * anew (the home page clears the back stack), so its view model and saved state are gone; this
 * outlives them for the process. Another profile does not inherit it.
 */
@Singleton
class BrowseMemory internal constructor(sessionKeys: Flow<Any?>, dispatcher: CoroutineDispatcher) {
    @Inject
    constructor(sessions: SessionManager) : this(
        sessions.session.map { it?.server?.id to it?.user?.userId },
        Dispatchers.Default,
    )

    private val libraries = ConcurrentHashMap<LibraryKind, LibraryMemory>()

    @Volatile private var liveTv: LiveTvMemory? = null

    @Volatile private var folders = FolderMemory()

    init {
        // Leaving the profile (also to come back to the same one) forgets everything.
        CoroutineScope(SupervisorJob() + dispatcher).launch {
            sessionKeys.distinctUntilChanged().drop(1).collect { clear() }
        }
    }

    fun library(kind: LibraryKind): LibraryMemory? = libraries[kind]

    fun remember(kind: LibraryKind, memory: LibraryMemory) {
        libraries[kind] = memory
    }

    fun liveTv(): LiveTvMemory? = liveTv

    fun remember(memory: LiveTvMemory) {
        liveTv = memory
    }

    /** Where the home video folders were left; no path when the top is where it ended. */
    fun folders(): FolderMemory = folders

    /** [folder] opened from [parent] (null: the top). A folder not reached from the top, such as one opened from the home page, is not kept. */
    @Synchronized
    fun openFolder(parent: UUID?, folder: MediaFolder) {
        val path = folders.path
        val above = if (parent == null) {
            emptyList()
        } else {
            path.indexOfFirst { it.id == parent }.takeIf { it >= 0 }?.let {
                path.take(it + 1)
            } ?: return
        }
        folders = FolderMemory(above + folder)
    }

    /**
     * The folder [id] (null: the top) is on screen, so the folders below it were left: Back from one lands on it
     * again, with its entry focused.
     */
    @Synchronized
    fun folderShown(id: UUID?) {
        val path = folders.path
        val index = if (id == null) -1 else path.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return
        if (index == path.lastIndex) return
        folders = FolderMemory(path.take(index + 1), path[index + 1].id.toString())
    }

    /** [entryId] has the focus in the folder [id] (null: the top); only the deepest folder is kept. */
    @Synchronized
    fun folderFocused(id: UUID?, entryId: String) {
        if (folders.path.lastOrNull()?.id == id) folders = folders.copy(focusId = entryId)
    }

    fun clear() {
        libraries.clear()
        liveTv = null
        folders = FolderMemory()
    }
}
