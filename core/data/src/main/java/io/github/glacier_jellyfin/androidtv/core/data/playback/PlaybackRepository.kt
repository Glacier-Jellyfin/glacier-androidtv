package io.github.glacier_jellyfin.androidtv.core.data.playback

import android.util.Log
import io.github.glacier_jellyfin.androidtv.core.data.Session
import io.github.glacier_jellyfin.androidtv.core.data.SessionManager
import io.github.glacier_jellyfin.androidtv.core.data.media.Track
import io.github.glacier_jellyfin.androidtv.core.data.media.toTrack
import io.github.glacier_jellyfin.androidtv.core.jellyfin.playback.DeviceProfiles
import io.github.glacier_jellyfin.androidtv.core.jellyfin.playback.MediaCapabilityDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.extensions.mediaInfoApi
import org.jellyfin.sdk.api.client.extensions.sessionApi
import org.jellyfin.sdk.api.client.extensions.videoApi
import org.jellyfin.sdk.api.client.util.AuthorizationHeaderBuilder
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.PlaybackInfoDto
import org.jellyfin.sdk.model.api.PlaybackOrder
import org.jellyfin.sdk.model.api.PlaybackProgressInfo
import org.jellyfin.sdk.model.api.PlaybackStartInfo
import org.jellyfin.sdk.model.api.PlaybackStopInfo
import org.jellyfin.sdk.model.api.RepeatMode
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** How the server delivers the stream; shown as a badge in the player. */
enum class PlaybackMethod { DirectPlay, DirectStream, Transcode }

/** How a subtitle reaches the screen. */
enum class SubtitleDelivery {
    /** Inside the played file; Media3 renders it. */
    Embedded,

    /** A separate file from the server, loaded next to the stream. */
    External,

    /** Burned into the picture by the server; needs a new stream. */
    BurnIn,
}

data class PlaybackSubtitle(
    val track: Track,
    val delivery: SubtitleDelivery,
    /** Full URL of the file for [SubtitleDelivery.External]. */
    val url: String?,
)

/** Everything the player needs to open a stream and report back on it. */
data class PlaybackSource(
    val itemId: UUID,
    val mediaSourceId: String?,
    val playSessionId: String?,
    val url: String,
    val isHls: Boolean,
    val method: PlaybackMethod,
    /** Request headers for the stream (authorisation). */
    val headers: Map<String, String>,
    val audioIndex: Int?,
    val subtitleIndex: Int?,
    val audioTracks: List<Track>,
    val subtitles: List<PlaybackSubtitle>,
) {
    /** Direct play keeps every audio track in the file; a transcode carries only the chosen one. */
    val allAudioInStream: Boolean get() = method == PlaybackMethod.DirectPlay

    /** Where [index] sits among the audio tracks inside the file, which is how Media3 lists them. */
    fun audioOrdinal(index: Int): Int? = embeddedOrdinal(index, audioTracks.map { it.index })

    fun subtitleOrdinal(index: Int): Int? =
        embeddedOrdinal(index, subtitles.filter { it.delivery == SubtitleDelivery.Embedded }.map { it.track.index })
}

/** Position of [index] in the sorted [indices], or null when it is not among them. */
internal fun embeddedOrdinal(index: Int, indices: List<Int>): Int? = indices.sorted().indexOf(index).takeIf { it >= 0 }

/** Where the player stands, for the server's "now playing" and resume point. */
data class PlaybackPosition(val positionMs: Long, val paused: Boolean)

