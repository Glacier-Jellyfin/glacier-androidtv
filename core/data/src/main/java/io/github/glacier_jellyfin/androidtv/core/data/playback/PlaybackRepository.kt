package io.github.glacier_jellyfin.androidtv.core.data.playback

import android.util.Log
import io.github.glacier_jellyfin.androidtv.core.data.Session
import io.github.glacier_jellyfin.androidtv.core.data.SessionManager
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
)

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
            subtitleIndex = subtitleIndex,
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
