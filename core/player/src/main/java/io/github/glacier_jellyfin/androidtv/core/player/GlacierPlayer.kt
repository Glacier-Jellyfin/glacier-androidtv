package io.github.glacier_jellyfin.androidtv.core.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.ui.SubtitleView
import io.github.glacier_jellyfin.androidtv.core.log.Log
import io.github.peerless2012.ass.media.AssHandler
import io.github.peerless2012.ass.media.factory.AssRenderersFactory
import io.github.peerless2012.ass.media.kt.withAssMkvSupport
import io.github.peerless2012.ass.media.parser.AssSubtitleParserFactory
import io.github.peerless2012.ass.media.type.AssRenderType
import io.github.peerless2012.ass.media.widget.AssSubtitleView

/** A song as the system shows it. */
data class SongMetadata(val title: String, val artist: String?, val album: String?, val artworkUrl: String?)

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
    /** Dolby Vision profile 7 plays its HDR10 base layer: the device has no decoder for the profile. */
    val dolbyVisionBaseLayer: Boolean = false,
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
class GlacierPlayback(val player: ExoPlayer, private val ass: AssHandler) {

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

    private companion object {
        const val TAG = "Player"

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

    @OptIn(UnstableApi::class)
    fun create(context: Context, request: StreamRequest): GlacierPlayback {
        val dataSource = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(request.headers)
            .setAllowCrossProtocolRedirects(true)
        // Rendered on its own GL thread above the video, with animation and embedded fonts.
        val ass = AssHandler(AssRenderType.OVERLAY_OPEN_GL)
        val assParsers = AssSubtitleParserFactory(ass)
        val extractors = DefaultExtractorsFactory().withAssMkvSupport(assParsers, ass)
        val mediaSources = DefaultMediaSourceFactory(dataSource, if (request.dolbyVisionBaseLayer) DolbyVisionBaseLayerExtractors(extractors) else extractors)
            // Side-loaded .ass files go through the same parser.
            .setSubtitleParserFactory(assParsers)
        val renderers = DefaultRenderersFactory(context)
            // Platform decoders first; a software fallback only when a hardware decoder fails.
            .setEnableDecoderFallback(true)
            // FFmpeg (core/ffmpeg) for audio formats no platform decoder or passthrough handles, e.g. DTS.
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        val player = ExoPlayer.Builder(context, AssRenderersFactory(ass, renderers))
            .setMediaSourceFactory(mediaSources)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .build()
        Media3Logs.attach(player)
        val item = MediaItem.Builder()
            .setUri(request.url)
            .apply { if (request.isHls) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .setSubtitleConfigurations(request.subtitles.map { it.toConfiguration() })
            .build()
        player.apply {
            if (request.live) setMediaItem(item) else setMediaItem(item, request.startPositionMs)
            prepare()
        }
        ass.init(player)
        return GlacierPlayback(player, ass)
    }

    /** The music player: a queue of songs sharing one set of request [headers]; see [audioItem]. */
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
                            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                            .build(),
                    )
                }
            }
            .build()

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
