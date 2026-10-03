package io.github.glacier_jellyfin.androidtv.music

import io.github.glacier_jellyfin.androidtv.core.log.Log
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.data.media.ItemKind
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaItem
import io.github.glacier_jellyfin.androidtv.core.data.media.MusicRepository
import io.github.glacier_jellyfin.androidtv.core.data.media.MusicTrack
import io.github.glacier_jellyfin.androidtv.ui.UiEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * What the options are for: one song, or all songs of an album, artist or
 * playlist ([tracks] may have to be loaded first).
 */
class MusicTarget(
    val title: String,
    val subtitle: String? = null,
    val tracks: suspend () -> List<MusicTrack>,
    /** Its place in the queue (player queue rows): offers moving and removing. */
    val queueIndex: Int? = null,
    /** The song that plays: only adding it to a playlist makes sense. */
    val playing: Boolean = false,
    /** Song, album, artist or playlist an instant mix can start from. */
    val mixFrom: UUID? = null,
    /** The playlist page it is on: offers taking it out of the playlist. */
    val playlistId: UUID? = null,
    val playlistEntryIds: List<String> = emptyList(),
)

/** The option sheet's steps. */
sealed interface MusicSheet {
    val target: MusicTarget

    data class Menu(override val target: MusicTarget) : MusicSheet

    /** Picking a playlist to add to; [playlists] is null while loading. */
    data class Playlists(override val target: MusicTarget, val playlists: List<MediaItem>? = null) : MusicSheet

    /** Naming a new playlist for the songs. */
    data class NewPlaylist(override val target: MusicTarget, val name: String = "", val busy: Boolean = false) : MusicSheet
}

/**
 * The options for songs (hold OK, or the menu key): play next, add to the
 * queue, add to a playlist (a new one too), and on playlist pages or in the
 * queue take them out again. Shared by the music pages and the player, like
 * the PIN gate.
 */
class MusicActions(
    private val scope: CoroutineScope,
    private val controller: MusicController,
    private val music: MusicRepository,
    private val toast: suspend (UiEvent.Toast) -> Unit,
    /** A playlist changed (songs added or taken out), so a page showing it can reload. */
    private val onPlaylistChanged: (UUID) -> Unit = {},
) {
    private val _sheet = MutableStateFlow<MusicSheet?>(null)
    val sheet: StateFlow<MusicSheet?> = _sheet.asStateFlow()

    /** The sheet started at the playlists, with no menu to go back to. */
    private var straightToPlaylists = false

    fun open(target: MusicTarget) {
        straightToPlaylists = false
        _sheet.value = MusicSheet.Menu(target)
    }

    /** Straight to picking a playlist (the player's add button): Back then closes the sheet. */
    fun openPlaylists(target: MusicTarget) {
        straightToPlaylists = true
        _sheet.value = MusicSheet.Playlists(target)
        loadPlaylists(target)
    }

    fun dismiss() {
        _sheet.value = null
    }

    /** Back inside the sheet: one step back, from the menu out. */
    fun back() {
        val sheet = _sheet.value ?: return
        _sheet.value = when (sheet) {
            is MusicSheet.Menu -> null
            is MusicSheet.Playlists -> if (straightToPlaylists) null else MusicSheet.Menu(sheet.target)
            is MusicSheet.NewPlaylist -> MusicSheet.Playlists(sheet.target).also { loadPlaylists(it.target) }
        }
    }

    fun playNext() = withTracks(R.string.music_plays_next) { controller.playNext(it) }

    fun addToQueue() = withTracks(R.string.music_added_to_queue) { controller.addToQueue(it) }

    /** Songs like the target, from the server, in place of the queue. */
    fun playMix() {
        val target = _sheet.value?.target ?: return
        val from = target.mixFrom ?: return
        dismiss()
        scope.launch {
            val tracks = try {
                music.instantMix(from)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Loading the instant mix failed", e)
                emptyList()
            }
            if (tracks.isEmpty()) {
                toast(UiEvent.Toast(R.string.music_mix_failed))
                return@launch
            }
            controller.playTracks(ItemKind.Other, target.title, tracks)
            toast(UiEvent.Toast(R.string.music_mix_started))
        }
    }

    fun moveNext() {
        val index = _sheet.value?.target?.queueIndex ?: return
        dismiss()
        controller.moveNext(index)
        say(R.string.music_plays_next)
    }

    fun removeFromQueue() {
        val index = _sheet.value?.target?.queueIndex ?: return
        dismiss()
        controller.removeAt(index)
        say(R.string.music_removed_from_queue)
    }

    fun choosePlaylist() {
        val target = _sheet.value?.target ?: return
        _sheet.value = MusicSheet.Playlists(target)
        loadPlaylists(target)
    }

    private fun loadPlaylists(target: MusicTarget) {
        scope.launch {
            val playlists = try {
                music.playlists()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Loading playlists failed", e)
                emptyList()
            }
            _sheet.update { if (it is MusicSheet.Playlists && it.target === target) it.copy(playlists = playlists) else it }
        }
    }

    fun addTo(playlist: MediaItem) {
        val target = _sheet.value?.target ?: return
        dismiss()
        scope.launch {
            runChange {
                music.addToPlaylist(playlist.id, target.tracks().map { it.id })
                onPlaylistChanged(playlist.id)
                toast(UiEvent.Toast(R.string.music_added_to_playlist, listOf(playlist.title)))
            }
        }
    }

    fun newPlaylist() {
        val target = _sheet.value?.target ?: return
        _sheet.value = MusicSheet.NewPlaylist(target)
    }

    fun setName(name: String) {
        _sheet.update { if (it is MusicSheet.NewPlaylist) it.copy(name = name) else it }
    }

    fun createPlaylist() {
        val sheet = _sheet.value as? MusicSheet.NewPlaylist ?: return
        val name = sheet.name.trim()
        if (name.isEmpty() || sheet.busy) return
        _sheet.value = sheet.copy(busy = true)
        scope.launch {
            runChange {
                music.createPlaylist(name, sheet.target.tracks().map { it.id })
                toast(UiEvent.Toast(R.string.music_playlist_created, listOf(name)))
            }
            dismiss()
        }
    }

    fun removeFromPlaylist() {
        val target = _sheet.value?.target ?: return
        val playlistId = target.playlistId ?: return
        dismiss()
        scope.launch {
            runChange {
                music.removeFromPlaylist(playlistId, target.playlistEntryIds)
                onPlaylistChanged(playlistId)
                toast(UiEvent.Toast(R.string.music_removed_from_playlist))
            }
        }
    }

    private fun withTracks(message: Int, action: (List<MusicTrack>) -> Unit) {
        val target = _sheet.value?.target ?: return
        dismiss()
        scope.launch {
            val tracks = try {
                target.tracks()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Loading songs failed", e)
                emptyList()
            }
            if (tracks.isEmpty()) return@launch
            action(tracks)
            toast(UiEvent.Toast(message))
        }
    }

    private suspend fun runChange(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Changing a playlist failed", e)
            toast(UiEvent.Toast(R.string.music_playlist_failed))
        }
    }

    private fun say(message: Int) {
        scope.launch { toast(UiEvent.Toast(message)) }
    }

    private companion object {
        const val TAG = "MusicActions"
    }
}
