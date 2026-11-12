package io.github.glacier_jellyfin.androidtv.player

import android.content.Context
import android.os.SystemClock
import io.github.glacier_jellyfin.androidtv.core.log.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
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
import io.github.glacier_jellyfin.androidtv.core.data.media.forVersion
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
import io.github.glacier_jellyfin.androidtv.core.data.settings.showKey
import io.github.glacier_jellyfin.androidtv.core.data.settings.toKey
import io.github.glacier_jellyfin.androidtv.core.player.SideloadedSubtitle
import io.github.glacier_jellyfin.androidtv.core.player.TrackControl
import io.github.glacier_jellyfin.androidtv.detail.TrackKind
import io.github.glacier_jellyfin.androidtv.diagnostics.Diagnostics
import io.github.glacier_jellyfin.androidtv.diagnostics.LogServer
import io.github.glacier_jellyfin.androidtv.diagnostics.LogShare
import io.github.glacier_jellyfin.androidtv.ui.UiEvent
import io.github.glacier_jellyfin.androidtv.core.data.media.DetailRepository
import io.github.glacier_jellyfin.androidtv.core.data.media.ItemDetails
import io.github.glacier_jellyfin.androidtv.core.data.playback.PlaybackMethod
import io.github.glacier_jellyfin.androidtv.core.data.playback.PlaybackPosition
import io.github.glacier_jellyfin.androidtv.core.data.playback.PlaybackRepository
import io.github.glacier_jellyfin.androidtv.core.data.playback.PlaybackSource
import io.github.glacier_jellyfin.androidtv.core.data.playback.SourceFile
import io.github.glacier_jellyfin.androidtv.core.data.playback.StreamBudget
import io.github.glacier_jellyfin.androidtv.core.data.settings.MaxBitrate
import io.github.glacier_jellyfin.androidtv.core.log.DebugOptions
import io.github.glacier_jellyfin.androidtv.core.data.playback.TranscodeStatus
import io.github.glacier_jellyfin.androidtv.core.jellyfin.playback.HlsSegments
import io.github.glacier_jellyfin.androidtv.core.player.GlacierPlayback
import io.github.glacier_jellyfin.androidtv.core.player.GlacierPlayer
import io.github.glacier_jellyfin.androidtv.core.player.StreamRequest
import io.github.glacier_jellyfin.androidtv.music.MusicController
import io.github.glacier_jellyfin.androidtv.navigation.PlayerRoute
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
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
    /** The device could not decode or output the stream: no point in checking the server. */
    val failedOnDevice: Boolean = false,
    /** What failed, for the issue: Media3's error name and code, or the exception's class. */
    val failureCode: String? = null,
    /** The log offered on the local network while the failure is shown; null without a network. */
    val logShare: LogShare? = null,
    /** The log could not be offered: the device has no network. */
    val logOffline: Boolean = false,
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
    /** The skip button fades away after this long with the OSD hidden, 0 for never (Settings › Playback). */
    val skipHideMs: Long = 10_000,
    /** The "Up next" card shrinks to its countdown after this long with the OSD hidden, 0 for never (Settings › Playback). */
    val upNextCollapseMs: Long = 0,
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
    private val versions: VersionChooser,
    private val diagnostics: Diagnostics,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<PlayerRoute>()
    /** Changes when playback moves on to another episode. */
    private var itemId = UUID.fromString(route.itemId)
    private var fromStart = route.fromStart

    /** Where the first open starts, given by the caller; later opens use the resume point. */
    private var startOverride = route.startMs
    /** The file that plays of a title with several; null until known, and for a single file. */
    private var versionId: String? = null
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
    /** Output failures already recovered from for this title. */
    private var outputRecoveries = 0
    private val stalls = StallTracker()
    /** Times this title already moved to a smaller stream because playback ran dry. */
    private var qualitySteps = 0
    /** Times a ladder's segment failed in time and was tried again; counted from the start of the title. */
    private var linkRetries = 0
    /** Retries of an open after a step down. */
    private var openRetries = 0
    private var lastSeekMs = 0L
    /** Waits out a stall that does not end. */
    private var stallWatch: Job? = null
    /** How long the buffer has been nearly empty while playing; draining is a stall that has not happened yet. */
    private var lowBufferMs = 0L
    /** When the stream first played, so the filling of the buffer at the start is not mistaken for draining. */
    private var playingSinceMs = 0L
    /** Keeps a ladder's tier within what the link carries; null for a stream of one tier. */
    private var tiers: TierController? = null
    /** Picture height of each tier by its bitrate, as the player lists them. */
    private var tierHeights: Map<Int, Int> = emptyMap()
    /** Height of the tier asked for last; its transcode is the one running now or about to. */
    private var pinnedHeight: Int? = null
    /** Heights of tiers left behind whose server job still runs, until the current tier has loaded its first segment. */
    private val retired = mutableSetOf<Int>()
    private var slowLoads = SlowLoads()
    /** Waits for the link to recover while a reduced stream plays. */
    private var recovery: Job? = null
    /** Times this title went back to the file's quality. */
    private var returns = 0
    /** Title position and length from the player's: the one place that knows where the playing stream starts. */
    private val time = TitleTime()
    private val trend = BufferTrend()
    private val throughput = ThroughputWatch()

    /** A stream opened to take over from the playing one; see [beginHandover]. */
    private class Handover(val trigger: HandoverTrigger?, val atMs: Long, val source: PlaybackSource, val lowering: Boolean)

    /** Opening the stream for a hand over. */
    private var handoverJob: Job? = null
    /** The stream queued in the player, until the player has moved on to it. */
    private var queued: Handover? = null
    /** The stream handed over to is not playing yet: no further step to another stream before it does. */
    private var awaitingStart = false

    private val handoverActive: Boolean get() = handoverJob?.isActive == true || queued != null || awaitingStart

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

    /** Media segments of a ladder: tells when a tier has taken over, and when loading falls behind playing. */
    @OptIn(UnstableApi::class)
    private val loads = object : AnalyticsListener {
        @OptIn(UnstableApi::class)
        override fun onLoadCompleted(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData) {
            val controller = tiers ?: return
            if (mediaLoadData.dataType != C.DATA_TYPE_MEDIA) return
            val height = mediaLoadData.trackFormat?.height?.takeIf { it > 0 } ?: return
            // The first segment of the new tier is here: the old tier's transcode has nothing left to give.
            if (height == pinnedHeight && retired.isNotEmpty()) endRetiredJobs(height)
            val playMs = mediaLoadData.mediaEndTimeMs - mediaLoadData.mediaStartTimeMs
            if (mediaLoadData.mediaStartTimeMs != C.TIME_UNSET && mediaLoadData.mediaEndTimeMs != C.TIME_UNSET && playMs > 0 &&
                slowLoads.record(loadEventInfo.loadDurationMs, playMs, SystemClock.elapsedRealtime())
            ) {
                Log.w(TAG, "Two segments in a row loaded slower than they play (${loadEventInfo.loadDurationMs} ms for ${playMs} ms), measuring the link")
                viewModelScope.launch { checkLink(controller) }
            }
        }
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> onBuffering()
                Player.STATE_READY -> {
                    stallWatch?.cancel()
                    if (awaitingStart) {
                        awaitingStart = false
                        Log.i(TAG, "The stream handed over to plays")
                    }
                    _state.update { it.copy(loading = false) }
                    if (!started) {
                        started = true
                        playingSinceMs = SystemClock.elapsedRealtime()
                        openRetries = 0
                        if (DebugOptions.segmentLog && DebugOptions.probe) viewModelScope.launch {
                            delay(PROBE_DELAY_MS)
                            probeSegments()
                        }
                        if (DebugOptions.pauseOnStart) _state.value.player?.playWhenReady = false
                        lowBufferMs = 0
                        source?.let { playback.reportStart(it, position()) }
                    }
                }
                Player.STATE_ENDED -> onEnded()
                else -> Unit
            }
        }

        // The player moved on to the stream queued by a hand over (by playing to its end or by a seek into it).
        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
            val handover = queued ?: return
            if (mediaItem?.mediaId != _state.value.playback?.handOverId) return
            completeHandover(handover)
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
            if (reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) {
                lastSeekMs = SystemClock.elapsedRealtime()
            }
            if (reason == Player.DISCONTINUITY_REASON_SEEK && started) source?.let { playback.reportProgress(it, position()) }
        }

        override fun onTracksChanged(tracks: Tracks) {
            if (tiers == null) startTiers(tracks)
            if (tracksPending) tracksPending = !applyTracks()
        }

        override fun onPlayerError(error: PlaybackException) {
            // A stream queued for a hand over that fails does not end the playing one: it goes on to its end, and an error of
            // its own shows again. Anything else of a hand over is given up, and the error is handled as before.
            if (queued != null && !awaitingStart) {
                Log.w(TAG, "The hand over failed, the playing stream goes on", error)
                dropHandover(restore = true)
                _state.value.player?.prepare()
                return
            }
            if (handoverActive) {
                Log.w(TAG, "Playback failed during a hand over, giving it up", error)
                dropHandover(restore = false)
            }
            val current = source
            // Some fMP4 segments from the server start at a negative time, which Media3 cannot read.
            if (current != null && current.fmp4Segments && segments == null && error.errorCode in FMP4_ERRORS) {
                Log.w(TAG, "Reading fMP4 segments failed, asking for MPEG-TS", error)
                segments = HlsSegments.Ts
                reopen(audio = _state.value.audioIndex, subtitle = _state.value.subtitleIndex)
                return
            }
            // While the TV changes picture mode it drops the audio link for a moment, which kills the AudioTrack,
            // and Fire TV's decoder can refuse the new video surface. A fresh player plays on once the switch is over.
            // Preparing the old one again can leave it stuck.
            val player = _state.value.player
            if (player != null && error.isOutputFailure() && outputRecoveries < MAX_OUTPUT_RECOVERIES) {
                outputRecoveries++
                Log.w(TAG, "Output failed, opening again ($outputRecoveries)", error)
                _state.update { it.copy(loading = true) }
                viewModelScope.launch {
                    delay(OUTPUT_RECOVERY_DELAY_MS)
                    if (_state.value.player === player) reopen(audio = _state.value.audioIndex, subtitle = _state.value.subtitleIndex)
                }
                return
            }
            // The tiers of a ladder: a segment that did not come in time is tried again, and the link is measured, before it
            // is an error. A move to another tier waits for that measurement.
            val controller = tiers
            if (controller != null && player != null && error.errorCode in LINK_ERRORS && linkRetries < MAX_LINK_RETRIES) {
                linkRetries++
                Log.w(TAG, "A segment did not come in time, trying again ($linkRetries)", error)
                viewModelScope.launch { checkLink(controller) }
                player.prepare()
                return
            }
            // A link too slow for the stream ends in a timeout or a closed connection: a smaller stream first.
            if (error.errorCode in LINK_ERRORS && lowerQuality()) {
                Log.w(TAG, "The link failed the stream, trying a smaller one", error)
                return
            }
            Log.w(TAG, "Playback failed", error)
            source?.let { playback.reportStopped(it, currentPositionMs(), failed = true) }
            fail(error.failureCode(), onDevice = error.isDeviceFailure())
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
                        skipHideMs = profile.playback.skipButtonHide.ms,
                        upNextCollapseMs = profile.playback.upNextCollapse.ms,
                    )
                }
            }
        }
        load()
    }

    fun load() {
        outputRecoveries = 0
        qualitySteps = 0
        linkRetries = 0
        openRetries = 0
        returns = 0
        stalls.clear()
        val selection = trackSelections.get(itemId)
        versionId = selection?.versionId
        // A choice made on the detail page wins; "off" there is sent as -1 so the server does not pick one.
        open(startMs = startOverride, audio = selection?.audio, subtitle = selection?.let { it.subtitle ?: NO_SUBTITLE }, fromLastTitle = selection == null)
        startOverride = null
    }

    /**
     * [startMs] null: the saved resume point (or 0 for "from start").
     * [fromLastTitle]: no tracks were chosen for this title, so the last title's may be taken over.
     */
    private fun open(startMs: Long?, audio: Int?, subtitle: Int?, fromLastTitle: Boolean = false, retry: Boolean = false) {
        opening?.cancel()
        opening = viewModelScope.launch {
            releasePlayer()
            closeLogShare()
            _state.update { it.copy(loading = true, failed = false, failedOnDevice = false, failureCode = null, trackPanel = null) }
            try {
                val item = _state.value.details ?: details.details(itemId).let { loaded ->
                    // The file the detail page showed, else the one picked for the title or show.
                    if (versionId == null) versionId = versions.choose(loaded)?.id
                    loaded.forVersion(versionId)
                }.also { d ->
                    _state.update { it.copy(details = d) }
                    loadExtras(d)
                }
                val start = startMs ?: if (fromStart) 0 else item.item.resumePositionMs
                val remembered = if (fromLastTitle) {
                    val preferences = serverPreferences.preferences.value ?: serverPreferences.refresh()
                    preferences?.let { rememberedTracks(settings.current().showTracks[item.showKey()], it, item.tracks) }
                } else {
                    null
                }
                val opened = playback.open(itemId, start, remembered?.audio ?: audio, remembered?.subtitle ?: subtitle, segments, versionId, reuse = retry)
                source = opened
                recordTracks(opened.audioIndex, opened.subtitleIndex)
                val request = streamRequest(opened, start)
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
                watchRecovery()
                if (!opened.live && opened.ladder == null) {
                    // A transcode takes its time to start: a 4K source needs well over 20 s before the first segment.
                    watchStartup(player, if (opened.method == PlaybackMethod.DirectPlay) STARTUP_MS else TRANSCODE_STARTUP_MS)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Right after a step down the link is still full of the old stream: the server's answer may be late.
                if (e is IOException && qualitySteps > 0 && openRetries < MAX_OPEN_RETRIES) {
                    openRetries++
                    Log.w(TAG, "Opening the smaller stream failed, trying again ($openRetries)", e)
                    delay(OPEN_RETRY_DELAY_MS)
                    open(startMs, audio, subtitle, fromLastTitle, retry = true)
                    return@launch
                }
                Log.w(TAG, "Opening playback failed", e)
                fail(e.javaClass.simpleName, onDevice = false)
            }
        }
    }

    /** The player's request for a stream of the title that [opened] describes, to play from [start]. */
    private suspend fun streamRequest(opened: PlaybackSource, start: Long) = StreamRequest(
        url = opened.url,
        isHls = opened.isHls,
        headers = opened.headers,
        startPositionMs = start,
        playlist = opened.ladder,
        patient = opened.isHls && !opened.live,
        title = true,
        // The tier the link carries; Media3 takes the one this estimate allows.
        startBitrate = opened.streamBitrate?.let { it * 110L / 100 }?.toInt(),
        subtitles = opened.subtitles
            .filter { it.delivery == SubtitleDelivery.External && it.url != null }
            .map { SideloadedSubtitle(it.track.index, it.url!!, it.track.codec, it.track.language) },
        setup = playback.device.playbackSetup(settings.current().playback, playback.iec61937),
    )

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
        outputRecoveries = 0
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
        versionId = selection?.versionId
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

    /**
     * Playback ran dry while playing (not at the start, not after a jump). Twice within a minute and a half,
     * or once for [LONG_STALL_MS], the stream is more than the link carries: the next one asks for less.
     */
    private fun onBuffering() {
        val player = _state.value.player ?: return
        if (!started || !player.playWhenReady) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastSeekMs < SEEK_BUFFERING_MS) return
        // A stream is on its way in: waiting for it at the point of the hand over is no stall of the link. It gets the time a
        // new stream gets at the start, then the title is opened again.
        queued?.takeIf { !awaitingStart }?.let { handover ->
            if (currentPositionMs() >= handover.atMs - HANDOVER_EDGE_MS && stallWatch?.isActive != true) {
                val timeout = if (handover.source.method == PlaybackMethod.DirectPlay) STARTUP_MS else TRANSCODE_STARTUP_MS
                Log.i(TAG, "Waiting for the stream handed over to, up to ${timeout / 1000} s")
                stallWatch = viewModelScope.launch {
                    delay(timeout)
                    if (queued === handover && _state.value.player === player) {
                        Log.w(TAG, "The stream handed over to did not start, opening the title again")
                        dropHandover(restore = false)
                        reopen(audio = _state.value.audioIndex, subtitle = _state.value.subtitleIndex)
                    }
                }
            }
            return
        }
        if (handoverActive) return
        // Tiers: a stall may be the link or the server. A move costs a new transcode (about 16 s), so only a measurement that
        // says the link does not carry the tier moves it; a new stream is for a title that has none.
        tiers?.let { controller ->
            Log.w(TAG, "Stalled at tier ${controller.bitrate / 1000} kbit/s, measuring the link")
            viewModelScope.launch { checkLink(controller) }
            return
        }
        noteStall(now, wait = true)
    }

    /** One stall, or a buffer that ran down to nothing; [wait]: one that does not end is acted on after [LONG_STALL_MS]. */
    private fun noteStall(now: Long, wait: Boolean) {
        if (stalls.record(now)) {
            lowerQuality()
            return
        }
        if (!wait) return
        stallWatch?.cancel()
        stallWatch = viewModelScope.launch {
            delay(LONG_STALL_MS)
            if (_state.value.player?.playbackState == Player.STATE_BUFFERING) lowerQuality()
        }
    }

    /**
     * The buffer tells before a stall does: a stream that takes more than the link carries drains it. Seconds
     * of nearly nothing ahead, after the buffer had its time to fill, count as a stall; a buffer that
     * holds up leaves the quality alone.
     */
    private fun watchBuffer(player: ExoPlayer) {
        val now = SystemClock.elapsedRealtime()
        val ahead = player.bufferedPosition - player.currentPosition
        // A ladder: the server hands out segments about as fast as they play, so the buffer is small by nature.
        if (tiers != null) return
        // Near the point of a hand over the playing stream's buffer ends there by design.
        if (handoverActive) {
            trend.clear()
            throughput.clear()
            lowBufferMs = 0
            return
        }
        // Faster than the trend: the bytes loaded per second against what the stream asks. A file played as it is has no
        // ladder to follow the link, and Media3's own estimate does not move during its one long transfer.
        val playback = _state.value.playback
        val streamBps = (source?.streamBitrate ?: source?.file?.bitrate)?.toLong() ?: 0L
        if (playback != null && started && player.isPlaying && now - playingSinceMs > THROUGHPUT_SETTLE_MS && now - lastSeekMs > THROUGHPUT_SETTLE_MS) {
            val rate = playback.downloadRate(ThroughputWatch.WINDOW_MS)
            val full = ahead >= playback.maxBufferMs - ThroughputWatch.FULL_MARGIN_MS
            if (throughput.check(now, player.isLoading, rate, streamBps, full)) {
                Log.w(TAG, "The link loads ${rate / 1000} kbit/s, the stream asks ${streamBps / 1000} kbit/s, ${ahead / 1000} s buffered")
                lowerQuality(HandoverTrigger.Throughput)
                return
            }
        } else {
            throughput.clear()
        }
        val settled = started && now - playingSinceMs > BUFFER_SETTLE_MS && now - lastSeekMs > BUFFER_SETTLE_MS
        // Loading, and yet the buffer shrinks: the link is slower than the stream. Seen long before a stall, so the way to
        // the smaller stream can run from the buffer.
        if (settled && player.isPlaying) {
            if (trend.record(now, ahead, player.isLoading)) {
                Log.w(TAG, "The buffer fell while loading, ${ahead / 1000} s left")
                lowerQuality(HandoverTrigger.Draining)
                return
            }
        } else {
            trend.clear()
        }
        when {
            !settled || !player.isPlaying || ahead >= BUFFER_RECOVERED_MS -> lowBufferMs = 0
            ahead < BUFFER_LOW_MS -> {
                lowBufferMs += TICK_MS
                if (lowBufferMs >= BUFFER_DRAIN_MS) {
                    lowBufferMs = 0
                    Log.w(TAG, "Buffer is down to ${ahead / 1000} s")
                    noteStall(now, wait = false)
                }
            }
        }
    }

    /**
     * A reduced stream goes back to the file's quality when the link has recovered: measured again every
     * few minutes while the buffer is full, since the measurement then costs no picture.
     */
    private fun watchRecovery() {
        recovery?.cancel()
        val current = source ?: return
        // A ladder: every test moves the window of tiers, no new stream.
        if (current.ladder != null && !current.live) {
            recovery = viewModelScope.launch {
                while (isActive) {
                    delay(RECOVERY_CHECK_MS)
                    val player = _state.value.player ?: return@launch
                    if (settings.settings.value.playback.maxBitrate != MaxBitrate.Auto) return@launch
                    if (!player.isPlaying) continue
                    tiers?.let { checkLink(it, current) }
                }
            }
            return
        }
        val wanted = current.file?.bitrate ?: return
        val streamBitrate = current.streamBitrate ?: return
        val reduced = streamBitrate < wanted
        if (current.live || current.ladder != null) return
        // The file as it is: only a step down is possible. A reduced stream: only the way back.
        if (if (reduced) returns >= MAX_RETURNS else qualitySteps >= MAX_QUALITY_STEPS || streamBitrate <= StreamBudget.FLOOR) return
        recovery = viewModelScope.launch {
            while (isActive) {
                delay(RECOVERY_CHECK_MS)
                val player = _state.value.player ?: return@launch
                if (settings.settings.value.playback.maxBitrate != MaxBitrate.Auto) return@launch
                val ahead = player.bufferedPosition - player.currentPosition
                if (!player.isPlaying || handoverActive || ahead < if (reduced) HandoverPlanner.RETURN_MIN_BUFFER_MS else RECOVERY_BUFFER_MS) continue
                val budget = playback.probeBudget() ?: continue
                Log.i(TAG, "The link carries ${budget / 1_000_000} Mbit/s, the stream asks ${streamBitrate / 1_000_000}")
                if (!reduced) {
                    // The measurement before the start is old by now: a link that carries less than the file is no news at a stall.
                    if (StreamBudget.exceeds(streamBitrate, budget) && lowerQuality(HandoverTrigger.Link) && qualitySteps >= MAX_QUALITY_STEPS) return@launch
                } else if (StreamBudget.returnsTo(wanted, budget)) {
                    val plan = HandoverPlanner.back(ahead, currentPositionMs(), titleDuration())
                    if (plan is HandoverPlan.At) {
                        returns++
                        Log.i(TAG, "The link carries ${budget / 1_000_000} Mbit/s again, back to the file's ${wanted / 1_000_000}")
                        beginHandover(plan, trigger = null, lowering = false)
                        return@launch
                    }
                    Log.i(TAG, "Back to the file would have to wait: ${ahead / 1000} s buffered")
                }
            }
        }
    }

    /** The length of the title, whatever stream plays. */
    private fun titleDuration(): Long = time.duration(_state.value.player?.duration?.coerceAtLeast(0) ?: 0)

    /**
     * Opens the stream that takes over from the playing one at [HandoverPlan.At.atMs] and queues it in the player, which
     * moves on there without a pause: the playing stream goes on from its buffer while the server starts the new one.
     * [lowering]: a smaller stream, else the file. Nothing is queued when the new stream is not ready in time; the stream
     * that plays then goes on, and after a stall ([trigger]) the title is opened again as it was before hand overs.
     */
    private fun beginHandover(plan: HandoverPlan.At, trigger: HandoverTrigger?, lowering: Boolean) {
        val player = _state.value.player ?: return
        val handOver = _state.value.playback ?: return
        val before = source ?: return
        val audio = _state.value.audioIndex
        val subtitle = _state.value.subtitleIndex ?: NO_SUBTITLE
        val seekMark = lastSeekMs
        Log.i(
            TAG,
            "Hand over (${trigger ?: "link recovered"}): from ${currentPositionMs() / 1000} s to ${plan.atMs / 1000} s, lead ${plan.leadMs / 1000} s, " +
                "${(player.bufferedPosition - player.currentPosition) / 1000} s buffered",
        )
        handoverJob?.cancel()
        handoverJob = viewModelScope.launch {
            var opened: PlaybackSource? = null
            try {
                opened = playback.open(itemId, plan.atMs, audio, subtitle, segments, versionId)
                val now = currentPositionMs()
                val problem = when {
                    _state.value.player !== player || lastSeekMs != seekMark -> "playback moved on meanwhile"
                    lowering && (opened.streamBitrate ?: 0) >= (before.streamBitrate ?: Int.MAX_VALUE) -> "the server did not give a smaller stream"
                    !HandoverPlanner.stillWorks(lowering, plan.atMs, now) -> "the stream took too long to open (${(plan.atMs - now) / 1000} s of the lead left)"
                    else -> null
                }
                if (problem == null && handOver.handOver(plan.atMs, streamRequest(opened, plan.atMs))) {
                    queued = Handover(trigger, plan.atMs, opened, lowering)
                    opened = null
                    return@launch
                }
                Log.w(TAG, "Hand over given up: ${problem ?: "the player could not take it"}")
            } catch (e: CancellationException) {
                opened?.let(playback::discard)
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Opening the stream for a hand over failed", e)
            }
            opened?.let(playback::discard)
            // A stream that already ran dry has no time to lose: the way it was before hand overs.
            if (trigger == HandoverTrigger.Stalls && _state.value.player === player) {
                reopen(audio = _state.value.audioIndex, subtitle = _state.value.subtitleIndex)
            }
        }
    }

    /** The player is on the stream handed over to: its positions count from the hand over, and the server hears of it. */
    private fun completeHandover(handover: Handover) {
        val handOver = _state.value.playback ?: return
        val player = handOver.player
        val old = source
        queued = null
        time.startAt(handover.atMs)
        handOver.finishHandOver()
        val opened = handover.source
        source = opened
        // Everything that belonged to the old stream: tiers and their server jobs, the stalls, the buffer's history.
        tiers = null
        tierHeights = emptyMap()
        pinnedHeight = null
        retired.clear()
        slowLoads = SlowLoads()
        stalls.clear()
        trend.clear()
        lowBufferMs = 0
        playingSinceMs = SystemClock.elapsedRealtime()
        _state.update {
            it.copy(
                method = opened.method,
                audioTracks = opened.audioTracks,
                subtitles = opened.subtitles,
                audioIndex = opened.audioIndex,
                subtitleIndex = opened.subtitleIndex,
                imageHeaders = opened.headers,
                file = opened.file,
            )
        }
        recordTracks(opened.audioIndex, opened.subtitleIndex)
        Log.i(TAG, "Handed over at ${handover.atMs / 1000} s to ${opened.method}, ${(opened.streamBitrate ?: 0) / 1_000_000} Mbit/s")
        if (old != null) playback.reportHandOver(old, opened, position()) else playback.reportStart(opened, position())
        viewModelScope.launch {
            _events.send(UiEvent.Toast(if (handover.lowering) R.string.player_lower_quality else R.string.player_full_quality))
        }
        tracksPending = true
        awaitingStart = player.playbackState != Player.STATE_READY
        if (awaitingStart) {
            val timeout = if (opened.method == PlaybackMethod.DirectPlay) STARTUP_MS else TRANSCODE_STARTUP_MS
            stallWatch?.cancel()
            stallWatch = viewModelScope.launch {
                delay(timeout)
                if (awaitingStart && _state.value.player === player) {
                    Log.w(TAG, "The stream handed over to shows no picture after ${timeout / 1000} s")
                    awaitingStart = false
                    if (!lowerQuality()) reopen(audio = _state.value.audioIndex, subtitle = _state.value.subtitleIndex)
                }
            }
        }
        // The tracks the player knows now are those of the old stream, until the new one is prepared: onTracksChanged brings them.
        watchRecovery()
    }

    /** Gives up a hand over: what is being opened is dropped, and what is queued goes out of the player. */
    private fun dropHandover(restore: Boolean) {
        handoverJob?.cancel()
        handoverJob = null
        queued?.let {
            playback.discard(it.source)
            if (restore) _state.value.playback?.cancelHandOver()
        }
        queued = null
        awaitingStart = false
    }

    /**
     * Debug: asks the server for the next segments of the playing tier all at once and logs when each answers. If they answer
     * together, the wait is a fixed delay per request and loading ahead in parallel would fill the buffer; if one after the
     * other, the server hands them out at its own pace.
     */
    private suspend fun probeSegments() {
        val current = source ?: return
        val player = _state.value.player ?: return
        val variant = current.ladder?.lines()?.firstOrNull { it.isNotBlank() && !it.startsWith("#") } ?: return
        // The player is read on the main thread, the requests run on another.
        val ahead = (time.position(player.bufferedPosition) / PROBE_SEGMENT_MS).toInt()
        // Old segments exist already: how fast they are handed out tells the server's own delay per request.
        val next = if (DebugOptions.probeOld) maxOf(0, ahead - PROBE_OLD_BEHIND) else ahead
        val buffered = player.bufferedPosition - player.currentPosition
        try {
            withContext(Dispatchers.IO) {
            fun open(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
                current.headers.forEach { (name, value) -> setRequestProperty(name, value) }
                connectTimeout = 15_000
                readTimeout = 60_000
            }
            val segments = open(variant).run { inputStream.bufferedReader().use { it.readLines() } }
                .filter { it.isNotBlank() && !it.startsWith("#") }
            val base = URI(variant)
            val t0 = SystemClock.elapsedRealtime()
            Log.i("Probe", "asking for ${if (DebugOptions.probeOld) "old " else ""}segments $next to ${next + PROBE_COUNT - 1} at once, buffered $buffered ms")
            coroutineScope {
                (0 until PROBE_COUNT).map { k ->
                    async {
                        val connection = open(base.resolve(segments[next + k]).toString())
                        val code = connection.responseCode
                        val answered = SystemClock.elapsedRealtime() - t0
                        val bytes = connection.inputStream.use { it.readBytes().size }
                        Log.i("Probe", "segment ${next + k}: answered after $answered ms, ${bytes / 1024} KiB, done after ${SystemClock.elapsedRealtime() - t0} ms (code $code)")
                    }
                }.awaitAll()
            }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A debug aid: whatever goes wrong with it must not end the playback.
            Log.w("Probe", "failed: ${e.javaClass.simpleName} ${e.message}")
        }
    }

    /**
     * Measures the link once. A title that plays as a ladder only because the link was too slow for the file goes back to the
     * file when the link carries it again (the tier is not raised then: the hand over replaces the ladder). Otherwise the tier
     * moves down when the link does not carry it, or up when the buffer is full and the link has room: with up to two minutes
     * loaded, the run-up of the new transcode does not show.
     */
    private suspend fun checkLink(controller: TierController, current: PlaybackSource? = null) {
        val budget = playback.probeBudget() ?: return
        Log.i(TAG, "The link carries ${budget / 1_000_000} Mbit/s")
        // The measurement took a while: the stream may have been replaced meanwhile.
        if (tiers !== controller) return
        val now = SystemClock.elapsedRealtime()
        val player = _state.value.player ?: return
        val ahead = player.bufferedPosition - player.currentPosition
        // Only the periodic check goes back to the file ([current] given); a check after a stall or a slow load never does.
        val wanted = current?.file?.bitrate
        if (current != null && wanted != null && HandoverPlanner.backToFile(
                current.fileFitsDevice, wanted, budget, ahead, handoverActive, returns, MAX_RETURNS,
            )
        ) {
            val plan = HandoverPlanner.back(ahead, currentPositionMs(), titleDuration())
            if (plan is HandoverPlan.At) {
                returns++
                Log.i(TAG, "The link carries ${budget / 1_000_000} Mbit/s again, back to the file's ${wanted / 1_000_000}")
                beginHandover(plan, trigger = null, lowering = false)
                return
            }
            Log.i(TAG, "Back to the file would have to wait: ${ahead / 1000} s buffered")
        }
        controller.limitTo(budget, now)?.let {
            pinTier(it)
            return
        }
        controller.raiseTo(budget, player.totalBufferedDuration, now)?.let { pinTier(it) }
    }

    /** Ends the server's jobs of the tiers left behind; [current] is the tier that plays and is never touched. */
    private fun endRetiredJobs(current: Int) {
        val sessions = source?.tierSessions.orEmpty()
        val ended = retired.filter { it != current }
        retired.clear()
        for (height in ended) {
            val id = sessions[height] ?: continue
            Log.i(TAG, "${current}p has its first segment, ending the transcode of ${height}p")
            viewModelScope.launch { playback.stopTierJob(id) }
        }
    }

    /** The tiers of a ladder, once Media3 lists them: the stream starts at the one that fits the budget. */
    @OptIn(UnstableApi::class)
    private fun startTiers(tracks: Tracks) {
        val current = source ?: return
        if (current.ladder == null || DebugOptions.fixedTier) return
        val formats = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
            .flatMap { group -> (0 until group.length).map { group.getTrackFormat(it) } }
            .filter { it.bitrate > 0 }
            .distinctBy { it.bitrate }
            .sortedBy { it.bitrate }
        if (formats.size < 2) {
            // None at all: the tracks are still those of the stream just left; the new stream's come with onTracksChanged.
            if (formats.isNotEmpty()) Log.i(TAG, "Video formats listed so far: ${formats.joinToString { "${it.height}p" }}")
            return
        }
        val bitrates = formats.map { it.bitrate }
        val start = bitrates.indexOfLast { it <= (current.streamBitrate ?: bitrates.last()) * 105L / 100 }.coerceAtLeast(0)
        val controller = TierController(bitrates, formats.map { current.tierDemand[it.height] ?: StreamBudget.expectedBitrate(it.height) }, start)
        tiers = controller
        tierHeights = formats.associate { it.bitrate to it.height }
        slowLoads = SlowLoads()
        Log.i(TAG, "Tiers ${formats.joinToString { "${it.height}p (${it.bitrate / 1000} kbit/s)" }}, starting at ${controller.bitrate / 1000}")
        pinTier(controller.bitrate)
    }

    /** Asks the player for the tier of [bitrate]; it switches at the next segment, the buffer already loaded plays on. */
    private fun pinTier(bitrate: Int) {
        Log.i(TAG, "Tier ${bitrate / 1000} kbit/s" + tiers?.reason?.takeIf { it.isNotEmpty() }?.let { ": $it" }.orEmpty())
        val height = tierHeights[bitrate]
        val before = pinnedHeight
        // The tier left behind keeps its job until the new one has a segment; one that is asked for again stays.
        if (before != null && height != null && before != height) retired += before
        if (height != null) retired -= height
        pinnedHeight = height
        _state.value.playback?.steer(bitrate)
    }

    /**
     * A stream that asks less of the link takes over: handed over in the playing player when its buffer carries the new
     * stream's start (see [HandoverPlanner]), else the title is opened again at the current position. Not while a hand over
     * is on its way. False when there is nothing to lower; true when it is handled, also when it waits for a better moment.
     */
    private fun lowerQuality(trigger: HandoverTrigger = HandoverTrigger.Stalls): Boolean {
        if (handoverActive) return true
        stallWatch?.cancel()
        val current = source ?: return false
        val streamBitrate = current.streamBitrate
        // Only "Auto" decides by itself; a chosen cap stays. Live TV has no bitrate to lower.
        // Tiers follow the link by themselves; a new stream is for the one that cannot.
        if (current.ladder != null || current.live || streamBitrate == null || qualitySteps >= MAX_QUALITY_STEPS ||
            streamBitrate <= StreamBudget.FLOOR || settings.settings.value.playback.maxBitrate != MaxBitrate.Auto
        ) {
            return false
        }
        val player = _state.value.player
        val canHand = player != null && started && _state.value.playback?.canHandOver == true
        val plan = if (canHand) {
            HandoverPlanner.lower(trigger, player.bufferedPosition - player.currentPosition, currentPositionMs(), titleDuration())
        } else {
            HandoverPlan.Reopen
        }
        if (plan == HandoverPlan.Wait) {
            Log.i(TAG, "The stream is too much for the link ($trigger), but the title ends soon: leaving it")
            return true
        }
        qualitySteps++
        stalls.clear()
        trend.clear()
        playback.lowerBudget(streamBitrate)
        if (plan is HandoverPlan.At) {
            beginHandover(plan, trigger, lowering = true)
            return true
        }
        Log.i(TAG, "Opening the title again with a smaller stream ($trigger)")
        viewModelScope.launch { _events.send(UiEvent.Toast(R.string.player_lower_quality)) }
        reopen(audio = _state.value.audioIndex, subtitle = _state.value.subtitleIndex)
        return true
    }

    /** A stream that shows no picture within [timeoutMs] is more than the link carries, too. */
    private fun watchStartup(player: Player, timeoutMs: Long) {
        stallWatch?.cancel()
        stallWatch = viewModelScope.launch {
            delay(timeoutMs)
            if (_state.value.player === player && !started) {
                Log.w(TAG, "No picture after ${timeoutMs / 1000} s")
                lowerQuality()
            }
        }
    }

    /** Opens the title again, at [at] (a position of the title) or where it plays now. */
    private fun reopen(audio: Int?, subtitle: Int?, at: Long? = null) {
        val position = at ?: currentPositionMs()
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
        trackSelections.set(itemId, TrackSelection(audio, subtitle, versionId))
        recordTracks(audio, subtitle)
    }

    /** What plays now, for the show's next episode ("use the tracks of the last title"). */
    private fun recordTracks(audio: Int?, subtitle: Int?) {
        val current = source ?: return
        val key = _state.value.details?.showKey() ?: return
        val last = LastTracks(
            audio = current.audioTracks.firstOrNull { it.index == audio }?.toKey(),
            subtitle = current.subtitles.firstOrNull { it.track.index == subtitle }?.track?.toKey(),
            subtitlesOff = subtitle == null,
        )
        viewModelScope.launch { settings.update { it.copy(showTracks = it.showTracks + (key to last)) } }
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
        val target = positionMs.coerceIn(0, titleDuration().takeIf { it > 0 } ?: Long.MAX_VALUE)
        when (val to = time.seek(target, queued?.atMs)) {
            // Before where the playing stream begins (after a hand over): it has nothing of that.
            is SeekTarget.Reopen -> {
                reopen(audio = _state.value.audioIndex, subtitle = _state.value.subtitleIndex, at = to.atMs)
                return
            }
            is SeekTarget.Here -> {
                // A stream still being opened for a hand over was planned from where the title played.
                if (handoverJob?.isActive == true) handoverJob?.cancel()
                player.seekTo(to.positionMs)
            }
            // Into the stream waiting to take over: the playback goes there at its start first, which is how it takes over what that
            // has loaded (see GlacierPlayback.enterNext).
            is SeekTarget.Next -> _state.value.playback?.enterNext(to.positionMs) ?: player.seekTo(1, to.positionMs)
        }
        _progress.update { it.copy(positionMs = target) }
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
                val position = time.position(player.currentPosition)
                val duration = titleDuration()
                val segment = policy.active(_state.value.segments, position)
                if (segment != null && policy.action(segment.kind) == SegmentAction.Skip && player.isPlaying) {
                    autoSkip(segment)
                }
                watchBuffer(player)
                val upNext = upNext(position, duration)
                _progress.value = PlayerProgress(
                    positionMs = position,
                    durationMs = duration,
                    bufferedMs = time.position(player.bufferedPosition),
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

    private fun currentPositionMs(): Long = _state.value.player?.let { time.position(it.currentPosition) } ?: _progress.value.positionMs

    private fun position() = PlaybackPosition(currentPositionMs(), paused = _state.value.player?.isPlaying != true)

    @OptIn(UnstableApi::class)
    private fun addDecoderListener(player: ExoPlayer) {
        player.addAnalyticsListener(decoders)
        player.addAnalyticsListener(loads)
    }

    private fun releasePlayer() {
        ticker?.cancel()
        stallWatch?.cancel()
        recovery?.cancel()
        handoverJob?.cancel()
        handoverJob = null
        queued?.let { playback.discard(it.source) }
        queued = null
        awaitingStart = false
        time.reset()
        trend.clear()
        tiers = null
        pinnedHeight = null
        retired.clear()
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
        closeLogShare()
    }

    private var logServer: LogServer? = null
    private var sharing: Job? = null

    /**
     * Shows the failure and offers the log on the local network at once, so a phone can fetch it
     * for an issue straight from the dialog. The log already holds the failure, written just before.
     */
    private fun fail(code: String, onDevice: Boolean) {
        _state.update { it.copy(failed = true, failedOnDevice = onDevice, failureCode = code, loading = false) }
        if (logServer != null || sharing?.isActive == true) return
        sharing = viewModelScope.launch {
            val server = try {
                diagnostics.shareOnNetwork()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Offering the log after a playback failure failed", e)
                null
            }
            if (server == null) {
                _state.update { it.copy(logOffline = it.failed) }
                return@launch
            }
            if (!_state.value.failed) {
                server.close()
                return@launch
            }
            logServer = server
            _state.update { it.copy(logShare = LogShare(server.url, server.fileName, server.sizeBytes)) }
        }
    }

    private fun closeLogShare() {
        sharing?.cancel()
        sharing = null
        logServer?.close()
        logServer = null
        _state.update { it.copy(logShare = null, logOffline = false) }
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
        const val MAX_OUTPUT_RECOVERIES = 3
        /** Long enough for the TV to finish switching its picture mode. */
        const val OUTPUT_RECOVERY_DELAY_MS = 1_500L
        /** A stall this long moves to a smaller stream at once. */
        const val LONG_STALL_MS = 8_000L
        /** Buffering this soon after a jump is the jump, not the link. */
        const val SEEK_BUFFERING_MS = 3_000L
        const val MAX_OPEN_RETRIES = 3
        /** Playing this close to the point of a hand over, the wait for the new stream is the hand over, not a stall. */
        const val HANDOVER_EDGE_MS = 2_000L
        /** Media3 tries a segment several times itself before an error reaches here, each with the long timeout. */
        const val MAX_LINK_RETRIES = 3
        /** The segment probe (debug): when it runs after the first picture, how long a segment is, how many it asks for. */
        const val PROBE_DELAY_MS = 25_000L
        const val PROBE_SEGMENT_MS = 10_000L
        const val PROBE_COUNT = 6
        const val PROBE_OLD_BEHIND = 6
        const val OPEN_RETRY_DELAY_MS = 3_000L
        /** A ladder's buffer is judged from this long after the start. */
        /** A new stream must show a picture within this time. */
        const val STARTUP_MS = 20_000L
        const val TRANSCODE_STARTUP_MS = 75_000L
        /** Errors of a connection that cannot keep up. */
        val LINK_ERRORS = setOf(
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        )

        /** Seconds ahead below which the buffer counts as nearly empty, and above which as recovered. */
        const val BUFFER_LOW_MS = 4_000L
        const val BUFFER_RECOVERED_MS = 10_000L
        /** Nearly empty for this long while playing: the stream drains the buffer. */
        const val BUFFER_DRAIN_MS = 5_000L
        /** The buffer of a stream that just started, or just jumped, fills first. */
        const val BUFFER_SETTLE_MS = 30_000L

        /** After a start or a seek the throughput is left alone this long: the player loads its first seconds in a burst. */
        const val THROUGHPUT_SETTLE_MS = 10_000L
        /** A reduced stream checks the link this often, and only with this much buffer ahead. */
        const val RECOVERY_CHECK_MS = 120_000L
        const val RECOVERY_BUFFER_MS = 20_000L
        const val MAX_RETURNS = 2
        /** Smaller streams one title may move to: 70 %, 49 %, 34 % of the first. */
        const val MAX_QUALITY_STEPS = 3

        /** A dead AudioTrack, or a runtime error such as a decoder refusing its new surface or a player stuck after either. */
        @OptIn(UnstableApi::class)
        fun PlaybackException.isOutputFailure() =
            errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED ||
                errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED ||
                (this is ExoPlaybackException && type == ExoPlaybackException.TYPE_UNEXPECTED)

        /** E.g. "DECODER_INIT_FAILED (4001)". */
        fun PlaybackException.failureCode() = "${errorCodeName.removePrefix("ERROR_CODE_")} ($errorCode)"

        /** A decoder or the audio output failed (Media3's 4xxx and 5xxx codes), not the network or the server. */
        fun PlaybackException.isDeviceFailure() =
            errorCode in PlaybackException.ERROR_CODE_DECODER_INIT_FAILED until PlaybackException.ERROR_CODE_DRM_UNSPECIFIED
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