@Singleton
class PlaybackRepository @Inject constructor(
    private val sessions: SessionManager,
    private val capabilities: MediaCapabilityDetector,
) {

    /** Reports must outlive the player screen: "stopped" is sent while it closes. */
    private val reportScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Asks the server how to play [itemId] on this device and builds the stream URL. */
    suspend fun open(itemId: UUID, startMs: Long, audioIndex: Int?, subtitleIndex: Int?): PlaybackSource = withContext(Dispatchers.IO) {
        val session = requireSession()
        val api = session.api
        val info = api.mediaInfoApi.getPostedPlaybackInfo(
            itemId = itemId,
            data = PlaybackInfoDto(
                userId = session.userId,
                maxStreamingBitrate = MAX_BITRATE,
                startTimeTicks = startMs * TICKS_PER_MS,
                audioStreamIndex = audioIndex,
                subtitleStreamIndex = subtitleIndex,
                deviceProfile = DeviceProfiles.build(capabilities.capabilities, MAX_BITRATE),
                enableDirectPlay = true,
                enableDirectStream = true,
                enableTranscoding = true,
                allowVideoStreamCopy = true,
                allowAudioStreamCopy = true,
                autoOpenLiveStream = true,
            ),
        ).content
        info.errorCode?.let { error("Server refused playback: $it") }
        val source = info.mediaSources.firstOrNull() ?: error("Server returned no media source")

        val baseUrl = checkNotNull(api.baseUrl).trimEnd('/')
        val streams = source.mediaStreams.orEmpty()
        val (url, method) = when {
            source.supportsDirectPlay -> api.videoApi.getVideoStreamUrl(
                itemId = itemId,
                static = true,
                mediaSourceId = source.id,
                playSessionId = info.playSessionId,
            ) to PlaybackMethod.DirectPlay
            source.transcodingUrl != null -> (baseUrl + source.transcodingUrl) to
                if (source.supportsDirectStream) PlaybackMethod.DirectStream else PlaybackMethod.Transcode
            else -> error("Server offered neither direct play nor a transcode")
        }
        PlaybackSource(
            itemId = itemId,
            mediaSourceId = source.id,
            playSessionId = info.playSessionId,
            url = url,
            isHls = method != PlaybackMethod.DirectPlay && source.transcodingSubProtocol == MediaStreamProtocol.HLS,
            method = method,
            headers = mapOf("Authorization" to authorization(session)),
            audioIndex = audioIndex ?: source.defaultAudioStreamIndex,
            // The server's default follows the user's subtitle mode; -1 means off.
            subtitleIndex = (subtitleIndex ?: source.defaultSubtitleStreamIndex)?.takeIf { it >= 0 },
            audioTracks = streams.filter { it.type == MediaStreamType.AUDIO && !it.isExternal }.map { it.toTrack() },
            subtitles = streams.filter { it.type == MediaStreamType.SUBTITLE }.mapNotNull { stream ->
                val delivery = when (stream.deliveryMethod) {
                    SubtitleDeliveryMethod.EMBED -> SubtitleDelivery.Embedded
                    SubtitleDeliveryMethod.EXTERNAL, SubtitleDeliveryMethod.HLS -> SubtitleDelivery.External
                    SubtitleDeliveryMethod.ENCODE -> SubtitleDelivery.BurnIn
                    // Drop and unknown methods cannot be shown.
                    else -> return@mapNotNull null
                }
                val url = stream.deliveryUrl?.let { if (it.startsWith("http")) it else baseUrl + it }
                if (delivery == SubtitleDelivery.External && url == null) null else PlaybackSubtitle(stream.toTrack(), delivery, url)
            },
        )
    }

    fun reportStart(source: PlaybackSource, position: PlaybackPosition) = report("start") {
        sessionApi.reportPlaybackStart(
            PlaybackStartInfo(
                canSeek = true,
                itemId = source.itemId,
                mediaSourceId = source.mediaSourceId,
                playSessionId = source.playSessionId,
                audioStreamIndex = source.audioIndex,
                subtitleStreamIndex = source.subtitleIndex,
                isPaused = position.paused,
                isMuted = false,
                positionTicks = position.positionMs * TICKS_PER_MS,
                playMethod = source.method.toApi(),
                repeatMode = RepeatMode.REPEAT_NONE,
                playbackOrder = PlaybackOrder.DEFAULT,
            ),
        )
    }

    fun reportProgress(source: PlaybackSource, position: PlaybackPosition) = report("progress") {
        sessionApi.reportPlaybackProgress(
            PlaybackProgressInfo(
                canSeek = true,
                itemId = source.itemId,
                mediaSourceId = source.mediaSourceId,
                playSessionId = source.playSessionId,
                audioStreamIndex = source.audioIndex,
                subtitleStreamIndex = source.subtitleIndex,
                isPaused = position.paused,
                isMuted = false,
                positionTicks = position.positionMs * TICKS_PER_MS,
                playMethod = source.method.toApi(),
                repeatMode = RepeatMode.REPEAT_NONE,
                playbackOrder = PlaybackOrder.DEFAULT,
            ),
        )
    }

    /** The server stores the resume point from this, and marks the title watched near its end. */
    fun reportStopped(source: PlaybackSource, positionMs: Long, failed: Boolean = false) = report("stopped") {
        sessionApi.reportPlaybackStopped(
            PlaybackStopInfo(
                itemId = source.itemId,
                mediaSourceId = source.mediaSourceId,
                playSessionId = source.playSessionId,
                positionTicks = positionMs * TICKS_PER_MS,
                failed = failed,
            ),
        )
    }

    private fun report(what: String, call: suspend org.jellyfin.sdk.api.client.ApiClient.() -> Unit) {
        val api = sessions.session.value?.api ?: return
        reportScope.launch {
            // Reporting is best effort: playback goes on if the server misses one.
            runCatching { api.call() }.onFailure { Log.w(TAG, "Playback report '$what' failed", it) }
        }
    }

    private fun authorization(session: Session): String {
        val api = session.api
        return AuthorizationHeaderBuilder.buildHeader(
            clientName = api.clientInfo.name,
            clientVersion = api.clientInfo.version,
            deviceId = api.deviceInfo.id,
            deviceName = api.deviceInfo.name,
            accessToken = api.accessToken,
        )
    }

    private fun PlaybackMethod.toApi() = when (this) {
        PlaybackMethod.DirectPlay -> PlayMethod.DIRECT_PLAY
        PlaybackMethod.DirectStream -> PlayMethod.DIRECT_STREAM
        PlaybackMethod.Transcode -> PlayMethod.TRANSCODE
    }

    private fun requireSession(): Session = checkNotNull(sessions.session.value) { "No profile is signed in" }

    private val Session.userId: UUID get() = UUID.fromString(user.userId)

    private companion object {
        const val TAG = "Playback"
        const val TICKS_PER_MS = 10_000L

        /** "Auto" until the settings screen exists: the highest step the design offers (120 Mbit/s). */
        const val MAX_BITRATE = 120_000_000
    }
}
