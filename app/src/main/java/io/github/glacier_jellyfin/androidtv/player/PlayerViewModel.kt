package io.github.glacier_jellyfin.androidtv.player

import android.content.Context
import io.github.glacier_jellyfin.androidtv.core.log.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.common.util.UnstableApi
import androidx.annotation.OptIn
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.glacier_jellyfin.androidtv.R
import io.github.glacier_jellyfin.androidtv.core.data.media.Chapter
import io.github.glacier_jellyfin.androidtv.core.data.media.ItemKind
import io.github.glacier_jellyfin.androidtv.core.data.media.MediaItem
import io.github.glacier_jellyfin.androidtv.core.data.media.Track
import io.github.glacier_jellyfin.androidtv.core.data.media.TrackSelection
import io.github.glacier_jellyfin.androidtv.core.data.media.TrackSelections
import io.github.glacier_jellyfin.androidtv.core.data.playback.MediaSegment
import io.github.glacier_jellyfin.androidtv.core.data.playback.PlaybackSubtitle
import io.github.glacier_jellyfin.androidtv.core.data.playback.SegmentAction
import io.github.glacier_jellyfin.androidtv.core.data.playback.SegmentKind
import io.github.glacier_jellyfin.androidtv.core.data.playback.SegmentPolicy
import io.github.glacier_jellyfin.androidtv.core.data.playback.SubtitleDelivery
import io.github.glacier_jellyfin.androidtv.core.data.settings.LastTracks
import io.github.glacier_jellyfin.androidtv.core.data.settings.RememberedTracks
import io.github.glacier_jellyfin.androidtv.core.data.settings.ServerPreferencesRepository
import io.github.glacier_jellyfin.androidtv.core.data.settings.SettingsRepository
import io.github.glacier_jellyfin.androidtv.core.data.settings.SubtitleStyle
import io.github.glacier_jellyfin.androidtv.core.data.settings.rememberedTracks
import io.github.glacier_jellyfin.androidtv.core.data.settings.toKey
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
import io.github.glacier_jellyfin.androidtv.core.data.playback.SourceFile
import io.github.glacier_jellyfin.androidtv.core.data.playback.TranscodeStatus
import io.github.glacier_jellyfin.androidtv.core.jellyfin.playback.HlsSegments
import io.github.glacier_jellyfin.androidtv.core.player.GlacierPlayback
import io.github.glacier_jellyfin.androidtv.core.player.GlacierPlayer
import io.github.glacier_jellyfin.androidtv.core.player.StreamRequest
import io.github.glacier_jellyfin.androidtv.music.MusicController
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
    /** The player with its ASS renderer, for the subtitle view. */
    val playback: GlacierPlayback? = null,
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
    /** The info sheet: overview and what is playing. */
    val infoOpen: Boolean = false,
    /** Decoders Media3 picked, for the info sheet ("c2.android.hevc.decoder", "ffmpegLib"). */
    val videoDecoder: String? = null,
    val audioDecoder: String? = null,
    /** What reaches the audio output: PCM or a passthrough bitstream. */
    val audioOutput: AudioOutput? = null,
    /** The file on the server, for the info sheet. */
    val file: SourceFile? = null,
    /** Headers for authenticated images (trickplay tiles). */
    val imageHeaders: Map<String, String> = emptyMap(),
    val segments: List<MediaSegment> = emptyList(),
    /** Neighbouring episodes of the show; null for films and at either end. */
    val previous: MediaItem? = null,
    val next: MediaItem? = null,
    /** "Watch credits" hides the "Up next" card for the rest of this episode. */
    val upNextDismissed: Boolean = false,
    /** Remote Left/Right and the skip buttons (Settings › Playback). */
    val seekBackMs: Long = 10_000,
    val seekForwardMs: Long = 30_000,
    val subtitleStyle: SubtitleStyle = SubtitleStyle(),
    /** Switch the TV to the video's frame rate (Settings › Playback). */
    val matchFrameRate: Boolean = true,
) {
    val chapters: List<Chapter> get() = details?.chapters.orEmpty()
    val isEpisode: Boolean get() = details?.item?.kind == ItemKind.Episode
}

/** Position data, kept apart from [PlayerUiState] so ticking only redraws the timeline. */
data class PlayerProgress(
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val bufferedMs: Long = 0,
    /** The segment offered for skipping right now. */
    val skip: MediaSegment? = null,
    val upNext: UpNextCountdown? = null,
)

/** The "Up next" card: time until the next episode starts and how much of the lead time is left. */
data class UpNextCountdown(val remainingMs: Long, val fraction: Float)

