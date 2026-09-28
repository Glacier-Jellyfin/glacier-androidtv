package io.github.glacier_jellyfin.androidtv.player

import android.content.Context
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.data.media.Chapter
import io.github.glacier_jellyfin.androidtv.core.data.media.Track
import io.github.glacier_jellyfin.androidtv.core.data.media.TrackSelection
import io.github.glacier_jellyfin.androidtv.core.data.media.TrackSelections
import io.github.glacier_jellyfin.androidtv.core.data.playback.PlaybackSubtitle
import io.github.glacier_jellyfin.androidtv.core.data.playback.SubtitleDelivery
import io.github.glacier_jellyfin.androidtv.core.player.SideloadedSubtitle
import io.github.glacier_jellyfin.androidtv.core.player.TrackControl
import io.github.glacier_jellyfin.androidtv.detail.TrackKind
import io.github.glacier_jellyfin.androidtv.ui.UiEvent
import io.github.glacier_jellyfin.androidtv.core.data.media.DetailRepository
import io.github.glacier_jellyfin.androidtv.core.data.media.ItemDetails
import io.github.glacier_jellyfin.androidtv.core.data.playback.PlaybackMethod
import io.github.glacier_jellyfin.androidtv.core.data.playback.PlaybackPosition
import io.github.glacier_jellyfin.androidtv.core.data.playback.PlaybackRepository
import io.github.glacier_jellyfin.androidtv.core.data.playback.PlaybackSource
import io.github.glacier_jellyfin.androidtv.core.player.GlacierPlayer
import io.github.glacier_jellyfin.androidtv.core.player.StreamRequest
import io.github.glacier_jellyfin.androidtv.navigation.PlayerRoute
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

data class PlayerUiState(
    val details: ItemDetails? = null,
    val player: ExoPlayer? = null,
    /** Until the first frame can be shown. */
    val loading: Boolean = true,
    val failed: Boolean = false,
    val method: PlaybackMethod? = null,
    val playing: Boolean = false,
    /** Playing or about to (buffering after a seek); drives the play/pause button. */
    val playWhenReady: Boolean = true,
    val audioTracks: List<Track> = emptyList(),
    val subtitles: List<PlaybackSubtitle> = emptyList(),
    val audioIndex: Int? = null,
    /** Null: subtitles off. */
    val subtitleIndex: Int? = null,
    val trackPanel: TrackKind? = null,
    val chaptersOpen: Boolean = false,
    /** Headers for authenticated images (trickplay tiles). */
    val imageHeaders: Map<String, String> = emptyMap(),
) {
    val chapters: List<Chapter> get() = details?.chapters.orEmpty()
}

/** Position data, kept apart from [PlayerUiState] so ticking only redraws the timeline. */
data class PlayerProgress(
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val bufferedMs: Long = 0,
)

