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

/** Creates the ExoPlayer used for video, set up for TV playback. */
object GlacierPlayer {

    @OptIn(UnstableApi::class)
    fun create(context: Context, request: StreamRequest): ExoPlayer {
        val dataSource = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(request.headers)
            .setAllowCrossProtocolRedirects(true)
        val renderers = DefaultRenderersFactory(context)
            // Platform decoders first; a software fallback only when a hardware decoder fails.
            .setEnableDecoderFallback(true)
        return ExoPlayer.Builder(context, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
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
            .apply {
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

    /** Remote Left/Right and the OSD skip buttons (agreed default; later a setting). */
    const val SEEK_BACK_MS = 10_000L
    const val SEEK_FORWARD_MS = 30_000L
}
