package io.github.glacier_jellyfin.androidtv.core.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.SystemClock
import android.util.Base64
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.PlayerMessage
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.ClippingMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ForwardingMediaSourceFactory
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.BandwidthMeter
import androidx.media3.exoplayer.upstream.DefaultAllocator
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.ui.SubtitleView
import io.github.glacier_jellyfin.androidtv.core.log.DebugOptions
import io.github.glacier_jellyfin.androidtv.core.log.Log
import io.github.peerless2012.ass.media.AssHandler
import io.github.peerless2012.ass.media.factory.AssRenderersFactory
import io.github.peerless2012.ass.media.parser.AssSubtitleParserFactory
import io.github.peerless2012.ass.media.type.AssRenderType
import io.github.peerless2012.ass.media.widget.AssSubtitleView

/** A song (or a music video, [video]) as the system shows it. */
data class SongMetadata(
    val title: String,
    val artist: String?,
    val album: String?,
    val artworkUrl: String?,
    val video: Boolean = false,
)

/** One stream to play: the URL the server handed out and the headers it needs. */
data class StreamRequest(
    val url: String,
    val isHls: Boolean,
    val headers: Map<String, String>,
    val startPositionMs: Long,
    /** Subtitle files loaded next to the stream. */
    val subtitles: List<SideloadedSubtitle> = emptyList(),
    /** A Live TV channel: starts at the live edge, [startPositionMs] does not apply. */
    val live: Boolean = false,
    val setup: PlaybackSetup = PlaybackSetup(),
    /** An HLS master playlist the app built (tiers of one transcode); played instead of [url]. */
    val playlist: String? = null,
    /** With [playlist]: the biggest tier to start with, in bit/s; the player then follows [GlacierPlayback.steer]. */
    val startBitrate: Int? = null,
    /**
     * The server makes this stream as it goes (a transcode of a title): it may be slow, so segments get time to arrive and
     * playback starts with more in the buffer. Not for live channels and previews, which must start and fail quickly.
     */
    val patient: Boolean = false,
    /** A film or an episode in the player: not a preview, a trailer or a live channel. Gets the big buffer, also for direct play. */
    val title: Boolean = false,
    /**
     * A start that comes from outside the player (the detail page, a resume): it may begin at the seek point of the file before
     * [startPositionMs], see [SyncPoints.snapsStart]. Never for what happens inside the player.
     */
    val snapStart: Boolean = false,
    /**
     * The audio of a title that may alternate between the file and a transcode; null for everything else (music, Live TV, a
     * title that only ever plays as a transcode). The player keeps the sound of all its streams on one output path, see [AudioPath].
     */
    val audio: TitleAudio? = null,
)

/** How this device plays what it cannot take as it is, decided by the app from its capabilities and settings. */
data class PlaybackSetup(
    val dolbyVisionProfile7: DolbyVisionProfile7 = DolbyVisionProfile7.AsIs,
    /** TrueHD and DTS-HD go to HDMI as IEC 61937 bursts that Glacier packs itself. */
    val iec61937: Boolean = false,
)

data class SideloadedSubtitle(
    /** Server stream index; becomes the Media3 track id, see [TrackControl]. */
    val index: Int,
    val url: String,
    val codec: String?,
    val language: String?,
)

/**
 * A hand over that waits to happen: the point where the next stream takes over, in ms of the playing item, how far it was moved
 * on because the next stream had loaded too little ([postponedMs], 0 when not), and what the next stream has loaded beyond the
 * point.
 */
data class HandOverProgress(
    val pointInItemMs: Long,
    val postponedMs: Long,
    val nextLoadedMs: Long,
)

/**
 * A player and its ASS renderer. libass draws ASS/SSA subtitles itself, in a
 * view that [attachAss] puts into the SubtitleView; every other format still
 * arrives as Media3 cues.
 */
