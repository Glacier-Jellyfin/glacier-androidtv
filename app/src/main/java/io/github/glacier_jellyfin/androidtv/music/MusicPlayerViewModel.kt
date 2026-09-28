package io.github.glacier_jellyfin.androidtv.music

import android.content.Context
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.data.media.DetailRepository
import io.github.glacier_jellyfin.androidtv.core.data.media.ItemKind
import io.github.glacier_jellyfin.androidtv.core.data.media.Lyrics
import io.github.glacier_jellyfin.androidtv.core.data.media.MusicRepository
import io.github.glacier_jellyfin.androidtv.core.data.media.MusicShuffle
import io.github.glacier_jellyfin.androidtv.core.data.media.MusicTrack
import io.github.glacier_jellyfin.androidtv.core.data.playback.PlaybackMethod
import io.github.glacier_jellyfin.androidtv.core.data.playback.PlaybackPosition
import io.github.glacier_jellyfin.androidtv.core.data.playback.PlaybackRepository
import io.github.glacier_jellyfin.androidtv.core.data.playback.PlaybackSource
import io.github.glacier_jellyfin.androidtv.core.player.GlacierPlayer
import io.github.glacier_jellyfin.androidtv.navigation.MusicRoute
import io.github.glacier_jellyfin.androidtv.ui.UiEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

enum class RepeatMode { Off, All, One }

/** A song in the queue; [key] is its place in the source, which stays unique when a playlist lists a song twice. */
data class QueueEntry(val key: Int, val track: MusicTrack)

sealed interface LyricsState {
    data object Loading : LyricsState
    data object None : LyricsState
    data class Loaded(val lyrics: Lyrics) : LyricsState
}

data class MusicUiState(
    val loading: Boolean = true,
    val failed: Boolean = false,
    /** Album, artist or playlist the queue was made from. */
    val sourceKind: ItemKind? = null,
    val sourceTitle: String = "",
    val queue: List<QueueEntry> = emptyList(),
    val index: Int = 0,
    val playing: Boolean = false,
    /** Playing or about to (buffering); drives the play/pause button. */
    val playWhenReady: Boolean = true,
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.Off,
    val lyricsOn: Boolean = false,
    val lyrics: LyricsState = LyricsState.Loading,
    val method: PlaybackMethod? = null,
) {
    val current: MusicTrack? get() = queue.getOrNull(index)?.track
}

/** Position data, kept apart from [MusicUiState] so ticking only redraws what moves. */
data class MusicProgress(val positionMs: Long = 0, val durationMs: Long = 0)

