package io.github.glacier_jellyfin.androidtv.music

import android.content.Context
import android.content.Intent
import io.github.glacier_jellyfin.androidtv.core.log.Log
import android.view.KeyEvent
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.data.SessionManager
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
import io.github.glacier_jellyfin.androidtv.core.data.settings.SettingsRepository
import io.github.glacier_jellyfin.androidtv.core.player.GlacierPlayer
import io.github.glacier_jellyfin.androidtv.core.player.SongMetadata
import io.github.glacier_jellyfin.androidtv.ui.UiEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

enum class RepeatMode { Off, All, One }

/** A song in the queue; [key] is its place in the source, which stays unique when a playlist lists a song twice. */
data class QueueEntry(val key: Int, val track: MusicTrack)

sealed interface LyricsState {
    data object Loading : LyricsState
    data object None : LyricsState
    data class Loaded(val lyrics: Lyrics) : LyricsState
}

data class MusicUiState(
    val loading: Boolean = false,
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
    /** Left/Right on the timeline and the remote's rewind/fast-forward (Settings › Playback). */
    val seekBackMs: Long = 10_000,
    val seekForwardMs: Long = 30_000,
) {
    val current: MusicTrack? get() = queue.getOrNull(index)?.track
}

/** Position data, kept apart from [MusicUiState] so ticking only redraws what moves. */
data class MusicProgress(val positionMs: Long = 0, val durationMs: Long = 0)

/** How to load a queue again ([MusicController.retry]): its songs, and the one to start with. */
private class QueueRequest(val startTrackId: String?, val load: suspend () -> LoadedQueue)

/** A loaded queue: what it was made from ([ItemKind.Other] for an instant mix, null for loose songs), its title and songs. */
private data class LoadedQueue(val kind: ItemKind?, val title: String, val tracks: List<MusicTrack>)

/** The song in the mini player. */
data class NowPlaying(val track: MusicTrack, val playing: Boolean)

/**
 * The app's music playback, outliving the player screen: songs keep playing
 * while the user moves through the app or leaves it ([MusicService] keeps the
 * process and the media session alive). Ends with [stop] (the remote's stop
 * key), a profile switch or sign-out, at the end of the queue, or after ten
 * minutes paused.
 */