@OptIn(UnstableApi::class)
class GlacierPlayback(
    val player: ExoPlayer,
    private val ass: AssHandler,
    private val meter: SteeredBandwidthMeter,
    /** The item of a title as it plays (a clipped source, see [GlacierPlayer.titleItem]); null for streams that cannot be handed over. */
    private var current: MediaItem? = null,
    /** Gives the connections the patient timeouts of a transcode, which a stream that started as the file does not have. */
    private val makePatient: () -> Unit = {},
    /** Lets the stream that is handed over to load while the playing one still does. */
    private val loadControl: HandoverLoadControl? = null,
    /** The audio of a title that may alternate between streams, see [StreamRequest.audio]; null when its sound is left alone. */
    private val titleAudio: TitleAudio? = null,
    /** The title's first stream is the file, so its first audio output tells how the file's sound leaves the device. */
    private val startsAsFile: Boolean = false,
    /** Makes the audio sink decode compressed sound to PCM (true) or leave passing it on to Media3 (false). */
    private val decodeSwitch: (Boolean) -> Unit = {},
) {

    /**
     * Whether the file's sound of this title is decoded on this device, null while that is not known. Kept by the app across
     * the players of one title, see [TitleAudio.decoded].
     */
    var fileAudioDecoded: Boolean? = titleAudio?.decoded
        private set

    /** Records how the file's sound leaves the device and sets the audio sink to match; once per title, see [AudioPath]. */
    internal fun setFileDecoded(decoded: Boolean, announce: Boolean = true) {
        val decode = AudioPath.decodeOtherStreams(titleAudio != null, decoded)
        fileAudioDecoded = decoded
        decodeSwitch(decode)
        if (decode && announce) Log.i(TAG, "Audio of other streams is decoded like the file's (PCM)")
    }

    /** The stream waiting to take over. */
    private var pending: MediaItem? = null
    private var handOvers = 0

    /** The message that moves the player on to [pending] when the playing stream has reached the hand over point. */
    private var switchMessage: PlayerMessage? = null

    /** Where the hand over was planned, in ms of the playing item, and how far it has been moved on since. */
    private var plannedInItemMs = 0L
    private var postponedMs = 0L

    /**
     * The end of the furthest media load of the stream that waits that has completed, in ms of the title (the time base of
     * [MediaLoadData.mediaEndTimeMs], which the clipping source passes on as it is); [C.TIME_UNSET] while none has, and for a
     * file, whose loads have no end time.
     */
    @Volatile
    private var pendingLoadedToMs = C.TIME_UNSET

    /** What the stream that waits has loaded, in ms beyond the point where it takes over; see [loadedMs]. */
    internal val nextLoadedMs: Long get() = loadedMs(loadControl?.preloadedMs ?: Long.MAX_VALUE)

    /**
     * Media3 asks the load control about the preloaded stream only between two of its loads, so [HandoverLoadControl.preloadedMs]
     * lags behind by a segment, or by all that was loaded since the last question. The completed loads are reported at once, so
     * the larger of both is taken; for a file the load control's value is all there is.
     */
    private fun loadedMs(fromLoadControl: Long): Long {
        val startMs = pending?.clippingConfiguration?.startPositionMs ?: return fromLoadControl
        val loadedTo = pendingLoadedToMs
        if (loadedTo == C.TIME_UNSET) return fromLoadControl
        return maxOf(fromLoadControl, (loadedTo - startMs).coerceAtLeast(0))
    }

    /**
     * Where the pending hand over stands, for the info panel; null without one. The point is in ms of the playing item (the
     * player's own positions); what the next stream has loaded is counted beyond the point, 0 while nothing is known.
     */
    fun handOverProgress(): HandOverProgress? {
        if (pending == null) return null
        return HandOverProgress(
            pointInItemMs = plannedInItemMs + postponedMs,
            postponedMs = postponedMs,
            nextLoadedMs = nextLoadedMs.takeIf { it != Long.MAX_VALUE }?.coerceAtLeast(0) ?: 0L,
        )
    }

    /** For the log: how far the completed loads reach beyond the hand over point, "none" for a file or before the first. */
    private fun loadedToLog(): String {
        val startMs = pending?.clippingConfiguration?.startPositionMs ?: return "none"
        val loadedTo = pendingLoadedToMs
        return if (loadedTo == C.TIME_UNSET) "none" else "${(loadedTo - startMs).coerceAtLeast(0) / 1000} s"
    }

    /** What the playing stream loads beyond the hand over point while the next stream loads ahead. */
    val handOverLoadAheadMs: Long get() = POSTPONE_MAX_MS

    /**
     * The first point at or after [atMs] where the file of [request] can be entered without decoding and discarding what is
     * before it, if its seek points are known (the player or an earlier open has read them) and it is no more than
     * [maxShiftMs] later. Matroska has cue points only every 20 to 40 s; a stream that starts between two of them first
     * decodes from the one before, which made the hand over to the file stall for seconds.
     */
    fun syncPointAtOrAfter(
        request: StreamRequest,
        atMs: Long,
        maxShiftMs: Long,
    ): Long? = if (request.isHls) null else SyncPoints.atOrAfter(request.url, atMs, maxShiftMs)

    /** True for a title: its stream can be handed over to another without the player starting again. */
    val canHandOver: Boolean get() = current != null

    /** The media id of the stream that waits to take over; null when none does. */
    val handOverId: String? get() = pending?.mediaId

    /**
     * Plays [next] from [atMs] (the position in the title) on, after the stream that plays now. The playing item stays as it
     * is, with its buffer: Media3 resets the playing period (and with it the buffer, when the file has no keyframe at the
     * playing position) as soon as its duration changes while a renderer has read up to the new end, and a renderer of a
     * sparse track (the next subtitle cue) does that; see `MediaPeriodQueue.updateQueuedPeriods`. So [next] is only appended;
     * it is loaded ahead (preloading, see [HandoverLoadControl]) while the playing stream goes on, and the player moves on to
     * it when the playing one reaches [atMs]. The positions of the player are relative to the start of the item then (see
     * [GlacierPlayer.titleItem]). False when this cannot be done.
     *
     * Until then the playing stream loads no further than [POSTPONE_MAX_MS] beyond [atMs], so the next one has the connection
     * to itself. If it has not loaded [READY_MS] by [atMs] and the playing stream still has data, the move waits, in steps of
     * [POSTPONE_STEP_MS] and for [POSTPONE_MAX_MS] at most.
     */
    fun handOver(atMs: Long, next: StreamRequest): Boolean {
        val item = current ?: return false
        if (pending != null || player.mediaItemCount != 1) return false
        val inItemMs = atMs - item.clippingConfiguration.startPositionMs
        if (inItemMs <= 0) return false
        makePatient()
        // The tier the new stream starts with is chosen when its source is prepared, which is before it plays.
        if (next.playlist != null) next.startBitrate?.let(meter::steer) else meter.unsteer()
        val nextItem = GlacierPlayer.titleItem(next, "handover-${++handOvers}", atMs)
        loadControl?.beginPreloading(untilPlayingMs = inItemMs + POSTPONE_MAX_MS)
        player.setPreloadConfiguration(ExoPlayer.PreloadConfiguration(PRELOAD_US))
        player.addMediaItem(nextItem)
        pending = nextItem
        plannedInItemMs = inItemMs
        postponedMs = 0
        pendingLoadedToMs = C.TIME_UNSET
        scheduleMove(nextItem, inItemMs)
        Log.i(
            TAG,
            "Hand over at $atMs ms: the playing stream goes on, the next is queued and loads ahead " +
                "(${stateName(player.playbackState)} at ${player.currentPosition} ms, ${player.mediaItemCount} items)",
        )
        return true
    }

    private fun scheduleMove(item: MediaItem, atInItemMs: Long) {
        switchMessage?.cancel()
        switchMessage = player.createMessage { _, _ -> moveOn(item) }
            .setLooper(player.applicationLooper)
            .setPosition(0, atInItemMs)
            .setDeleteAfterDelivery(true)
            .send()
    }

    /**
     * The playing stream has reached the hand over point: on to the stream that waits, unless that has hardly loaded and the
     * playing one has data to bridge a little longer.
     */
    private fun moveOn(item: MediaItem) {
        if (pending !== item || player.mediaItemCount < 2 || player.currentMediaItemIndex != 0) return
        val position = player.currentPosition
        val fromLoadControl = loadControl?.preloadedMs ?: Long.MAX_VALUE
        val loaded = loadedMs(fromLoadControl)
        val nextStop = plannedInItemMs + postponedMs + POSTPONE_STEP_MS
        if (loaded < READY_MS && postponedMs + POSTPONE_STEP_MS <= POSTPONE_MAX_MS &&
            player.bufferedPosition >= nextStop + KEEP_MS
        ) {
            postponedMs += POSTPONE_STEP_MS
            Log.i(
                TAG,
                "Hand over point reached at $position ms, the next stream has ${loaded / 1000} s (load control ${fromLoadControl / 1000} s, completed loads ${loadedToLog()}): waiting, now $postponedMs ms past the plan",
            )
            scheduleMove(item, plannedInItemMs + postponedMs)
            return
        }
        // The stream that waits begins at the hand over point; the way on from there is its position, in its own item.
        val into = (position - plannedInItemMs).takeIf { it >= MIN_SKIP_MS } ?: 0L
        Log.i(
            TAG,
            "Hand over point reached at $position ms (${loaded / 1000} s of the next stream loaded, load control ${fromLoadControl / 1000} s, completed loads ${loadedToLog()}), moving on to the next stream" +
                if (into > 0) " at +$into ms" else "",
        )
        enterNext(into)
    }

    /**
     * Moves the player on to the stream that waits, at [positionMs] of its item. Media3 takes a period it has loaded ahead only
     * for the start position of its item (`MediaPeriodHolder.canBeUsedForMediaPeriodInfo`), so the player goes there first and
     * moves to [positionMs] once it has taken that period over, a jump inside a period that keeps what it has loaded.
     */
    fun enterNext(positionMs: Long = 0) {
        if (player.mediaItemCount < 2) return
        player.seekToNextMediaItem()
        if (positionMs > 0) afterTakeOver { player.seekTo(positionMs) }
    }

    /**
     * Runs [action] once the player has taken over the period it loaded ahead. A seek makes the playback thread drop its
     * periods and queue the next one up in a work step of its own (`ExoPlayerImplInternal.seekToPeriodPosition`, then
     * `maybeUpdateLoadingPeriod`), and the listeners hear of the seek while that step is still to come. What the app asks of
     * the player in that callback is queued before it: a removed item or the end of preloading
     * (`MediaPeriodQueue.releasePreloadPool`) then dropped the loaded period, which was loaded again. A message of the player
     * is delivered after the seek has been handled, so what the action asks of the player comes behind that step.
     */
    private fun afterTakeOver(action: () -> Unit) {
        player.createMessage { _, _ -> action() }
            .setLooper(player.applicationLooper)
            .setDeleteAfterDelivery(true)
            .send()
    }

    /** Media3 has moved on to the stream that was handed over: the old one is let go (see [afterTakeOver]). */
    fun finishHandOver() {
        val next = pending ?: return
        switchMessage?.cancel()
        switchMessage = null
        current = next
        pending = null
        pendingLoadedToMs = C.TIME_UNSET
        loadControl?.endPreloading()
        afterTakeOver {
            if (player.mediaItemCount > 1) player.removeMediaItem(0)
            player.setPreloadConfiguration(ExoPlayer.PreloadConfiguration.DEFAULT)
            Log.i(TAG, "Hand over done, the old stream is released (${player.mediaItemCount} item)")
        }
    }

    /** The stream that was handed over to is not wanted after all: the playing one goes on, nothing follows it. */
    fun cancelHandOver() {
        if (pending == null) return
        switchMessage?.cancel()
        switchMessage = null
        pending = null
        pendingLoadedToMs = C.TIME_UNSET
        if (player.mediaItemCount > 1) player.removeMediaItem(1)
        loadControl?.endPreloading()
        player.setPreloadConfiguration(ExoPlayer.PreloadConfiguration.DEFAULT)
        Log.i(TAG, "Hand over cancelled, the playing stream goes on")
    }

    private fun stateName(state: Int) = when (state) {
        Player.STATE_IDLE -> "idle"
        Player.STATE_BUFFERING -> "buffering"
        Player.STATE_READY -> "ready"
        else -> "ended"
    }

    /**
     * Makes the player choose the tier of [bitrate] (bit/s) of a ladder from the next segment on. Media3 switches
     * between tiers by its speed estimate, which for a transcode is low, since the server takes its time for every
     * segment, so the app gives it the estimate that suits the tier it wants. The buffer already loaded plays on.
     */
    fun steer(bitrate: Int) = meter.steer(bitrate)

    /**
     * Bit/s the player's connections loaded in the last [windowMs] (up to 30 s). Media3's estimate does not move during a
     * long transfer, see [DownloadRate].
     */
    fun downloadRate(windowMs: Long): Long = DownloadRate.shared.bitsPerSecond(windowMs)

    /** The most the load control buffers ahead, in ms: the scale of the buffer bar in the info panel. */
    var maxBufferMs: Int = DefaultLoadControl.DEFAULT_MAX_BUFFER_MS
        internal set

    /** The video decoder Media3 picked, for the info panels; also known when the stream changed hands. */
    var videoDecoder: String? = null
        private set
    var audioDecoder: String? = null
        private set

    /** How the sound leaves the device: the audio track's encoding (C.ENCODING_*), null until it is set up. */
    var audioEncoding: Int? = null
        private set

    /** The audio track's channel mask (AudioFormat.CHANNEL_OUT_*). */
    var audioChannelMask: Int = 0
        private set

    init {
        addDecoderListener()
    }

    private var audioStartAt = 0L

    /** One line of the audio start timeline: [what], in ms since the audio was enabled, or a seek or item change; the first line starts it if none did. */
    private fun audioStart(what: String) {
        val now = SystemClock.elapsedRealtime()
        if (audioStartAt == 0L) audioStartAt = now
        Log.i("AudioStart", "+${now - audioStartAt} ms $what")
    }

    private fun audioStartReset(what: String) {
        audioStartAt = SystemClock.elapsedRealtime()
        audioStart(what)
    }

    @OptIn(UnstableApi::class)
    private fun addDecoderListener() {
        player.addAnalyticsListener(object : AnalyticsListener {
            // The stream that waits is the second item. Its completed media loads tell how far it has loaded at once, which the
            // load control only learns between two loads; a file has no end time on its loads and is skipped.
            override fun onLoadCompleted(
                eventTime: AnalyticsListener.EventTime,
                loadEventInfo: LoadEventInfo,
                mediaLoadData: MediaLoadData,
            ) {
                if (pending == null || eventTime.windowIndex != 1 || mediaLoadData.dataType != C.DATA_TYPE_MEDIA) return
                if (mediaLoadData.mediaEndTimeMs == C.TIME_UNSET) return
                pendingLoadedToMs = maxOf(pendingLoadedToMs, mediaLoadData.mediaEndTimeMs)
            }

            // Logged on every playback: a bug report shows the decoders and the sound output without detailed logging.
            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                videoDecoder = decoderName
                Log.i(TAG, "Video decoder: $decoderName")
            }

            override fun onAudioDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                audioDecoder = decoderName
                Log.i(TAG, "Audio decoder: $decoderName")
                audioStart("decoder $decoderName ready, init $initializationDurationMs ms")
            }

            // Timeline of one audio start ("AudioStart"): where the time between the decoder and the audio output goes.
            override fun onAudioEnabled(eventTime: AnalyticsListener.EventTime, decoderCounters: DecoderCounters) {
                audioStartReset("audio enabled")
            }

            override fun onPositionDiscontinuity(
                eventTime: AnalyticsListener.EventTime,
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                audioStartReset("discontinuity reason $reason to ${newPosition.positionMs} ms")
            }

            override fun onMediaItemTransition(
                eventTime: AnalyticsListener.EventTime,
                mediaItem: MediaItem?,
                reason: Int,
            ) {
                audioStartReset("media item transition reason $reason")
            }

            override fun onAudioInputFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                format: Format,
                decoderReuseEvaluation: DecoderReuseEvaluation?,
            ) {
                audioStart(
                    "input ${format.codecs?.let {
                        "$it "
                    } ?: ""}${format.sampleMimeType} ${format.channelCount} ch ${format.sampleRate} Hz ${format.averageBitrate} bit/s",
                )
            }

            override fun onAudioPositionAdvancing(
                eventTime: AnalyticsListener.EventTime,
                playoutStartSystemTimeMs: Long,
            ) {
                audioStart(
                    "position advancing, playout start ${playoutStartSystemTimeMs - SystemClock.elapsedRealtime()} ms from now (system time)",
                )
            }

            override fun onAudioUnderrun(
                eventTime: AnalyticsListener.EventTime,
                bufferSize: Int,
                bufferSizeMs: Long,
                elapsedSinceLastFeedMs: Long,
            ) {
                audioStart("underrun, buffer $bufferSizeMs ms, $elapsedSinceLastFeedMs ms since last feed")
            }

            override fun onRenderedFirstFrame(
                eventTime: AnalyticsListener.EventTime,
                output: Any,
                renderTimeMs: Long,
            ) {
                audioStart("first video frame")
            }

            override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
                audioStart("state ${stateName(state)}, buffered ${player.totalBufferedDuration} ms")
            }

            override fun onAudioTrackInitialized(
                eventTime: AnalyticsListener.EventTime,
                audioTrackConfig: AudioSink.AudioTrackConfig,
            ) {
                audioEncoding = audioTrackConfig.encoding
                audioChannelMask = audioTrackConfig.channelConfig
                // The first output of a title that starts as the file is the file's own: it tells which way the sound goes.
                if (titleAudio != null && startsAsFile && fileAudioDecoded == null) {
                    setFileDecoded(AudioPath.isDecoded(audioTrackConfig.encoding))
                }
                val channels = Integer.bitCount(audioTrackConfig.channelConfig)
                Log.i(
                    TAG,
                    "Audio output: ${encodingName(audioTrackConfig.encoding)}, " +
                        "$channels channels, ${audioTrackConfig.sampleRate} Hz",
                )
                audioStart("track ${encodingName(audioTrackConfig.encoding)} ${audioTrackConfig.sampleRate} Hz")
            }
        })
    }

    @OptIn(UnstableApi::class)
    fun attachAss(subtitleView: SubtitleView) {
        subtitleView.addView(AssSubtitleView(subtitleView.context, ass))
    }

    internal companion object {
        private const val TAG = "Player"

        /** How much of the stream that is handed over to is loaded ahead: more than a new stream needs to start (8 s). */
        private const val PRELOAD_US = 12_000_000L

        /** The stream that is handed over to has loaded this much when the player moves on to it, else the move waits. */
        const val READY_MS = 5_000L

        /** The move waits in steps of this, up to the most; the playing stream is loaded as far as the most beyond the plan. */
        const val POSTPONE_STEP_MS = 2_000L
        const val POSTPONE_MAX_MS = 10_000L

        /** A wait needs this much of the playing stream's buffer beyond the new point. */
        private const val KEEP_MS = 500L

        /** Less than this past the plan counts as the plan: no jump inside the stream that is handed over to. */
        private const val MIN_SKIP_MS = 250L

        /** "E-AC-3 bitstream", "PCM 16-bit": whether the receiver gets the bitstream or decoded sound. */
        fun encodingName(encoding: Int): String = when (encoding) {
            C.ENCODING_AC3 -> "AC-3 bitstream"
            C.ENCODING_E_AC3 -> "E-AC-3 bitstream"
            C.ENCODING_E_AC3_JOC -> "E-AC-3 JOC bitstream"
            C.ENCODING_AC4 -> "AC-4 bitstream"
            C.ENCODING_DOLBY_TRUEHD -> "TrueHD bitstream"
            C.ENCODING_DTS -> "DTS bitstream"
            C.ENCODING_DTS_HD -> "DTS-HD bitstream"
            C.ENCODING_PCM_16BIT -> "PCM 16-bit"
            C.ENCODING_PCM_24BIT -> "PCM 24-bit"
            C.ENCODING_PCM_32BIT -> "PCM 32-bit"
            C.ENCODING_PCM_FLOAT -> "PCM float"
            else -> "encoding $encoding"
        }
    }
}

