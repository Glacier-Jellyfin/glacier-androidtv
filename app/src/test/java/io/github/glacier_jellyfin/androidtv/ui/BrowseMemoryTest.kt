package io.github.glacier_jellyfin.androidtv.ui

import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryKind
import io.github.glacier_jellyfin.androidtv.core.data.media.LibraryScope
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaFolder
import io.github.glacier_jellyfin.androidtv.livetv.LiveTvView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class BrowseMemoryTest {

    private val session = MutableStateFlow<Any?>("a")
    private val memory = BrowseMemory(session, Dispatchers.Unconfined)

    @Test
    fun keepsLibrariesApart() {
        memory.remember(LibraryKind.Movies, LibraryMemory(LibraryScope.All, focusId = "x"))
        assertEquals("x", memory.library(LibraryKind.Movies)?.focusId)
        assertNull(memory.library(LibraryKind.Shows))
    }

    @Test
    fun forgetsWhenTheSessionEnds() {
        memory.remember(LibraryKind.Movies, LibraryMemory(LibraryScope.All))
        memory.remember(LiveTvMemory(LiveTvView.Guide))
        session.value = null
        assertNull(memory.library(LibraryKind.Movies))
        assertNull(memory.liveTv())
    }

    @Test
    fun forgetsOnAnotherProfile() {
        memory.remember(LibraryKind.Movies, LibraryMemory(LibraryScope.All))
        session.value = "b"
        assertNull(memory.library(LibraryKind.Movies))
    }

    @Test
    fun keepsWhileTheSessionStays() {
        memory.remember(LibraryKind.Movies, LibraryMemory(LibraryScope.All))
        session.value = "a"
        assertEquals(LibraryScope.All, memory.library(LibraryKind.Movies)?.scope)
    }

    private val a = MediaFolder(UUID.randomUUID(), "80s")
    private val b = MediaFolder(UUID.randomUUID(), "Rock")
    private val c = MediaFolder(UUID.randomUUID(), "Live")

    @Test
    fun keepsTheMusicVideoFolderPath() {
        memory.remember(LibraryKind.Music, LibraryMemory(LibraryScope.MusicVideos, focusId = "x", folderPath = listOf(a, b)))
        assertEquals(listOf(a, b), memory.library(LibraryKind.Music)?.folderPath)
        session.value = "b"
        assertNull(memory.library(LibraryKind.Music))
    }

    @Test
    fun foldersGrowWithEachOpenedLevel() {
        memory.openFolder(null, a)
        memory.openFolder(a.id, b)
        assertEquals(listOf(a, b), memory.folders().path)
        // Opening from the top starts a new path.
        memory.openFolder(null, c)
        assertEquals(listOf(c), memory.folders().path)
    }

    @Test
    fun aFolderNotReachedFromTheTopIsNotKept() {
        memory.openFolder(a.id, b)
        assertTrue(memory.folders().path.isEmpty())
    }

    @Test
    fun backCutsThePathAndFocusesTheFolderLeft() {
        memory.openFolder(null, a)
        memory.openFolder(a.id, b)
        memory.folderFocused(b.id, "video")
        assertEquals("video", memory.folders().focusId)
        memory.folderShown(a.id)
        assertEquals(listOf(a), memory.folders().path)
        assertEquals(b.id.toString(), memory.folders().focusId)
        memory.folderShown(null)
        assertTrue(memory.folders().path.isEmpty())
        assertEquals(a.id.toString(), memory.folders().focusId)
    }

    @Test
    fun onlyTheDeepestFolderKeepsItsFocus() {
        memory.openFolder(null, a)
        memory.folderFocused(null, "top")
        assertNull(memory.folders().focusId)
        memory.folderFocused(a.id, "x")
        assertEquals("x", memory.folders().focusId)
    }

    @Test
    fun theTopKeepsItsFocusWithoutFolders() {
        memory.folderFocused(null, "x")
        assertEquals("x", memory.folders().focusId)
    }

    @Test
    fun forgetsTheFoldersOnAnotherProfile() {
        memory.openFolder(null, a)
        session.value = "b"
        assertTrue(memory.folders().path.isEmpty())
    }
}
