package io.github.glacier_jellyfin.androidtv.core.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.ui.SubtitleView
import io.github.peerless2012.ass.media.AssHandler
import io.github.peerless2012.ass.media.factory.AssRenderersFactory
import io.github.peerless2012.ass.media.kt.withAssMkvSupport
import io.github.peerless2012.ass.media.parser.AssSubtitleParserFactory
import io.github.peerless2012.ass.media.type.AssRenderType
import io.github.peerless2012.ass.media.widget.AssSubtitleView

/** One stream to play: the URL the server handed out and the headers it needs. */
data class StreamRequest(
    val url: String,
    val isHls: Boolean,
    val headers: Map<String, String>,
    val startPositionMs: Long,
    /** Subtitle files loaded next to the stream. */
    val subtitles: List<SideloadedSubtitle> = emptyList(),
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

    @OptIn(UnstableApi::class)
    fun attachAss(subtitleView: SubtitleView) {
        subtitleView.addView(AssSubtitleView(subtitleView.context, ass))
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
        val mediaSources = DefaultMediaSourceFactory(dataSource, DefaultExtractorsFactory().withAssMkvSupport(assParsers, ass))
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
            .setSeekBackIncrementMs(SEEK_BACK_MS)
            .setSeekForwardIncrementMs(SEEK_FORWARD_MS)
            .build()
        player.apply {
                setMediaItem(
                    MediaItem.Builder()
                        .setUri(request.url)
                        .apply { if (request.isHls) setMimeType(MimeTypes.APPLICATION_M3U8) }
                        .setSubtitleConfigurations(request.subtitles.map { it.toConfiguration() })
                        .build(),
                    request.startPositionMs,
                )
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
    }

    /** One song of the queue; [id] comes back as the player's media id. */
    fun audioItem(id: String, url: String, isHls: Boolean): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setUri(url)
            .apply { if (isHls) setMimeType(MimeTypes.APPLICATION_M3U8) }
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

    /** Remote Left/Right and the OSD skip buttons (agreed default; later a setting). */
    const val SEEK_BACK_MS = 10_000L
    const val SEEK_FORWARD_MS = 30_000L
}