/**
 * The load control of a title, set as the builder of [GlacierPlayer.create] does it (min = max buffer, time not over size). Media3
 * preloads the next item of the playlist only while no player loads, which the stream that plays always does on a link slower
 * than the stream; during a hand over ([GlacierPlayback.handOver]) it is wanted at once, and the stream that plays stops
 * loading a little beyond the hand over point, so the connection goes to the one that is loaded ahead.
 */
@OptIn(UnstableApi::class)
class HandoverLoadControl(
    minBufferMs: Int,
    maxBufferMs: Int,
    bufferForPlaybackMs: Int,
    bufferForPlaybackAfterRebufferMs: Int,
    targetBufferBytes: Int,
) : DefaultLoadControl(
    DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE),
    minBufferMs,
    minBufferMs,
    maxBufferMs,
    maxBufferMs,
    bufferForPlaybackMs,
    bufferForPlaybackMs,
    bufferForPlaybackAfterRebufferMs,
    bufferForPlaybackAfterRebufferMs,
    targetBufferBytes,
    false,
    false,
    DefaultLoadControl.DEFAULT_BACK_BUFFER_DURATION_MS,
    DefaultLoadControl.DEFAULT_RETAIN_BACK_BUFFER_FROM_KEYFRAME,
) {
    /** Set while a stream waits to take over; read on the playback thread. */
    @Volatile
    var preloadNext = false
        private set

    /** The position in the playing item (ms) beyond which it loads no more while [preloadNext]. */
    @Volatile
    private var stopAtMs = Long.MAX_VALUE

    /**
     * How much of the stream that waits is loaded, in ms from its start. Media3 asks when the stream is between two loads (after
     * a segment of a ladder, every few hundred KB of a file), which is how often this moves.
     */
    @Volatile
    var preloadedMs = 0L
        private set

    internal fun beginPreloading(untilPlayingMs: Long) {
        preloadedMs = 0
        stopAtMs = untilPlayingMs
        preloadNext = true
    }

    internal fun endPreloading() {
        preloadNext = false
        stopAtMs = Long.MAX_VALUE
        preloadedMs = 0
    }

    override fun shouldContinuePreloading(
        playerId: PlayerId,
        timeline: Timeline,
        mediaPeriodId: MediaPeriodId,
        bufferedDurationUs: Long,
    ): Boolean {
        if (!preloadNext) return super.shouldContinuePreloading(playerId, timeline, mediaPeriodId, bufferedDurationUs)
        // What is passed as the buffered duration is the buffered position of the period: its position in its item, once the
        // start of the item (the clipping start, which is where the period starts) is taken off. Nothing before it is prepared.
        val period = timeline.getPeriodByUid(mediaPeriodId.periodUid, Timeline.Period())
        preloadedMs = when (bufferedDurationUs) {
            C.TIME_END_OF_SOURCE -> Long.MAX_VALUE
            else -> ((bufferedDurationUs + period.positionInWindowUs) / 1000).coerceAtLeast(0)
        }
        return true
    }

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
        val loads = super.shouldContinueLoading(parameters)
        if (!loads || stopAtMs == Long.MAX_VALUE) return loads
        val period = parameters.timeline.getPeriodByUid(parameters.mediaPeriodId.periodUid, Timeline.Period())
        // Only the playing item (the first); the one that waits is preloaded, and takes the connection.
        if (period.windowIndex != 0) return true
        val bufferedUntilMs =
            (parameters.playbackPositionUs + period.positionInWindowUs + parameters.bufferedDurationUs) / 1000
        return bufferedUntilMs < stopAtMs
    }
}