@HiltViewModel
class MusicPlayerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @param:ApplicationContext private val context: Context,
    private val details: DetailRepository,
    private val music: MusicRepository,
    private val playback: PlaybackRepository,
    private val shuffleSwitch: MusicShuffle,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<MusicRoute>()

    private val _state = MutableStateFlow(MusicUiState(shuffle = shuffleSwitch.on.value))
    val state: StateFlow<MusicUiState> = _state.asStateFlow()

    private val _progress = MutableStateFlow(MusicProgress())
    val progress: StateFlow<MusicProgress> = _progress.asStateFlow()

    /** Fires when the screen should close. */
    private val _finished = Channel<Unit>(Channel.CONFLATED)
    val finished = _finished.receiveAsFlow()

    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var player: ExoPlayer? = null
    /** The source's songs in their own order, to return to when shuffle goes off. */
    private var sourceOrder: List<QueueEntry> = emptyList()
    private var sources: Map<Int, PlaybackSource> = emptyMap()
    /** The song reported to the server as playing, and where it last was. */
    private var reported: PlaybackSource? = null
    private var lastPositionMs = 0L
    private val lyricsCache = mutableMapOf<UUID, Lyrics?>()
    private var lyricsJob: Job? = null
    private var ticker: Job? = null
    private var closing = false

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
            // A song that ran to its end stopped at its end, not at the last tick before it.
            val endedAt = if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
                reported?.let { source -> sourceTrack(source)?.durationMs }
            } else {
                null
            }
            reportCurrentStopped(endedAt)
            syncIndex()
            reportCurrentStarted()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> {
                    _state.update { it.copy(loading = false) }
                    if (reported == null) reportCurrentStarted()
                }
                Player.STATE_ENDED -> {
                    // The end of the queue: the last song stays shown, paused (design).
                    reportCurrentStopped(_state.value.current?.durationMs)
                    player?.pause()
                }
                else -> Unit
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            _state.update { it.copy(playWhenReady = playWhenReady) }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.update { it.copy(playing = isPlaying) }
            reported?.let { playback.reportProgress(it, position()) }
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK) reported?.let { playback.reportProgress(it, position()) }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.w(TAG, "Music playback failed", error)
            reportCurrentStopped(null, failed = true)
            // One broken file should not end the evening: move on when there is more.
            val p = player ?: return
            if (p.hasNextMediaItem()) {
                toast(R.string.music_track_failed)
                p.seekToNextMediaItem()
                p.prepare()
                p.play()
            } else {
                _state.update { it.copy(failed = true, loading = false) }
            }
        }
    }

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, failed = false) }
            try {
                val sourceId = UUID.fromString(route.sourceId)
                val source = details.details(sourceId).item
                val tracks = when (source.kind) {
                    ItemKind.Artist -> music.tracksOf(music.artistAlbums(sourceId))
                    ItemKind.Playlist -> music.playlistTracks(sourceId)
                    else -> music.albumTracks(sourceId)
                }
                if (tracks.isEmpty()) error("Nothing to play in ${source.kind}")
                sourceOrder = tracks.mapIndexed { i, track -> QueueEntry(i, track) }
                sources = sourceOrder.associate { it.key to playback.audioSource(it.track) }
                val start = route.startTrackId?.let { id -> tracks.indexOfFirst { it.id.toString() == id }.takeIf { it >= 0 } }
                val order = MusicQueue.start(sourceOrder, start, shuffleSwitch.on.value)
                _state.update {
                    it.copy(sourceKind = source.kind, sourceTitle = source.title, queue = order.items, index = order.index, shuffle = shuffleSwitch.on.value)
                }
                startPlayer(order)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Loading the queue failed", e)
                _state.update { it.copy(loading = false, failed = true) }
            }
        }
    }

    private fun startPlayer(order: QueueOrder<QueueEntry>) {
        releasePlayer()
        val headers = sources.values.first().headers
        val p = GlacierPlayer.createAudio(context, headers)
        p.addListener(listener)
        p.repeatMode = _state.value.repeat.toPlayer()
        p.setMediaItems(order.items.map(::mediaItem), order.index, 0)
        p.prepare()
        p.play()
        player = p
        syncIndex()
        startTicker()
    }

    private fun mediaItem(entry: QueueEntry) = sources.getValue(entry.key).let { GlacierPlayer.audioItem(entry.key.toString(), it.url, it.isHls) }

    /** Takes the queue position from the player, which moves on by itself. */
    private fun syncIndex() {
        val p = player ?: return
        val index = p.currentMediaItemIndex.coerceAtLeast(0)
        val entry = _state.value.queue.getOrNull(index)
        _state.update { it.copy(index = index, method = entry?.let { e -> sources[e.key]?.method }) }
        _progress.value = MusicProgress(0, entry?.track?.durationMs ?: 0)
        entry?.track?.let(::loadLyrics)
    }

    private fun loadLyrics(track: MusicTrack) {
        if (lyricsCache.containsKey(track.id)) {
            _state.update { it.copy(lyrics = lyricsCache[track.id]?.let(LyricsState::Loaded) ?: LyricsState.None) }
            return
        }
        lyricsJob?.cancel()
        _state.update { it.copy(lyrics = LyricsState.Loading) }
        lyricsJob = viewModelScope.launch {
            val lyrics = try {
                music.lyrics(track.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Loading lyrics failed", e)
                null
            }
            lyricsCache[track.id] = lyrics
            if (_state.value.current?.id == track.id) {
                _state.update { it.copy(lyrics = lyrics?.let(LyricsState::Loaded) ?: LyricsState.None) }
            }
        }
    }

    fun togglePlay() {
        val p = player ?: return
        when {
            // After the last song: play it again from the start.
            p.playbackState == Player.STATE_ENDED -> {
                p.seekTo(p.currentMediaItemIndex, 0)
                p.play()
            }
            p.playWhenReady -> p.pause()
            else -> p.play()
        }
    }

    fun pause() {
        player?.pause()
    }

    fun next() {
        val p = player ?: return
        when {
            p.hasNextMediaItem() -> p.seekToNextMediaItem()
            _state.value.repeat != RepeatMode.Off -> p.seekTo(0, 0)
            else -> return toast(R.string.music_queue_end)
        }
        p.play()
    }

    /** Within the first 3 s the previous song, else the start of this one (design `mPrev`). */
    fun previous() {
        val p = player ?: return
        p.seekToPrevious()
        p.play()
    }

    fun seekBy(deltaMs: Long) {
        val p = player ?: return
        val duration = p.duration.takeIf { it > 0 } ?: _progress.value.durationMs
        val target = (p.currentPosition + deltaMs).coerceIn(0, (duration - 1).coerceAtLeast(0))
        p.seekTo(target)
        _progress.update { it.copy(positionMs = target) }
    }

    fun seekTo(positionMs: Long) {
        val p = player ?: return
        p.seekTo(positionMs)
        p.play()
        _progress.update { it.copy(positionMs = positionMs) }
    }

    fun playAt(index: Int) {
        val p = player ?: return
        p.seekTo(index, 0)
        p.play()
    }

    fun toggleShuffle() {
        val p = player ?: return
        val current = _state.value
        val on = !current.shuffle
        shuffleSwitch.set(on)
        val playing = current.queue.getOrNull(current.index) ?: return
        if (on) {
            val queue = MusicQueue.shuffleRest(current.queue, current.index)
            replaceAround(p, current.index, queue.take(current.index), queue.drop(current.index + 1))
            _state.update { it.copy(shuffle = true, queue = queue) }
        } else {
            val order = MusicQueue.restore(sourceOrder, playing)
            replaceAround(p, current.index, order.items.take(order.index), order.items.drop(order.index + 1))
            _state.update { it.copy(shuffle = false, queue = order.items, index = order.index) }
        }
        toast(if (on) R.string.shuffle_on else R.string.shuffle_off)
    }

    /** Swaps everything around the playing song, which plays on undisturbed. */
    private fun replaceAround(p: ExoPlayer, index: Int, before: List<QueueEntry>, after: List<QueueEntry>) {
        p.removeMediaItems(index + 1, p.mediaItemCount)
        p.removeMediaItems(0, index)
        p.addMediaItems(0, before.map(::mediaItem))
        p.addMediaItems(after.map(::mediaItem))
    }

    /** Off → all songs → this song → off (design). */
    fun cycleRepeat() {
        val repeat = when (_state.value.repeat) {
            RepeatMode.Off -> RepeatMode.All
            RepeatMode.All -> RepeatMode.One
            RepeatMode.One -> RepeatMode.Off
        }
        player?.repeatMode = repeat.toPlayer()
        _state.update { it.copy(repeat = repeat) }
        toast(
            when (repeat) {
                RepeatMode.Off -> R.string.repeat_off
                RepeatMode.All -> R.string.repeat_all
                RepeatMode.One -> R.string.repeat_one
            },
        )
    }

    fun toggleLyrics() = _state.update { it.copy(lyricsOn = !it.lyricsOn) }

    /** "Stop" and Back: music ends with the player screen (no background playback). */
    fun stop() {
        if (closing) return
        closing = true
        player?.pause()
        reportCurrentStopped(null)
        _finished.trySend(Unit)
    }

    private fun reportCurrentStarted() {
        val entry = _state.value.queue.getOrNull(_state.value.index) ?: return
        val source = sources[entry.key] ?: return
        reported = source
        lastPositionMs = 0
        playback.reportStart(source, position())
    }

    private fun reportCurrentStopped(atMs: Long?, failed: Boolean = false) {
        val source = reported ?: return
        reported = null
        playback.reportStopped(source, atMs ?: lastPositionMs, failed)
    }

    private fun sourceTrack(source: PlaybackSource): MusicTrack? = sourceOrder.firstOrNull { it.track.id == source.itemId }?.track

    private fun startTicker() {
        ticker?.cancel()
        ticker = viewModelScope.launch {
            var sinceReport = 0L
            while (isActive) {
                val p = player ?: break
                val position = p.currentPosition
                lastPositionMs = position
                _progress.value = MusicProgress(position, p.duration.takeIf { it > 0 } ?: _state.value.current?.durationMs ?: 0)
                sinceReport += TICK_MS
                if (sinceReport >= REPORT_INTERVAL_MS && p.isPlaying) {
                    sinceReport = 0
                    reported?.let { playback.reportProgress(it, position()) }
                }
                delay(TICK_MS)
            }
        }
    }

    private fun position() = PlaybackPosition(player?.currentPosition ?: lastPositionMs, paused = player?.isPlaying != true)

    private fun toast(message: Int) {
        viewModelScope.launch { _events.send(UiEvent.Toast(message)) }
    }

    private fun releasePlayer() {
        ticker?.cancel()
        player?.let {
            it.removeListener(listener)
            it.release()
        }
        player = null
    }

    override fun onCleared() {
        reportCurrentStopped(null)
        releasePlayer()
    }

    private fun RepeatMode.toPlayer() = when (this) {
        RepeatMode.Off -> Player.REPEAT_MODE_OFF
        RepeatMode.All -> Player.REPEAT_MODE_ALL
        RepeatMode.One -> Player.REPEAT_MODE_ONE
    }

    private companion object {
        const val TAG = "MusicPlayer"
        const val TICK_MS = 250L
        const val REPORT_INTERVAL_MS = 10_000L
    }
}