@Singleton
class MusicController @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val details: DetailRepository,
    private val music: MusicRepository,
    private val playback: PlaybackRepository,
    private val shuffleSwitch: MusicShuffle,
    settings: SettingsRepository,
    sessions: SessionManager,
) {
    private val scope = MainScope()

    private val _state = MutableStateFlow(MusicUiState(shuffle = shuffleSwitch.on.value))
    val state: StateFlow<MusicUiState> = _state.asStateFlow()

    private val _progress = MutableStateFlow(MusicProgress())
    val progress: StateFlow<MusicProgress> = _progress.asStateFlow()

    /** Notes for the player screen; dropped while it is not shown. */
    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    /** The player while a queue is loaded; [MusicService] builds its media session on it. */
    private val _player = MutableStateFlow<ExoPlayer?>(null)
    val player: StateFlow<ExoPlayer?> = _player.asStateFlow()

    val nowPlaying: StateFlow<NowPlaying?> = state
        .map { s -> s.current?.let { NowPlaying(it, s.playWhenReady) } }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** Music is playing or about to: theme songs stay silent and media keys belong to it. */
    val isPlaying: Boolean get() = _player.value != null && _state.value.playWhenReady

    private val activePlayer: ExoPlayer? get() = _player.value
    /** What the queue was made from, for [retry]. */
    private var request: QueueRequest? = null
    private var loadJob: Job? = null
    /** The source's songs in their own order, to return to when shuffle goes off. */
    private var sourceOrder: List<QueueEntry> = emptyList()
    private var sources: Map<Int, PlaybackSource> = emptyMap()
    /** Key for the next song added to the queue ([QueueEntry.key]). */
    private var nextKey = 0
    /** The song reported to the server as playing, and where it last was. */
    private var reported: PlaybackSource? = null
    private var lastPositionMs = 0L
    private val lyricsCache = mutableMapOf<UUID, Lyrics?>()
    private var lyricsJob: Job? = null
    private var ticker: Job? = null
    /** Open player screens; music that ends while one shows stays on it until it closes. */
    private var screens = 0
    /** The queue ran out; the last song waits, paused. */
    private var ended = false
    private var idleStop: Job? = null

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
                    // The end of the queue: the last song stays shown, paused (design), while the
                    // player screen is open; elsewhere the mini player goes.
                    reportCurrentStopped(_state.value.current?.durationMs)
                    ended = true
                    activePlayer?.pause()
                    // After the callback, not inside it: stop() releases this player.
                    if (screens == 0) scope.launch { stop() }
                }
                else -> Unit
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            _state.update { it.copy(playWhenReady = playWhenReady) }
            if (playWhenReady) {
                ended = false
                idleStop?.cancel()
            } else {
                scheduleIdleStop()
            }
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
            val p = activePlayer ?: return
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
        scope.launch {
            settings.settings.collect { profile ->
                _state.update {
                    it.copy(seekBackMs = profile.playback.seekBack.ms, seekForwardMs = profile.playback.seekForward.ms, lyricsOn = profile.music.lyrics)
                }
            }
        }
        // Another profile does not inherit the music.
        scope.launch {
            sessions.session.map { it?.server?.id to it?.user?.userId }.distinctUntilChanged().drop(1).collect { stop() }
        }
    }

    /** Plays the songs of an album, artist or playlist, from [startTrackId] if given. */
    fun play(sourceId: UUID, startTrackId: String?) {
        request = QueueRequest(startTrackId) {
            val source = details.details(sourceId).item
            val tracks = when (source.kind) {
                ItemKind.Artist -> music.tracksOf(music.artistAlbums(sourceId))
                ItemKind.Playlist -> music.playlistTracks(sourceId)
                else -> music.albumTracks(sourceId)
            }
            LoadedQueue(source.kind, source.title, tracks)
        }
        load()
    }

    /**
     * Songs from elsewhere in place of the queue: an instant mix ([kind] Other, see
     * MusicRepository.instantMix) or the favorite songs (null, a plain queue).
     */
    fun playTracks(kind: ItemKind?, title: String, tracks: List<MusicTrack>, startTrackId: String? = null) {
        request = QueueRequest(startTrackId) { LoadedQueue(kind, title, tracks) }
        load()
    }

    /** Loads the last [play] again after it failed. */
    fun retry() {
        if (request != null) load()
    }

    private fun load() {
        val request = request ?: return
        loadJob?.cancel()
        activePlayer?.pause()
        reportCurrentStopped(null)
        _state.update { it.copy(loading = true, failed = false) }
        loadJob = scope.launch {
            try {
                val (kind, title, tracks) = request.load()
                if (tracks.isEmpty()) error("Nothing to play in $kind")
                sourceOrder = tracks.mapIndexed { i, track -> QueueEntry(i, track) }
                nextKey = tracks.size
                sources = sourceOrder.associate { it.key to playback.audioSource(it.track) }
                val start = request.startTrackId?.let { id -> tracks.indexOfFirst { it.id.toString() == id }.takeIf { it >= 0 } }
                val order = MusicQueue.start(sourceOrder, start, shuffleSwitch.on.value)
                _state.update {
                    it.copy(sourceKind = kind, sourceTitle = title, queue = order.items, index = order.index, shuffle = shuffleSwitch.on.value)
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
        val old = activePlayer
        val headers = sources.values.first().headers
        val p = GlacierPlayer.createAudio(context, headers)
        p.addListener(listener)
        p.repeatMode = _state.value.repeat.toPlayer()
        p.setMediaItems(order.items.map(::mediaItem), order.index, 0)
        p.prepare()
        p.play()
        // The service moves its session over before the old player goes.
        _player.value = p
        old?.let(::release)
        context.startService(Intent(context, MusicService::class.java))
        syncIndex()
        startTicker()
    }

    private fun mediaItem(entry: QueueEntry) = sources.getValue(entry.key).let {
        val track = entry.track
        GlacierPlayer.audioItem(entry.key.toString(), it.url, it.isHls, SongMetadata(track.title, track.artist, track.album, track.largeCoverUrl ?: track.coverUrl))
    }

    /** Takes the queue position from the player, which moves on by itself. */
    private fun syncIndex() {
        val p = activePlayer ?: return
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
        lyricsJob = scope.launch {
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
        val p = activePlayer ?: return
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
        activePlayer?.pause()
    }

    fun next() {
        val p = activePlayer ?: return
        when {
            p.hasNextMediaItem() -> p.seekToNextMediaItem()
            _state.value.repeat != RepeatMode.Off -> p.seekTo(0, 0)
            else -> return toast(R.string.music_queue_end)
        }
        p.play()
    }

    /** Within the first 3 s the previous song, else the start of this one (design `mPrev`). */
    fun previous() {
        val p = activePlayer ?: return
        p.seekToPrevious()
        p.play()
    }

    fun seekBy(deltaMs: Long) {
        val p = activePlayer ?: return
        val duration = p.duration.takeIf { it > 0 } ?: _progress.value.durationMs
        val target = (p.currentPosition + deltaMs).coerceIn(0, (duration - 1).coerceAtLeast(0))
        p.seekTo(target)
        _progress.update { it.copy(positionMs = target) }
    }

    fun seekTo(positionMs: Long) {
        val p = activePlayer ?: return
        p.seekTo(positionMs)
        p.play()
        _progress.update { it.copy(positionMs = positionMs) }
    }

    fun playAt(index: Int) {
        val p = activePlayer ?: return
        p.seekTo(index, 0)
        p.play()
    }

    /** Songs right after the current one; with no music loaded they start playing. */
    fun playNext(tracks: List<MusicTrack>) = insert(tracks, next = true)

    /** Songs at the end of the queue; with no music loaded they start playing. */
    fun addToQueue(tracks: List<MusicTrack>) = insert(tracks, next = false)

    private fun insert(tracks: List<MusicTrack>, next: Boolean) {
        if (tracks.isEmpty()) return
        val entries = tracks.map { QueueEntry(nextKey++, it) }
        sources = sources + entries.associate { it.key to playback.audioSource(it.track) }
        val p = activePlayer
        if (p == null) {
            loadJob?.cancel()
            request = null
            sourceOrder = entries
            _state.update { it.copy(loading = true, failed = false, sourceKind = null, sourceTitle = "", queue = entries, index = 0) }
            startPlayer(QueueOrder(entries, 0))
            return
        }
        val current = _state.value
        val at = if (next) (current.index + 1).coerceAtMost(current.queue.size) else current.queue.size
        p.addMediaItems(at, entries.map(::mediaItem))
        // The source order (for shuffle off) takes them at the same place relative to the current song.
        val orderAt = if (next) {
            sourceOrder.indexOf(current.queue.getOrNull(current.index)).let { if (it < 0) sourceOrder.size else it + 1 }
        } else {
            sourceOrder.size
        }
        sourceOrder = sourceOrder.take(orderAt) + entries + sourceOrder.drop(orderAt)
        _state.update { it.copy(queue = it.queue.take(at) + entries + it.queue.drop(at)) }
        // A queue that had run out goes on with the new songs.
        if (ended) {
            p.seekTo(at, 0)
            p.play()
        }
    }

    /** Takes a song out of the queue; the one playing stays. */
    fun removeAt(index: Int) {
        val p = activePlayer ?: return
        val current = _state.value
        if (index == current.index || index !in current.queue.indices) return
        val entry = current.queue[index]
        p.removeMediaItem(index)
        sourceOrder = sourceOrder - entry
        _state.update { it.copy(queue = it.queue.filterIndexed { i, _ -> i != index }, index = p.currentMediaItemIndex) }
    }

    /** Moves a queued song to right after the current one. */
    fun moveNext(index: Int) {
        val p = activePlayer ?: return
        val current = _state.value
        if (index == current.index || index !in current.queue.indices) return
        // Taking out a song before the current one shifts the current one up.
        val target = if (index < current.index) current.index else current.index + 1
        if (target == index) return
        p.moveMediaItem(index, target)
        val queue = current.queue.toMutableList().apply { add(target, removeAt(index)) }
        _state.update { it.copy(queue = queue, index = p.currentMediaItemIndex) }
    }

    fun toggleShuffle() {
        val p = activePlayer ?: return
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
        activePlayer?.repeatMode = repeat.toPlayer()
        _state.update { it.copy(repeat = repeat) }
        toast(
            when (repeat) {
                RepeatMode.Off -> R.string.repeat_off
                RepeatMode.All -> R.string.repeat_all
                RepeatMode.One -> R.string.repeat_one
            },
        )
    }

    /** The heart in the player: the song that plays becomes a favorite, or stops being one. */
    fun toggleFavorite() {
        val track = _state.value.current ?: return
        val favorite = !track.isFavorite
        setFavorite(track.id, favorite)
        scope.launch {
            try {
                details.setFavorite(track.id, favorite)
                toast(if (favorite) R.string.music_favorite_added else R.string.music_favorite_removed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Changing the favorite failed", e)
                setFavorite(track.id, !favorite)
            }
        }
    }

    /** Every entry of the song in the queue (a playlist may hold it twice). */
    private fun setFavorite(trackId: UUID, favorite: Boolean) {
        val change = { entry: QueueEntry -> if (entry.track.id == trackId) entry.copy(track = entry.track.copy(isFavorite = favorite)) else entry }
        sourceOrder = sourceOrder.map(change)
        _state.update { it.copy(queue = it.queue.map(change)) }
    }

    /** Kept for the profile; the settings flow brings it back into the state. */
    fun toggleLyrics() {
        val on = !_state.value.lyricsOn
        _state.update { it.copy(lyricsOn = on) }
        shuffleSwitch.setLyrics(on)
    }

    /**
     * A media key no screen used ([keyCode] of android.view.KeyEvent): it
     * belongs to the music while some is loaded.
     */
    fun onMediaKey(keyCode: Int): Boolean {
        if (!ownsKey(keyCode)) return false
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> togglePlay()
            KeyEvent.KEYCODE_MEDIA_PLAY -> activePlayer?.play()
            KeyEvent.KEYCODE_MEDIA_PAUSE -> pause()
            KeyEvent.KEYCODE_MEDIA_NEXT -> next()
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> previous()
            KeyEvent.KEYCODE_MEDIA_STOP -> stop()
        }
        return true
    }

    /** The key's other half (key up) is the music's too, so the system does not act on it again. */
    fun ownsKey(keyCode: Int): Boolean = activePlayer != null && keyCode in MediaKeys

    /** The player screen opened; see [screens]. */
    fun screenShown() {
        screens++
    }

    /** The player screen closed: music that ended meanwhile goes now, paused music starts its wait. */
    fun screenGone() {
        screens = (screens - 1).coerceAtLeast(0)
        if (screens > 0 || activePlayer == null) return
        when {
            ended -> stop()
            !_state.value.playWhenReady -> scheduleIdleStop()
        }
    }

    /** Music paused for [IDLE_STOP_MS] ends, unless the player screen is open. */
    private fun scheduleIdleStop() {
        idleStop?.cancel()
        idleStop = scope.launch {
            delay(IDLE_STOP_MS)
            if (screens == 0) stop()
        }
    }

    /** Ends the music: the queue goes, and with it the mini player and the media session. */
    fun stop() {
        idleStop?.cancel()
        ended = false
        loadJob?.cancel()
        request = null
        reportCurrentStopped(null)
        ticker?.cancel()
        val old = activePlayer
        _player.value = null
        old?.let(::release)
        sourceOrder = emptyList()
        sources = emptyMap()
        _progress.value = MusicProgress()
        _state.update { MusicUiState(shuffle = it.shuffle, repeat = it.repeat, lyricsOn = it.lyricsOn, seekBackMs = it.seekBackMs, seekForwardMs = it.seekForwardMs) }
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
        ticker = scope.launch {
            var sinceReport = 0L
            while (isActive) {
                val p = activePlayer ?: break
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

    private fun position() = PlaybackPosition(activePlayer?.currentPosition ?: lastPositionMs, paused = activePlayer?.isPlaying != true)

    private fun toast(message: Int) {
        _events.tryEmit(UiEvent.Toast(message))
    }

    private fun release(p: ExoPlayer) {
        p.removeListener(listener)
        p.release()
    }

    private fun RepeatMode.toPlayer() = when (this) {
        RepeatMode.Off -> Player.REPEAT_MODE_OFF
        RepeatMode.All -> Player.REPEAT_MODE_ALL
        RepeatMode.One -> Player.REPEAT_MODE_ONE
    }

    private companion object {
        const val TAG = "MusicController"
        const val TICK_MS = 250L
        const val REPORT_INTERVAL_MS = 10_000L
        const val IDLE_STOP_MS = 10 * 60_000L
        val MediaKeys = setOf(
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_MEDIA_STOP,
        )
    }
}