/** Creates the ExoPlayers for video and music, set up for TV playback. */
object GlacierPlayer {

    private const val REBUFFER_MS = 10_000
    private const val RUN_UP_MS = 8_000
    private const val MB = 1024L * 1024L

    /** The server delivers a transcode about 2.8 times faster than real time; 120 s of it lasts through a tier switch. */
    private const val VIDEO_BUFFER_MS = 120_000

    /**
     * Media3 moves to a smaller tier only while less than this is buffered (15 s by default). The app decides when to move, after
     * a measurement, and a move costs the server a new transcode (16 to 29 s), so it must happen while the buffer, up to
     * about 120 s, can still cover that: the block is lifted by a value above any buffer.
     */
    private const val DECREASE_ANYTIME_MS = 600_000
    private const val HLS_CONNECT_TIMEOUT_MS = 15_000
    private const val HLS_READ_TIMEOUT_MS = 60_000

    /** The share of its speed estimate Media3 lets a tier take; [SteeredBandwidthMeter.steer] counts with it. */
    const val STEER_FRACTION = 0.9f

    @OptIn(UnstableApi::class)
    private fun makePatient(factory: DefaultHttpDataSource.Factory) {
        factory.setConnectTimeoutMs(HLS_CONNECT_TIMEOUT_MS)
        factory.setReadTimeoutMs(HLS_READ_TIMEOUT_MS)
    }