@HiltViewModel
class PlayerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @param:ApplicationContext private val context: Context,
    private val details: DetailRepository,
    private val playback: PlaybackRepository,
    private val trackSelections: TrackSelections,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<PlayerRoute>()
    private val itemId = UUID.fromString(route.itemId)

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private val _progress = MutableStateFlow(PlayerProgress())
    val progress: StateFlow<PlayerProgress> = _progress.asStateFlow()

    /** Fires when playback is over and the screen should close. */
    private val _finished = Channel<Unit>(Channel.CONFLATED)
    val finished = _finished.receiveAsFlow()

    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var source: PlaybackSource? = null
    private var started = false
    private var ticker: Job? = null
    /** Tracks to apply once Media3 knows the file's tracks. */
    private var tracksPending = false

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> {
                    _state.update { it.copy(loading = false) }
                    if (!started) {
                        started = true
                        source?.let { playback.reportStart(it, position()) }
                    }
                }
                Player.STATE_ENDED -> finish()
                else -> Unit
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            _state.update { it.copy(playWhenReady = playWhenReady) }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.update { it.copy(playing = isPlaying) }
            if (started) source?.let { playback.reportProgress(it, position()) }
        }

        override fun onTracksChanged(tracks: Tracks) {
            if (tracksPending) tracksPending = !applyTracks()
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.w(TAG, "Playback failed", error)
            source?.let { playback.reportStopped(it, currentPositionMs(), failed = true) }
            _state.update { it.copy(failed = true, loading = false) }
        }
    }

    init {
        load()
    }

    fun load() {
        val selection = trackSelections.get(itemId)
        // A choice made on the detail page wins; "off" there is sent as -1 so the server does not pick one.
        open(startMs = null, audio = selection?.audio, subtitle = selection?.let { it.subtitle ?: NO_SUBTITLE })
    }

    /** [startMs] null: the saved resume point (or 0 for "from start"). */
    private fun open(startMs: Long?, audio: Int?, subtitle: Int?) {
        viewModelScope.launch {
            releasePlayer()
            _state.update { it.copy(loading = true, failed = false, trackPanel = null) }
            try {
                val item = _state.value.details ?: details.details(itemId).also { d -> _state.update { it.copy(details = d) } }
                val start = startMs ?: if (route.fromStart) 0 else item.item.resumePositionMs
                val opened = playback.open(itemId, start, audio, subtitle)
                source = opened
                val request = StreamRequest(
                    url = opened.url,
                    isHls = opened.isHls,
                    headers = opened.headers,
                    startPositionMs = start,
                    subtitles = opened.subtitles
                        .filter { it.delivery == SubtitleDelivery.External && it.url != null }
                        .map { SideloadedSubtitle(it.track.index, it.url!!, it.track.codec, it.track.language) },
                )
                val player = GlacierPlayer.create(context, request)
                player.addListener(listener)
                player.playWhenReady = true
                tracksPending = true
                _state.update {
                    it.copy(
                        player = player,
                        method = opened.method,
                        audioTracks = opened.audioTracks,
                        subtitles = opened.subtitles,
                        audioIndex = opened.audioIndex,
                        subtitleIndex = opened.subtitleIndex,
                        imageHeaders = opened.headers,
                    )
                }
                startTicker()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Opening playback failed", e)
                _state.update { it.copy(failed = true, loading = false) }
            }
        }
    }

    fun openTracks(kind: TrackKind) = _state.update { it.copy(trackPanel = kind, chaptersOpen = false) }

    fun closeTracks() = _state.update { it.copy(trackPanel = null) }

    fun openChapters() = _state.update { it.copy(chaptersOpen = true, trackPanel = null) }

    fun closeChapters() = _state.update { it.copy(chaptersOpen = false) }

    fun playChapter(chapter: Chapter) {
        seekTo(chapter.startMs)
        closeChapters()
    }

    /** Applies at once: switched in the player when the stream has the track, else the server sends a new stream. */
    fun pickAudio(index: Int, label: String) {
        val current = source ?: return
        _state.update { it.copy(trackPanel = null, audioIndex = index) }
        remember(audio = index, subtitle = _state.value.subtitleIndex)
        toast(R.string.track_audio_set, label)
        val ordinal = current.audioOrdinal(index)
        val player = _state.value.player
        if (current.allAudioInStream && ordinal != null && player != null) {
            TrackControl.selectAudio(player, ordinal)
            source = current.copy(audioIndex = index)
            playback.reportProgress(current.copy(audioIndex = index), position())
        } else {
            reopen(audio = index, subtitle = _state.value.subtitleIndex)
        }
    }

    fun pickSubtitle(index: Int?, label: String) {
        val current = source ?: return
        val from = current.subtitles.firstOrNull { it.track.index == _state.value.subtitleIndex }?.delivery
        val to = current.subtitles.firstOrNull { it.track.index == index }?.delivery
        _state.update { it.copy(trackPanel = null, subtitleIndex = index) }
        remember(audio = _state.value.audioIndex, subtitle = index)
        toast(R.string.track_subtitles_set, label)
        // Burned-in subtitles are part of the picture: adding, changing or removing them needs a new stream.
        if (from == SubtitleDelivery.BurnIn || to == SubtitleDelivery.BurnIn) {
            reopen(audio = _state.value.audioIndex, subtitle = index)
        } else {
            source = current.copy(subtitleIndex = index)
            applyTracks()
            playback.reportProgress(current.copy(subtitleIndex = index), position())
        }
    }

    private fun reopen(audio: Int?, subtitle: Int?) {
        val position = currentPositionMs()
        source?.let { playback.reportStopped(it, position) }
        source = null
        open(startMs = position, audio = audio, subtitle = subtitle ?: NO_SUBTITLE)
    }

    /** Selects the chosen tracks in Media3; false while its track list is not known yet. */
    private fun applyTracks(): Boolean {
        val player = _state.value.player ?: return false
        val current = source ?: return false
        if (player.currentTracks.isEmpty) return false
        val audio = _state.value.audioIndex
        if (audio != null && current.allAudioInStream) current.audioOrdinal(audio)?.let { TrackControl.selectAudio(player, it) }
        val subtitle = current.subtitles.firstOrNull { it.track.index == _state.value.subtitleIndex }
        when (subtitle?.delivery) {
            SubtitleDelivery.Embedded -> current.subtitleOrdinal(subtitle.track.index)?.let { TrackControl.selectEmbeddedText(player, it) }
            SubtitleDelivery.External -> TrackControl.selectExternalText(player, subtitle.track.index)
            // Off, or burned into the picture: no text track.
            SubtitleDelivery.BurnIn, null -> TrackControl.disableText(player)
        }
        return true
    }

    private fun remember(audio: Int?, subtitle: Int?) = trackSelections.set(itemId, TrackSelection(audio, subtitle))

    private fun toast(message: Int, arg: String) {
        viewModelScope.launch { _events.send(UiEvent.Toast(message, listOf(arg))) }
    }

    fun togglePlay() {
        val player = _state.value.player ?: return
        if (player.playWhenReady) player.pause() else player.play()
    }

    fun pause() {
        _state.value.player?.pause()
    }

    fun seekTo(positionMs: Long) {
        val player = _state.value.player ?: return
        player.seekTo(positionMs.coerceIn(0, player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE))
        _progress.update { it.copy(positionMs = player.currentPosition) }
    }

    fun seekBy(deltaMs: Long) = seekTo(currentPositionMs() + deltaMs)

    /** "Stop" and Back: report where playback ended, then close. */
    fun stop() = finish()

    private fun finish() {
        source?.let { playback.reportStopped(it, currentPositionMs()) }
        source = null
        _finished.trySend(Unit)
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = viewModelScope.launch {
            var sinceReport = 0L
            while (isActive) {
                val player = _state.value.player ?: break
                _progress.value = PlayerProgress(
                    positionMs = player.currentPosition,
                    durationMs = player.duration.coerceAtLeast(0),
                    bufferedMs = player.bufferedPosition,
                )
                sinceReport += TICK_MS
                if (sinceReport >= REPORT_INTERVAL_MS && player.isPlaying) {
                    sinceReport = 0
                    source?.let { playback.reportProgress(it, position()) }
                }
                delay(TICK_MS)
            }
        }
    }

    private fun currentPositionMs(): Long = _state.value.player?.currentPosition ?: _progress.value.positionMs

    private fun position() = PlaybackPosition(currentPositionMs(), paused = _state.value.player?.isPlaying != true)

    private fun releasePlayer() {
        ticker?.cancel()
        _state.value.player?.let {
            it.removeListener(listener)
            it.release()
        }
        _state.update { it.copy(player = null) }
        started = false
    }

    override fun onCleared() {
        // Leaving without Stop (Home button, process going away) still records the resume point.
        source?.let { playback.reportStopped(it, currentPositionMs()) }
        releasePlayer()
    }

    private companion object {
        const val TAG = "Player"
        const val TICK_MS = 500L
        const val REPORT_INTERVAL_MS = 10_000L
        /** The server's "no subtitles". */
        const val NO_SUBTITLE = -1
    }
}
