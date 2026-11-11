package io.github.glacier_jellyfin.androidtv.core.player

import android.content.Context
import android.net.Uri
import android.os.Handler
import androidx.annotation.OptIn
import android.util.Base64
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.BandwidthMeter
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
data class SongMetadata(val title: String, val artist: String?, val album: String?, val artworkUrl: String?, val video: Boolean = false)

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
 * A player and its ASS renderer. libass draws ASS/SSA subtitles itself, in a
 * view that [attachAss] puts into the SubtitleView; every other format still
 * arrives as Media3 cues.
 */
@OptIn(UnstableApi::class)
class GlacierPlayback(val player: ExoPlayer, private val ass: AssHandler, private val meter: SteeredBandwidthMeter) {

    /**
     * Makes the player choose the tier of [bitrate] (bit/s) of a ladder from the next segment on. Media3 switches
     * between tiers by its speed estimate, which for a transcode is low, since the server takes its time for every
     * segment, so the app gives it the estimate that suits the tier it wants. The buffer already loaded plays on.
     */
    fun steer(bitrate: Int) = meter.steer(bitrate)

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

    @OptIn(UnstableApi::class)
    private fun addDecoderListener() {
        player.addAnalyticsListener(object : AnalyticsListener {
            // Logged on every playback: a bug report shows the decoders and the sound output without detailed logging.
            override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                videoDecoder = decoderName
                Log.i(TAG, "Video decoder: $decoderName")
            }

            override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                audioDecoder = decoderName
                Log.i(TAG, "Audio decoder: $decoderName")
            }

            override fun onAudioTrackInitialized(eventTime: AnalyticsListener.EventTime, audioTrackConfig: AudioSink.AudioTrackConfig) {
                audioEncoding = audioTrackConfig.encoding
                audioChannelMask = audioTrackConfig.channelConfig
                Log.i(TAG, "Audio output: ${encodingName(audioTrackConfig.encoding)}, ${Integer.bitCount(audioTrackConfig.channelConfig)} channels, ${audioTrackConfig.sampleRate} Hz")
            }
        })
    }

    @OptIn(UnstableApi::class)
    fun attachAss(subtitleView: SubtitleView) {
        subtitleView.addView(AssSubtitleView(subtitleView.context, ass))
    }

    internal companion object {
        private const val TAG = "Player"

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

    /** A playlist the app wrote, as an address Media3 reads without a request. */
    private fun playlistUri(playlist: String): Uri =
        Uri.parse("data:application/x-mpegURL;base64," + Base64.encodeToString(playlist.toByteArray(), Base64.NO_WRAP))

    @OptIn(UnstableApi::class)
    fun create(context: Context, request: StreamRequest): GlacierPlayback {
        val dataSource = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(request.headers)
            .setAllowCrossProtocolRedirects(true)
            .apply {
                // The server answers a segment of a transcode when it has made it, and a slow transcode takes far longer
                // than the 8 s Media3 waits by default: that ended the playback with an error.
                if (request.patient) {
                    setConnectTimeoutMs(HLS_CONNECT_TIMEOUT_MS)
                    setReadTimeoutMs(HLS_READ_TIMEOUT_MS)
                }
            }
        // Rendered on its own GL thread above the video, with animation and embedded fonts.
        val ass = AssHandler(AssRenderType.OVERLAY_OPEN_GL)
        val assParsers = AssSubtitleParserFactory(ass)
        // libass's Matroska extractor, which also hands on Dolby Vision block additions.
        val extractors = DefaultExtractorsFactory().withDolbyVisionMkvSupport(assParsers, ass)
        // HTTP for the server's streams; asset:// for the playback check's samples.
        val sources = ThrottledDataSource.Factory(DefaultDataSource.Factory(context, dataSource))
        val profile7 = request.setup.dolbyVisionProfile7
        Log.i("Player", "Setup: Dolby Vision profile 7 $profile7, IEC 61937 ${if (request.setup.iec61937) "on" else "off"}")
        val mediaSources = DefaultMediaSourceFactory(
            sources,
            if (profile7 == DolbyVisionProfile7.AsIs) extractors else DolbyVisionProfile7Extractors(extractors, profile7),
        )
            // Side-loaded .ass files go through the same parser.
            .setSubtitleParserFactory(assParsers)
        // A silent bitstream track: a seek to where the player stands flushes the sink, which opens a new one.
        var built: ExoPlayer? = null
        val renderers = GuardedRenderersFactory(context, request.setup.iec61937) {
            built?.let { player -> Handler(player.applicationLooper).post { player.seekTo(player.currentPosition) } }
        }
            // Platform decoders first; a software fallback only when a hardware decoder fails.
            .setEnableDecoderFallback(true)
            // FFmpeg (core/ffmpeg) for audio formats no platform decoder or passthrough handles, e.g. DTS.
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        // After a stall it takes more buffer to start again (default 5 s), so a link that just kept up
        // does not run dry again at once.
        // Live has no stock to buffer, previews must start fast: only a title gets the big buffer, which carries a tier
        // switch (a new transcode takes 16 to 29 s) and the high bitrates of direct play.
        val bigBuffer = request.title && !request.live
        val bufferMs = if (bigBuffer) VIDEO_BUFFER_MS else DefaultLoadControl.DEFAULT_MAX_BUFFER_MS
        val maxMemory = Runtime.getRuntime().maxMemory()
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
            val targetBytes = BufferSizing.targetBytes(
                maxMemory,
                DefaultLoadControl.DEFAULT_VIDEO_BUFFER_SIZE + DefaultLoadControl.DEFAULT_AUDIO_BUFFER_SIZE,
            )
            loadControlBuilder.setTargetBufferBytes(targetBytes).setPrioritizeTimeOverSizeThresholds(false)
            Log.i("Player", "Buffer: ${bufferMs / 1000} s, ${targetBytes / MB} MB target, ${maxMemory / MB} MB heap")
        } else {
            Log.i("Player", "Buffer: ${bufferMs / 1000} s, default byte target, ${maxMemory / MB} MB heap")
        }
        val loadControl = loadControlBuilder.build()
        // Tiers of one transcode: quality comes first, so it moves up as soon as the link allows and down only when
        // the buffer runs low, and uses more of the measured speed than Media3's default 70 %.
        val trackSelector = DefaultTrackSelector(context, AdaptiveTrackSelection.Factory(5_000, DECREASE_ANYTIME_MS, 25_000, STEER_FRACTION))
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
            .setMediaSourceFactory(mediaSources)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true,
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
                if (DebugOptions.segmentLog) Log.w("Segment", "load failed after ${loadEventInfo.loadDurationMs} ms: ${error.javaClass.simpleName}")
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

            override fun onVideoInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?) {
                if (request.playlist != null) Log.i("Player", "Video tier ${format.bitrate / 1000} kbit/s ${format.width}x${format.height}")
            }
        })
        val item = MediaItem.Builder()
            .setUri(request.playlist?.let(::playlistUri) ?: Uri.parse(request.url))
            .apply { if (request.isHls) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .setSubtitleConfigurations(request.subtitles.map { it.toConfiguration() })
            .build()
        player.apply {
            if (request.live) setMediaItem(item) else setMediaItem(item, request.startPositionMs)
            prepare()
        }
        ass.init(player)
        return GlacierPlayback(player, ass, meter).also { it.maxBufferMs = bufferMs }
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
                /* handleAudioFocus = */ true,
            )
            .build()
            .also(Media3Logs::attach)
    }

    /**
     * One song of the queue; [id] comes back as the player's media id. [metadata]
     * is what the system shows for it (media session, "Now playing").
     */
    fun audioItem(id: String, url: String, isHls: Boolean, metadata: SongMetadata? = null): MediaItem =
        MediaItem.Builder()
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
                            .setMediaType(if (metadata.video) MediaMetadata.MEDIA_TYPE_VIDEO else MediaMetadata.MEDIA_TYPE_MUSIC)
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
        override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParams: Boolean): AudioSink =
            GuardedAudioSink(
                DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
                    .apply { if (iec61937) setAudioOutputProvider(Iec61937OutputProvider.create(context)) }
                    .build(),
                onStall,
            )
    }

    private fun SideloadedSubtitle.toConfiguration() =
        MediaItem.SubtitleConfiguration.Builder(Uri.parse(url))
            .setId(TrackControl.externalId(index))
            .setMimeType(subtitleMimeType(url.substringBefore('?').substringAfterLast('.', "").ifEmpty { codec.orEmpty() }))
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

    override fun getBitrateEstimate(): Long = steered.takeIf { it > 0 } ?: real.bitrateEstimate

    /** A transcode answers late for every segment; Media3 would take that off the speed it allows a tier. */
    override fun getTimeToFirstByteEstimateUs(): Long = if (steered > 0) C.TIME_UNSET else real.timeToFirstByteEstimateUs
}