    /**
     * Gives every item of a title a [ClippingMediaSource] that clips inside the media period: the item of a stream handed over
     * to starts at the hand over point, and the period itself starts there instead of Media3 seeking after it. Media3 builds a
     * clipping source only for items that clip something, so the items that play all of the stream are wrapped here too.
     * [factory] builds the source of the stream.
     */
    @OptIn(UnstableApi::class, ExperimentalApi::class)
    internal class ClippedSources(private val factory: MediaSource.Factory) : ForwardingMediaSourceFactory(factory) {
        override fun createMediaSource(mediaItem: MediaItem): MediaSource = ClippingMediaSource.Builder(
            factory.createMediaSource(
                mediaItem.buildUpon().setClippingConfiguration(MediaItem.ClippingConfiguration.UNSET).build(),
            ),
        )
            .setClippingConfiguration(mediaItem.clippingConfiguration)
            .setEnableClippingInMediaPeriod(true)
            .build()
    }

    /**
     * The item of a stream of a title. Its clipping starts at [startMs], where in the title this stream takes over (0 for the
     * first), and ends where the stream ends: a stream plays the whole title (the playlist or the file spans it), so the
     * player's positions of the item are relative to [startMs].
     */
    internal fun titleItem(
        request: StreamRequest,
        id: String,
        startMs: Long,
    ): MediaItem = itemOf(request)
        .buildUpon()
        .setMediaId(id)
        .setClippingConfiguration(
            MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(startMs)
                // From the start no sample is cut off: no jump at the beginning. Later it starts inside a segment.
                .setStartsAtKeyFrame(startMs == 0L)
                .build(),
        )
        .build()