@HiltViewModel
class PlayerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @param:ApplicationContext private val context: Context,
    private val details: DetailRepository,
    private val playback: PlaybackRepository,
    private val trackSelections: TrackSelections,
    private val settings: SettingsRepository,
    private val serverPreferences: ServerPreferencesRepository,
    private val musicPlayback: MusicController,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<PlayerRoute>()
    /** Changes when playback moves on to another episode. */
    private var itemId = UUID.fromString(route.itemId)
    private var fromStart = route.fromStart
    /** Playlist or artist whose videos play one after another; its list once loaded. */
    private val queueOf = route.queueOf?.let(UUID::fromString)
    private var queue: List<MediaItem>? = null
    private var policy = SegmentPolicy()

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
    private var closing = false
    private var ticker: Job? = null
    private var opening: Job? = null
    /** Segments already skipped automatically; seeking back into one plays it. */
    private val autoSkipped = mutableSetOf<MediaSegment>()
    /** Tracks to apply once Media3 knows the file's tracks. */
    private var tracksPending = false
    /** Set once fMP4 segments failed for this title: it plays from MPEG-TS segments from then on. */
    private var segments: HlsSegments? = null

    @OptIn(UnstableApi::class)
    private val decoders = object : AnalyticsListener {
        @OptIn(UnstableApi::class)
        override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
            _state.update { it.copy(videoDecoder = decoderName) }
        }

        @OptIn(UnstableApi::class)
        override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
            _state.update { it.copy(audioDecoder = decoderName) }
        }

        @OptIn(UnstableApi::class)
        override fun onAudioTrackInitialized(eventTime: AnalyticsListener.EventTime, audioTrackConfig: AudioSink.AudioTrackConfig) {
            _state.update { it.copy(audioOutput = audioOutput(audioTrackConfig)) }
        }
    }

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
                Player.STATE_ENDED -> onEnded()
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

        // Report a seek at once: if the app is killed before the next periodic report,
        // the server would otherwise keep the position from before the jump.
        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK && started) source?.let { playback.reportProgress(it, position()) }
        }

        override fun onTracksChanged(tracks: Tracks) {
            if (tracksPending) tracksPending = !applyTracks()
        }

        override fun onPlayerError(error: PlaybackException) {
            val current = source
            // Some fMP4 segments from the server start at a negative time, which Media3 cannot read.
            if (current != null && current.fmp4Segments && segments == null && error.errorCode in FMP4_ERRORS) {
                Log.w(TAG, "Reading fMP4 segments failed, asking for MPEG-TS", error)
                segments = HlsSegments.Ts
                reopen(audio = _state.value.audioIndex, subtitle = _state.value.subtitleIndex)
                return
            }
            Log.w(TAG, "Playback failed", error)
            source?.let { playback.reportStopped(it, currentPositionMs(), failed = true) }
            _state.update { it.copy(failed = true, loading = false) }
        }
    }

    init {
        // A video ends the music; it does not come back on its own afterwards.
        musicPlayback.pause()
        viewModelScope.launch {
            settings.settings.collect { profile ->
                policy = profile.playback.segmentPolicy
                _state.update {
                    it.copy(
                        seekBackMs = profile.playback.seekBack.ms,
                        seekForwardMs = profile.playback.seekForward.ms,
                        subtitleStyle = profile.subtitleStyle,
                        matchFrameRate = profile.playback.matchFrameRate,
                    )
                }
            }
        }
        load()
    }

    fun load() {
        val selection = trackSelections.get(itemId)
        // A choice made on the detail page wins; "off" there is sent as -1 so the server does not pick one.
        open(startMs = null, audio = selection?.audio, subtitle = selection?.let { it.subtitle ?: NO_SUBTITLE }, fromLastTitle = selection == null)
    }

    /**
     * [startMs] null: the saved resume point (or 0 for "from start").
     * [fromLastTitle]: no tracks were chosen for this title, so the last title's may be taken over.
     */
    private fun open(startMs: Long?, audio: Int?, subtitle: Int?, fromLastTitle: Boolean = false) {
        opening?.cancel()
        opening = viewModelScope.launch {
            releasePlayer()
            _state.update { it.copy(loading = true, failed = false, trackPanel = null) }
            try {
                val item = _state.value.details ?: details.details(itemId).also { d ->
                    _state.update { it.copy(details = d) }
                    loadExtras(d)
                }
                val start = startMs ?: if (fromStart) 0 else item.item.resumePositionMs
                val remembered = if (fromLastTitle) {
                    val preferences = serverPreferences.preferences.value ?: serverPreferences.refresh()
                    preferences?.let { rememberedTracks(settings.current().lastTracks, it, item.tracks) }
                } else {
                    null
                }
                val opened = playback.open(itemId, start, remembered?.audio ?: audio, remembered?.subtitle ?: subtitle, segments)
                source = opened
                recordTracks(opened.audioIndex, opened.subtitleIndex)
                val request = StreamRequest(
                    url = opened.url,
                    isHls = opened.isHls,
                    headers = opened.headers,
                    startPositionMs = start,
                    subtitles = opened.subtitles
                        .filter { it.delivery == SubtitleDelivery.External && it.url != null }
                        .map { SideloadedSubtitle(it.track.index, it.url!!, it.track.codec, it.track.language) },
                )
                val playback = GlacierPlayer.create(context, request)
                val player = playback.player
                player.addListener(listener)
                addDecoderListener(player)
                player.playWhenReady = true
                tracksPending = true
                _state.update {
                    it.copy(
                        player = player,
                        playback = playback,
                        method = opened.method,
                        audioTracks = opened.audioTracks,
                        subtitles = opened.subtitles,
                        audioIndex = opened.audioIndex,
                        subtitleIndex = opened.subtitleIndex,
                        imageHeaders = opened.headers,
                        file = opened.file,
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

    /** Segments and the neighbouring episodes; playback does without them when they fail. */
    private fun loadExtras(item: ItemDetails) {
        val id = item.item.id
        viewModelScope.launch {
            val segments = playback.segments(id)
            if (id == itemId) _state.update { it.copy(segments = segments) }
        }
        val queueSource = queueOf
        if (queueSource != null) {
            viewModelScope.launch {
                try {
                    val list = queue ?: details.videoQueue(queueSource).also { queue = it }
                    val neighbours = details.neighboursIn(list, id)
                    if (id == itemId) _state.update { it.copy(previous = neighbours.previous, next = neighbours.next) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Loading the video queue failed", e)
                }
            }
            return
        }
        val seriesId = item.seriesId ?: return
        if (item.item.kind != ItemKind.Episode) return
        viewModelScope.launch {
            try {
                val neighbours = details.neighbours(seriesId, id)
                if (id == itemId) _state.update { it.copy(previous = neighbours.previous, next = neighbours.next) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Loading neighbouring episodes failed", e)
            }
        }
    }

    /** The skip button: jumps past the segment; skipping credits that run to the end starts the next episode. */
    fun skipSegment() {
        val segment = _progress.value.skip ?: return
        val next = _state.value.next
        val duration = _progress.value.durationMs
        if (next != null && duration > 0 && segment.endMs >= duration - END_TOLERANCE_MS) {
            switchTo(next, watched = true)
        } else {
            seekTo(segment.endMs)
        }
        _progress.update { it.copy(skip = null) }
    }

    /** Moving on to the next episode counts the current one as watched. */
    fun playNext() = _state.value.next?.let { switchTo(it, watched = true) }

    fun playPrevious() = _state.value.previous?.let(::switchTo)

    /** "Watch credits". */
    fun dismissUpNext() {
        _state.update { it.copy(upNextDismissed = true) }
        _progress.update { it.copy(upNext = null) }
    }

    private fun onEnded() {
        val state = _state.value
        val next = state.next
        if (next != null && !state.upNextDismissed && policy.upNextAtMs(state.segments, _progress.value.durationMs) != null) {
            switchTo(next)
        } else {
            finish()
        }
    }

    /** Stops this title and plays [item] from its resume point, with the tracks chosen for it (or the server's). */
    private fun switchTo(item: MediaItem, watched: Boolean = false) {
        source?.let { playback.reportStopped(it, currentPositionMs(), watched = watched) }
        source = null
        itemId = item.id
        fromStart = false
        segments = null
        autoSkipped.clear()
        _state.update {
            it.copy(
                details = null,
                segments = emptyList(),
                previous = null,
                next = null,
                upNextDismissed = false,
                chaptersOpen = false,
                trackPanel = null,
                infoOpen = false,
            )
        }
        _progress.value = PlayerProgress()
        val selection = trackSelections.get(item.id)
        open(startMs = item.resumePositionMs, audio = selection?.audio, subtitle = selection?.let { it.subtitle ?: NO_SUBTITLE }, fromLastTitle = selection == null)
    }

    fun openTracks(kind: TrackKind) = _state.update { it.copy(trackPanel = kind, chaptersOpen = false) }

    fun closeTracks() = _state.update { it.copy(trackPanel = null) }

    fun openChapters() = _state.update { it.copy(chaptersOpen = true, trackPanel = null) }

    fun closeChapters() = _state.update { it.copy(chaptersOpen = false) }

    fun openInfo() = _state.update { it.copy(infoOpen = true, chaptersOpen = false, trackPanel = null) }

    fun closeInfo() = _state.update { it.copy(infoOpen = false) }

    /** The server's transcoder for what plays now; null for direct play or when the server does not say. */
    suspend fun transcodeStatus(): TranscodeStatus? =
        source?.takeIf { it.method != PlaybackMethod.DirectPlay }?.let { playback.transcodeStatus(it) }

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

    private fun remember(audio: Int?, subtitle: Int?) {
        trackSelections.set(itemId, TrackSelection(audio, subtitle))
        recordTracks(audio, subtitle)
    }

    /** What plays now, for the next title's "use the tracks of the last title". */
    private fun recordTracks(audio: Int?, subtitle: Int?) {
        val current = source ?: return
        val last = LastTracks(
            audio = current.audioTracks.firstOrNull { it.index == audio }?.toKey(),
            subtitle = current.subtitles.firstOrNull { it.track.index == subtitle }?.track?.toKey(),
            subtitlesOff = subtitle == null,
        )
        viewModelScope.launch { settings.update { it.copy(lastTracks = last) } }
    }

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

    /** Once: Back can arrive twice, as a key and as the system's back callback; each would close a screen. */
    private fun finish() {
        if (closing) return
        closing = true
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
                val position = player.currentPosition
                val duration = player.duration.coerceAtLeast(0)
                val segment = policy.active(_state.value.segments, position)
                if (segment != null && policy.action(segment.kind) == SegmentAction.Skip && player.isPlaying) {
                    autoSkip(segment)
                }
                val upNext = upNext(position, duration)
                _progress.value = PlayerProgress(
                    positionMs = player.currentPosition,
                    durationMs = duration,
                    bufferedMs = player.bufferedPosition,
                    // The card takes over the credits button.
                    skip = segment?.takeIf {
                        policy.action(it.kind) == SegmentAction.Ask && !(it.kind == SegmentKind.Outro && upNext != null)
                    },
                    upNext = upNext,
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

    private fun upNext(positionMs: Long, durationMs: Long): UpNextCountdown? {
        val state = _state.value
        if (state.next == null || state.upNextDismissed || state.loading) return null
        val at = policy.upNextAtMs(state.segments, durationMs) ?: return null
        if (positionMs < at) return null
        val remaining = (durationMs - positionMs).coerceAtLeast(0)
        return UpNextCountdown(remaining, remaining.toFloat() / (durationMs - at).coerceAtLeast(1))
    }

    /** Once per segment; seeking back into it later plays it. */
    private fun autoSkip(segment: MediaSegment) {
        if (!autoSkipped.add(segment)) return
        seekTo(segment.endMs)
        viewModelScope.launch { _events.send(UiEvent.Toast(segment.kind.skippedMessage(), emptyList())) }
    }

    private fun currentPositionMs(): Long = _state.value.player?.currentPosition ?: _progress.value.positionMs

    private fun position() = PlaybackPosition(currentPositionMs(), paused = _state.value.player?.isPlaying != true)

    @OptIn(UnstableApi::class)
    private fun addDecoderListener(player: ExoPlayer) = player.addAnalyticsListener(decoders)

    private fun releasePlayer() {
        ticker?.cancel()
        _state.value.player?.let {
            it.removeListener(listener)
            it.release()
        }
        _state.update { it.copy(player = null, playback = null, videoDecoder = null, audioDecoder = null, audioOutput = null) }
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
        /** Credits ending this close to the end count as running to the end. */
        const val END_TOLERANCE_MS = 2_000L
        /** The server's "no subtitles". */
        const val NO_SUBTITLE = -1
        /** How Media3 reports a segment it cannot parse; the negative start time arrives as an unexpected loader failure. */
        val FMP4_ERRORS = setOf(
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        )
    }
}

internal fun SegmentKind.skipLabel(): Int = when (this) {
    SegmentKind.Intro -> R.string.skip_intro
    SegmentKind.Recap -> R.string.skip_recap
    SegmentKind.Preview -> R.string.skip_preview
    SegmentKind.Commercial -> R.string.skip_commercial
    SegmentKind.Outro -> R.string.skip_outro
}

private fun SegmentKind.skippedMessage(): Int = when (this) {
    SegmentKind.Intro -> R.string.skipped_intro
    SegmentKind.Recap -> R.string.skipped_recap
    SegmentKind.Preview -> R.string.skipped_preview
    SegmentKind.Commercial -> R.string.skipped_commercial
    SegmentKind.Outro -> R.string.skipped_outro
}