    private fun itemOf(request: StreamRequest): MediaItem = MediaItem.Builder()
        .setUri(request.playlist?.let(::playlistUri) ?: Uri.parse(request.url))
        .apply { if (request.isHls) setMimeType(MimeTypes.APPLICATION_M3U8) }
        .setSubtitleConfigurations(request.subtitles.map { it.toConfiguration() })
        .build()

    /** A playlist the app wrote, as an address Media3 reads without a request. */
    private fun playlistUri(playlist: String): Uri =
        Uri.parse("data:application/x-mpegURL;base64," + Base64.encodeToString(playlist.toByteArray(), Base64.NO_WRAP))

    @OptIn(UnstableApi::class)
    fun create(context: Context, request: StreamRequest): GlacierPlayback {
        // The server answers a segment of a transcode when it has made it, and a slow transcode takes far longer
        // than the 8 s Media3 waits by default: that ended the playback with an error.
        val dataSource = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(request.headers)
            .setAllowCrossProtocolRedirects(true)
            .apply { if (request.patient) makePatient(this) }
        // Rendered on its own GL thread above the video, with animation and embedded fonts.
        val ass = AssHandler(AssRenderType.OVERLAY_OPEN_GL)
        val assParsers = AssSubtitleParserFactory(ass)
        // libass's Matroska extractor, which also hands on Dolby Vision block additions.
        val extractors = DefaultExtractorsFactory().withDolbyVisionMkvSupport(assParsers, ass)
        // HTTP for the server's streams; asset:// for the playback check's samples.
        val sources = ThrottledDataSource.Factory(DefaultDataSource.Factory(context, dataSource))
        val profile7 = request.setup.dolbyVisionProfile7
        Log.i(
            "Player",
            "Setup: Dolby Vision profile 7 $profile7, IEC 61937 ${if (request.setup.iec61937) "on" else "off"}",
        )
        val mediaSources = DefaultMediaSourceFactory(
            sources,
            if (profile7 == DolbyVisionProfile7.AsIs) {
                extractors
            } else {
                DolbyVisionProfile7Extractors(extractors, profile7)
            },
        )
            // Side-loaded .ass files go through the same parser.
            .setSubtitleParserFactory(assParsers)
        // A silent bitstream track: a seek to where the player stands flushes the sink, which opens a new one.
        var built: ExoPlayer? = null
        val guarded = GuardedRenderersFactory(context, request.setup.iec61937) {
            built?.let { player -> Handler(player.applicationLooper).post { player.seekTo(player.currentPosition) } }
        }
        val renderers = guarded
            // Platform decoders first; a software fallback only when a hardware decoder fails.
            .setEnableDecoderFallback(true)
            // FFmpeg (core/ffmpeg) for audio formats no platform decoder or passthrough handles, e.g. DTS.
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        // After a stall it takes more buffer to start again (default 5 s), so a link that just kept up
        // does not run dry again at once.
        // Live has no stock to buffer, previews must start fast: only a title gets the big buffer, which carries a tier
        // switch (a new transcode takes 16 to 29 s) and the high bitrates of direct play.
        val bigBuffer = request.title && !request.live
        // The stream of a title may be handed over to another one while it plays.
        val handOverable = bigBuffer
        val bufferMs = if (bigBuffer) VIDEO_BUFFER_MS else DefaultLoadControl.DEFAULT_MAX_BUFFER_MS
        val maxMemory = Runtime.getRuntime().maxMemory()
        var targetBytes: Int? = null
        val loadControlBuilder = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                // Min = max: Media3 keeps loading instead of pausing between the two.
                if (bigBuffer) bufferMs else DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                bufferMs,
                // A transcode produces its segments at the server's pace: it starts with more in the buffer.
                if (request.patient) RUN_UP_MS else DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                REBUFFER_MS,
            )
        if (bigBuffer) {
            // Sized by the heap: a high-bitrate remux hits the default byte limit after about 14 s. The byte limit
            // must keep deciding (not the time), or a full heap ends in an OutOfMemoryError.
            targetBytes = BufferSizing.targetBytes(
                maxMemory,
                DefaultLoadControl.DEFAULT_VIDEO_BUFFER_SIZE + DefaultLoadControl.DEFAULT_AUDIO_BUFFER_SIZE,
            )
            loadControlBuilder.setTargetBufferBytes(targetBytes).setPrioritizeTimeOverSizeThresholds(false)
            Log.i("Player", "Buffer: ${bufferMs / 1000} s, ${targetBytes / MB} MB target, ${maxMemory / MB} MB heap")
        } else {
            Log.i("Player", "Buffer: ${bufferMs / 1000} s, default byte target, ${maxMemory / MB} MB heap")
        }
        // A title takes a hand over: its load control also loads the next item while the playing one loads.
        val handOverLoadControl = if (handOverable) {
            HandoverLoadControl(
                minBufferMs = bufferMs,
                maxBufferMs = bufferMs,
                bufferForPlaybackMs = if (request.patient) RUN_UP_MS else DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                bufferForPlaybackAfterRebufferMs = REBUFFER_MS,
                targetBufferBytes = targetBytes ?: C.LENGTH_UNSET,
            )
        } else {
            null
        }
        val loadControl = handOverLoadControl ?: loadControlBuilder.build()
        // Tiers of one transcode: quality comes first, so it moves up as soon as the link allows and down only when
        // the buffer runs low, and uses more of the measured speed than Media3's default 70 %.
        val trackSelector = DefaultTrackSelector(
            context,
            AdaptiveTrackSelection.Factory(5_000, DECREASE_ANYTIME_MS, 25_000, STEER_FRACTION),
        )
        // Media3 leaves out tiers bigger than the display; a test of the display scaling a bigger picture down lets them in.
        if (!DebugOptions.fitDisplay) {
            trackSelector.setParameters(trackSelector.buildUponParameters().setViewportSize(3840, 2160, true))
        }
        val meter = SteeredBandwidthMeter(DefaultBandwidthMeter.getSingletonInstance(context))
        request.startBitrate?.let { if (request.playlist != null) meter.steer(it) }
        val player = ExoPlayer.Builder(context, AssRenderersFactory(ass, renderers))
            .setBandwidthMeter(meter)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(if (handOverable) ClippedSources(mediaSources) else mediaSources)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */
                true,
            )
            .build()
        built = player
        Media3Logs.attach(player)
        // Which tier of a ladder plays, for tests of the switching.
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onLoadCompleted(
                eventTime: AnalyticsListener.EventTime,
                loadEventInfo: LoadEventInfo,
                mediaLoadData: MediaLoadData,
            ) {
                if (!DebugOptions.segmentLog) return
                val kind = when (mediaLoadData.dataType) {
                    C.DATA_TYPE_MEDIA -> "segment"
                    C.DATA_TYPE_MEDIA_INITIALIZATION -> "init"
                    C.DATA_TYPE_MANIFEST -> "playlist"
                    else -> "type ${mediaLoadData.dataType}"
                }
                val ms = loadEventInfo.loadDurationMs.coerceAtLeast(1)
                Log.i(
                    "Segment",
                    "$kind ${loadEventInfo.bytesLoaded / 1024} KiB in $ms ms (${loadEventInfo.bytesLoaded * 8 / ms} kbit/s), " +
                        "tier ${mediaLoadData.trackFormat?.height}p ${(mediaLoadData.trackFormat?.bitrate ?: 0) / 1000} kbit/s, at ${mediaLoadData.mediaStartTimeMs / 1000} s, " +
                        "ahead ${(eventTime.totalBufferedDurationMs) / 1000} s",
                )
            }

            override fun onLoadStarted(
                eventTime: AnalyticsListener.EventTime,
                loadEventInfo: LoadEventInfo,
                mediaLoadData: MediaLoadData,
            ) {
                if (!DebugOptions.segmentLog || mediaLoadData.dataType != C.DATA_TYPE_MEDIA) return
                Log.i(
                    "Segment",
                    "load starts: tier ${mediaLoadData.trackFormat?.height}p ${(mediaLoadData.trackFormat?.bitrate ?: 0) / 1000} kbit/s, estimate ${meter.bitrateEstimate / 1000} kbit/s, " +
                        "ahead ${eventTime.totalBufferedDurationMs / 1000} s",
                )
            }

            override fun onLoadError(
                eventTime: AnalyticsListener.EventTime,
                loadEventInfo: LoadEventInfo,
                mediaLoadData: MediaLoadData,
                error: java.io.IOException,
                wasCanceled: Boolean,
            ) {
                if (DebugOptions.segmentLog) {
                    Log.w(
                        "Segment",
                        "load failed after ${loadEventInfo.loadDurationMs} ms: ${error.javaClass.simpleName}",
                    )
                }
            }

            override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
                if (DebugOptions.segmentLog) {
                    val name = when (state) {
                        Player.STATE_IDLE -> "idle"
                        Player.STATE_BUFFERING -> "BUFFERING"
                        Player.STATE_READY -> "ready"
                        else -> "ended"
                    }
                    Log.i("Segment", "state $name, ahead ${eventTime.totalBufferedDurationMs / 1000} s")
                }
            }

            override fun onVideoInputFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                format: Format,
                decoderReuseEvaluation: DecoderReuseEvaluation?,
            ) {
                if (request.playlist != null) {
                    Log.i(
                        "Player",
                        "Video tier ${format.bitrate / 1000} kbit/s ${format.width}x${format.height}",
                    )
                }
            }
        })
        val item = if (handOverable) titleItem(request, "title", 0) else itemOf(request)
        // Media3 gives a start position of a file as it is and decodes from the seek point before it, discarding what it decodes.
        var startMs = request.startPositionMs
        val snapKey = if (request.snapStart && !request.live && !request.isHls && startMs > 0) {
            SyncPoints.keyOf(request.url)
        } else {
            null
        }
        if (snapKey != null) {
            val point = SyncPoints.atOrBefore(request.url, startMs, SyncPoints.START_SNAP_MAX_MS)
            if (SyncPoints.snapsStart(true, startMs, point)) {
                Log.i("Player", "Start moved ${startMs - point!!} ms back to the file's sync point at $point ms")
                startMs = point
            }
        }
        player.apply {
            if (request.live) setMediaItem(item) else setMediaItem(item, startMs)
            prepare()
        }
        // A file not seen before: its seek points arrive while the player first buffers; if it still does, it starts again at the point.
        if (snapKey != null && startMs == request.startPositionMs) {
            val wanted = startMs
            SyncPoints.whenKnown(snapKey) { seekMap ->
                Handler(player.applicationLooper).post {
                    val point = SyncPoints.lastAtOrBefore(seekMap, wanted, SyncPoints.START_SNAP_MAX_MS)
                    val untouched = player.playbackState == Player.STATE_BUFFERING && player.currentPosition == wanted
                    if (untouched && SyncPoints.snapsStart(true, wanted, point)) {
                        Log.i("Player", "Start moved ${wanted - point!!} ms back to the file's sync point at $point ms")
                        player.seekTo(point)
                    }
                }
            }
        }
        ass.init(player)
        // One output path for all streams of a title: where the file's sound is decoded here, so is the transcode's.
        val titleAudio = request.audio?.takeIf { !request.live }
        val startsAsFile = !request.isHls && request.playlist == null
        val decodeSwitch = { decode: Boolean -> guarded.sink?.decodeCompressed = decode }
        var predicted: Boolean? = titleAudio?.decoded
        if (titleAudio != null && predicted == null && !startsAsFile) {
            // The file has not played yet: predict from what the output takes as it is.
            predicted = predictFileDecoded(context, titleAudio, request.setup.iec61937)
            Log.i(
                "Player",
                "The file's audio is ${when (predicted) {
                    true -> "decoded on this device"
                    false -> "passed on as a bitstream"
                    null -> "not known"
                }} (predicted)",
            )
        }
        val playback = GlacierPlayback(
            player, ass, meter,
            current = item.takeIf { handOverable },
            makePatient = { makePatient(dataSource) },
            loadControl = handOverLoadControl,
            titleAudio = titleAudio,
            startsAsFile = startsAsFile,
            decodeSwitch = decodeSwitch,
        )
        playback.maxBufferMs = bufferMs
        if (predicted != null) playback.setFileDecoded(predicted, announce = titleAudio?.decoded == null)
        return playback
    }

    /** Whether the file's audio goes out as PCM on this device, predicted from the sink's support for its format. */
    @OptIn(UnstableApi::class)
    private fun predictFileDecoded(
        context: Context,
        audio: TitleAudio,
        iec61937: Boolean,
    ): Boolean? {
        val mime = AudioPath.mimeOf(audio.codec, audio.profile)
        val passthrough = mime != null && mime in AudioPath.COMPRESSED && runCatching {
            val attributes = androidx.media3.common.AudioAttributes.DEFAULT
            AudioCapabilities.getCapabilities(context, attributes, null)
                .isPassthroughPlaybackSupported(
                    Format.Builder().setSampleMimeType(mime).setChannelCount(6).setSampleRate(48_000).build(),
                    attributes,
                )
        }.getOrDefault(false)
        return AudioPath.predictDecoded(mime, passthrough, iec61937)
    }

    /**
     * The music player: a queue of songs and music videos sharing one set of request [headers]; see
     * [audioItem]. A video's picture goes to the surface the player screen attaches.
     */
    @OptIn(UnstableApi::class)
    fun createAudio(context: Context, headers: Map<String, String>): ExoPlayer {
        val dataSource = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(headers)
            .setAllowCrossProtocolRedirects(true)
        val renderers = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        return ExoPlayer.Builder(context, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */
                true,
            )
            .build()
            .also(Media3Logs::attach)
    }

    /**
     * One song of the queue; [id] comes back as the player's media id. [metadata]
     * is what the system shows for it (media session, "Now playing").
     */
    fun audioItem(
        id: String,
        url: String,
        isHls: Boolean,
        metadata: SongMetadata? = null,
    ): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setUri(url)
        .apply { if (isHls) setMimeType(MimeTypes.APPLICATION_M3U8) }
        .apply {
            if (metadata != null) {
                setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(metadata.title)
                        .setArtist(metadata.artist)
                        .setAlbumTitle(metadata.album)
                        .setArtworkUri(metadata.artworkUrl?.let(Uri::parse))
                        .setMediaType(
                            if (metadata.video) MediaMetadata.MEDIA_TYPE_VIDEO else MediaMetadata.MEDIA_TYPE_MUSIC,
                        )
                        .build(),
                )
            }
        }
        .build()

    /**
     * Media3's renderers with an audio sink that watches bitstreams ([GuardedAudioSink]) and, with
     * [iec61937], packs TrueHD and DTS-HD itself ([Iec61937OutputProvider]).
     */
    @OptIn(UnstableApi::class)
    private class GuardedRenderersFactory(
        context: Context,
        private val iec61937: Boolean,
        private val onStall: () -> Unit,
    ) : DefaultRenderersFactory(context) {
        /** The sink of the player built with this factory, for the switch that decodes compressed sound. */
        var sink: GuardedAudioSink? = null
            private set

        init {
            // Media3 asks for the decoders at every track selection (also for the period of a stream handed over to), so
            // the answer follows the sink's switch: while compressed sound is decoded here, FFmpeg takes it, not a platform
            // decoder (see AudioPath.platformMayDecode); the renderer then reports the format as unsupported.
            setMediaCodecSelector { mimeType, secure, tunneling ->
                val ffmpeg =
                    mimeType in AudioPath.COMPRESSED && FfmpegLibrary.isAvailable() && FfmpegLibrary.supportsFormat(
                        mimeType,
                    )
                if (AudioPath.platformMayDecode(mimeType, sink?.decodeCompressed == true, ffmpeg)) {
                    MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, secure, tunneling)
                } else {
                    emptyList()
                }
            }
        }

        override fun buildAudioSink(
            context: Context,
            enableFloatOutput: Boolean,
            enableAudioOutputPlaybackParams: Boolean,
        ): AudioSink = GuardedAudioSink(
            DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
                .apply { if (iec61937) setAudioOutputProvider(Iec61937OutputProvider.create(context)) }
                .build(),
            onStall,
        ).also { sink = it }
    }

    private fun SideloadedSubtitle.toConfiguration() = MediaItem.SubtitleConfiguration.Builder(Uri.parse(url))
        .setId(TrackControl.externalId(index))
        .setMimeType(
            subtitleMimeType(url.substringBefore('?').substringAfterLast('.', "").ifEmpty { codec.orEmpty() }),
        )
        .setLanguage(language)
        .build()

    /** Media3 needs the format up front for side-loaded files: the URL's extension, else the codec. */
    private fun subtitleMimeType(format: String): String = when (format.lowercase()) {
        "ass", "ssa" -> MimeTypes.TEXT_SSA
        "vtt", "webvtt" -> MimeTypes.TEXT_VTT
        "ttml" -> MimeTypes.APPLICATION_TTML
        else -> MimeTypes.APPLICATION_SUBRIP
    }
}

/**
 * Media3's speed meter, with an estimate the app can set (see [GlacierPlayback.steer]); it measures on as usual, so
 * the info panel still shows what the link did.
 */
@UnstableApi
class SteeredBandwidthMeter(private val real: BandwidthMeter) : BandwidthMeter by real {

    @Volatile
    private var steered = 0L

    /** The estimate that makes Media3 take the tier of [bitrate]: its share of it just fits, the next tier up does not. */
    fun steer(bitrate: Int) {
        steered = (bitrate * 1.05 / GlacierPlayer.STEER_FRACTION).toLong()
    }

    /** Back to Media3's own estimate. */
    fun unsteer() {
        steered = 0
    }

    override fun getBitrateEstimate(): Long = steered.takeIf { it > 0 } ?: real.bitrateEstimate

    /** A transcode answers late for every segment; Media3 would take that off the speed it allows a tier. */
    override fun getTimeToFirstByteEstimateUs(): Long = if (steered > 0) {
        C.TIME_UNSET
    } else {
        real.timeToFirstByteEstimateUs
    }
}
