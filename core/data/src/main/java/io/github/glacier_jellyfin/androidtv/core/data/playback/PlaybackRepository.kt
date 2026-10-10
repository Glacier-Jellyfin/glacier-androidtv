package io.github.glacier_jellyfin.androidtv.core.data.playback

import io.github.glacier_jellyfin.androidtv.core.log.Log
import io.github.glacier_jellyfin.androidtv.core.data.Session
import io.github.glacier_jellyfin.androidtv.core.data.SessionManager
import io.github.glacier_jellyfin.androidtv.core.data.media.MusicTrack
import io.github.glacier_jellyfin.androidtv.core.data.media.Track
import io.github.glacier_jellyfin.androidtv.core.data.media.toTrack
import io.github.glacier_jellyfin.androidtv.core.data.settings.SettingsRepository
import io.github.glacier_jellyfin.androidtv.core.jellyfin.playback.DeviceProfiles
import io.github.glacier_jellyfin.androidtv.core.jellyfin.playback.HlsSegments
import io.github.glacier_jellyfin.androidtv.core.jellyfin.playback.MediaCapabilityDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.extensions.audioApi
import org.jellyfin.sdk.api.client.extensions.mediaInfoApi
import org.jellyfin.sdk.api.client.extensions.mediaSegmentApi
import org.jellyfin.sdk.api.client.extensions.sessionApi
import org.jellyfin.sdk.api.client.extensions.userDataApi
import org.jellyfin.sdk.api.client.extensions.videoApi
import org.jellyfin.sdk.api.client.util.AuthorizationHeaderBuilder
import org.jellyfin.sdk.model.api.HardwareAccelerationType
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import org.jellyfin.sdk.model.api.MediaSegmentType
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
    /** The file as the server stores it; null for music. */
    val file: SourceFile? = null,
    /** A transcode in fragmented MP4 segments, which may need a second try as MPEG-TS. */
    val fmp4Segments: Boolean = false,
    /** The tuner stream the server opened for a Live TV channel; reporting the stop closes it. */
    val liveStreamId: String? = null,
) {
    /** A Live TV channel: no end, no resume point. */
    val live: Boolean get() = liveStreamId != null

    /** Direct play keeps every audio track in the file; a transcode carries only the chosen one. */
    val allAudioInStream: Boolean get() = method == PlaybackMethod.DirectPlay

    /** Where [index] sits among the audio tracks inside the file, which is how Media3 lists them. */
    fun audioOrdinal(index: Int): Int? = embeddedOrdinal(index, audioTracks.map { it.index })

    fun subtitleOrdinal(index: Int): Int? =
        embeddedOrdinal(index, subtitles.filter { it.delivery == SubtitleDelivery.Embedded }.map { it.track.index })
}

/** The file on the server, for the player's info sheet. */
data class SourceFile(
    val container: String?,
    val sizeBytes: Long?,
    val bitrate: Int?,
    /** What the server sends instead of the file ("HLS · MP4"); null for direct play. */
    val streamContainer: String?,
    val video: SourceVideo?,
    val audio: List<SourceAudio>,
    /** Why the server does not send the file as it is, e.g. "AudioCodecNotSupported". */
    val transcodeReasons: List<String>,
)

data class SourceVideo(
    /** The server's codec name: "hevc", "h264", "av1". */
    val codec: String?,
    val profile: String?,
    val width: Int?,
    val height: Int?,
    val bitDepth: Int?,
    val frameRate: Float?,
    /** "SDR", "HDR10", "DOVIWithHDR10", "HLG", … */
    val rangeType: String?,
    val dolbyVisionProfile: Int?,
)

data class SourceAudio(
    val index: Int,
    /** The server's codec name: "dts", "truehd", "eac3". */
    val codec: String?,
    /** "DTS-HD MA", "LC", … */
    val profile: String?,
    val channels: Int?,
    val sampleRate: Int?,
    val bitrate: Int?,
)

/** What the server's transcoder is doing for this device right now. */
data class TranscodeStatus(
    /** Frames per second the transcoder produces. */
    val framerate: Float?,
    /** How much of the title is transcoded, 0–100. */
    val completion: Double?,
    /** "Intel QSV", "NVIDIA NVENC"; null for software. */
    val hardware: String?,
    val videoDirect: Boolean,
    val audioDirect: Boolean,
    val reasons: List<String>,
)

/** Position of [index] in the sorted [indices], or null when it is not among them. */
internal fun embeddedOrdinal(index: Int, indices: List<Int>): Int? = indices.sorted().indexOf(index).takeIf { it >= 0 }

/** Where the player stands, for the server's "now playing" and resume point. */
data class PlaybackPosition(val positionMs: Long, val paused: Boolean)

@Singleton
class PlaybackRepository @Inject constructor(
    private val sessions: SessionManager,
    private val capabilities: MediaCapabilityDetector,
    private val settings: SettingsRepository,
) {

    /** Reports must outlive the player screen: "stopped" is sent while it closes. */
    private val reportScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _stopped = MutableSharedFlow<UUID>(extraBufferCapacity = 8)

    /** A title whose "stopped" report reached the server: resume points and watched state changed. */
    val stopped: SharedFlow<UUID> = _stopped.asSharedFlow()

    /**
     * Asks the server how to play [itemId] on this device and builds the stream URL.
     * [segments] null: the container that suits the device, see [HlsSegments.preferredFor].
     */
    suspend fun open(itemId: UUID, startMs: Long, audioIndex: Int?, subtitleIndex: Int?, segments: HlsSegments? = null): PlaybackSource = withContext(Dispatchers.IO) {
        val session = requireSession()
        val api = session.api
        val playback = settings.current().playback
        val device = capabilities.capabilities.let { detected ->
            playback.audioChannels.max?.let { detected.copy(maxAudioChannels = minOf(detected.maxAudioChannels, it)) } ?: detected
        }
        suspend fun playbackInfo(bitrate: Int) = api.mediaInfoApi.getPostedPlaybackInfo(
            itemId = itemId,
            data = PlaybackInfoDto(
                userId = session.userId,
                maxStreamingBitrate = bitrate,
                maxAudioChannels = device.maxAudioChannels,
                startTimeTicks = startMs * TICKS_PER_MS,
                audioStreamIndex = audioIndex,
                subtitleStreamIndex = subtitleIndex,
                deviceProfile = DeviceProfiles.build(device, bitrate, playback.subtitleBurnIn, segments ?: HlsSegments.preferredFor(device)),
                enableDirectPlay = true,
                enableDirectStream = true,
                enableTranscoding = true,
                allowVideoStreamCopy = true,
                allowAudioStreamCopy = true,
                autoOpenLiveStream = true,
            ),
        ).content

        // The bitrate setting only caps transcodes (design): ask without it first, so a
        // file above the cap still plays directly, and again with it when the server transcodes.
        var info = playbackInfo(AUTO_BITRATE)
        val cap = playback.maxBitrate.bitsPerSecond
        if (cap != null && info.errorCode == null && info.mediaSources.firstOrNull()?.supportsDirectPlay == false) {
            info = playbackInfo(cap)
        }
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
                liveStreamId = source.liveStreamId,
            ) to PlaybackMethod.DirectPlay
            source.transcodingUrl != null -> (baseUrl + source.transcodingUrl) to
                if (source.supportsDirectStream) PlaybackMethod.DirectStream else PlaybackMethod.Transcode
            else -> error("Server offered neither direct play nor a transcode")
        }
        val reasons = source.transcodingUrl?.takeIf { method != PlaybackMethod.DirectPlay }?.let { TRANSCODE_REASONS.find(it)?.groupValues?.get(1) }
        Log.i(TAG, "Playing $itemId: $method, container ${source.container}, ${source.bitrate} bit/s" + reasons?.let { ", because $it" }.orEmpty())
        if (Log.verbose) Log.d(TAG, "Streams of $itemId: " + streams.joinToString { it.describe() })
        PlaybackSource(
            itemId = itemId,
            mediaSourceId = source.id,
            playSessionId = info.playSessionId,
            url = url,
            isHls = method != PlaybackMethod.DirectPlay && source.transcodingSubProtocol == MediaStreamProtocol.HLS,
            fmp4Segments = method != PlaybackMethod.DirectPlay && source.transcodingSubProtocol == MediaStreamProtocol.HLS &&
                source.transcodingContainer.equals("mp4", ignoreCase = true),
            liveStreamId = source.liveStreamId,
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
            file = SourceFile(
                container = source.container,
                sizeBytes = source.size,
                bitrate = source.bitrate,
                streamContainer = source.transcodingContainer?.uppercase()?.takeIf { method != PlaybackMethod.DirectPlay }?.let {
                    if (source.transcodingSubProtocol == MediaStreamProtocol.HLS) "HLS · $it" else it
                },
                video = streams.firstOrNull { it.type == MediaStreamType.VIDEO }?.let {
                    SourceVideo(
                        codec = it.codec,
                        profile = it.profile,
                        width = it.width,
                        height = it.height,
                        bitDepth = it.bitDepth,
                        frameRate = it.realFrameRate ?: it.averageFrameRate,
                        rangeType = it.videoRangeType?.serialName,
                        dolbyVisionProfile = it.dvProfile,
                    )
                },
                audio = streams.filter { it.type == MediaStreamType.AUDIO && !it.isExternal }.map {
                    SourceAudio(it.index, it.codec, it.profile, it.channels, it.sampleRate, it.bitRate)
                },
                transcodeReasons = reasons?.split(',')?.filter { it.isNotBlank() }.orEmpty(),
            ),
        )
    }

    /**
     * The transcoder's state for [source], from this device's session on the server; null
     * for direct play, when the server does not say, or when this account may not see sessions.
     */
    suspend fun transcodeStatus(source: PlaybackSource): TranscodeStatus? = withContext(Dispatchers.IO) {
        val api = sessions.session.value?.api ?: return@withContext null
        val info = runCatching { api.sessionApi.getSessions(deviceId = api.deviceInfo.id).content }
            .onFailure { Log.w(TAG, "Loading the transcode status failed", it) }
            .getOrNull()
            ?.firstOrNull { it.nowPlayingItem?.id == source.itemId }
            ?.transcodingInfo
            ?: return@withContext null
        TranscodeStatus(
            framerate = info.framerate?.takeIf { it > 0 },
            completion = info.completionPercentage,
            hardware = when (info.hardwareAccelerationType) {
                HardwareAccelerationType.AMF -> "AMD AMF"
                HardwareAccelerationType.QSV -> "Intel QSV"
                HardwareAccelerationType.NVENC -> "NVIDIA NVENC"
                HardwareAccelerationType.V_4L_2M_2M -> "V4L2"
                HardwareAccelerationType.VAAPI -> "VA-API"
                HardwareAccelerationType.VIDEOTOOLBOX -> "VideoToolbox"
                HardwareAccelerationType.RKMPP -> "Rockchip MPP"
                HardwareAccelerationType.NONE, null -> null
            },
            videoDirect = info.isVideoDirect,
            audioDirect = info.isAudioDirect,
            reasons = info.transcodeReasons.orEmpty().map { it.serialName },
        )
    }

    /**
     * A song for the music queue, built without asking the server so the whole
     * queue can go to the player at once: the file itself when this device
     * decodes its codec, else the server's AAC transcode over HLS.
     */
    fun audioSource(track: MusicTrack): PlaybackSource {
        val session = requireSession()
        val api = session.api
        val direct = track.format?.codec?.lowercase()?.let { it in capabilities.capabilities.audioCodecs } == true
        val url = if (direct) {
            api.audioApi.getAudioStreamUrl(itemId = track.id, static = true)
        } else {
            api.audioApi.getUniversalAudioStreamUrl(
                itemId = track.id,
                // Only what the direct path above already rules out, so the server never sends the file itself.
                container = listOf("mp4|aac"),
                userId = session.userId,
                deviceId = api.deviceInfo.id,
                maxStreamingBitrate = settings.settings.value.playback.maxBitrate.bitsPerSecond ?: AUTO_BITRATE,
                audioCodec = "aac",
                transcodingContainer = "mp4",
                transcodingProtocol = MediaStreamProtocol.HLS,
            )
        }
        return PlaybackSource(
            itemId = track.id,
            mediaSourceId = track.id.toString().replace("-", ""),
            playSessionId = null,
            url = url,
            isHls = !direct,
            method = if (direct) PlaybackMethod.DirectPlay else PlaybackMethod.Transcode,
            headers = mapOf("Authorization" to authorization(session)),
            audioIndex = null,
            subtitleIndex = null,
            audioTracks = emptyList(),
            subtitles = emptyList(),
        )
    }

    /** Intro, credits and the like; empty when the server has none or cannot tell (no segment provider). */
    suspend fun segments(itemId: UUID): List<MediaSegment> = withContext(Dispatchers.IO) {
        val api = requireSession().api
        runCatching { api.mediaSegmentApi.getItemSegments(itemId).content.items }
            .onFailure { Log.w(TAG, "Loading media segments failed", it) }
            .getOrDefault(emptyList())
            .mapNotNull { dto ->
                val kind = when (dto.type) {
                    MediaSegmentType.INTRO -> SegmentKind.Intro
                    MediaSegmentType.RECAP -> SegmentKind.Recap
                    MediaSegmentType.PREVIEW -> SegmentKind.Preview
                    MediaSegmentType.COMMERCIAL -> SegmentKind.Commercial
                    MediaSegmentType.OUTRO -> SegmentKind.Outro
                    else -> return@mapNotNull null
                }
                MediaSegment(kind, dto.startTicks / TICKS_PER_MS, dto.endTicks / TICKS_PER_MS).takeIf { it.endMs > it.startMs }
            }
            .let(::mergeOverlapping)
    }

    fun reportStart(source: PlaybackSource, position: PlaybackPosition) = report("start") {
        sessionApi.reportPlaybackStart(
            PlaybackStartInfo(
                canSeek = !source.live,
                itemId = source.itemId,
                mediaSourceId = source.mediaSourceId,
                playSessionId = source.playSessionId,
                liveStreamId = source.liveStreamId,
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
                canSeek = !source.live,
                itemId = source.itemId,
                mediaSourceId = source.mediaSourceId,
                playSessionId = source.playSessionId,
                liveStreamId = source.liveStreamId,
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

    /**
     * The server stores the resume point from this, and marks the title watched near its end.
     * [watched] marks it watched in any case, after the stop so the stop cannot undo it.
     */
    fun reportStopped(source: PlaybackSource, positionMs: Long, failed: Boolean = false, watched: Boolean = false) =
        report("stopped", onDone = { _stopped.tryEmit(source.itemId) }) {
            sessionApi.reportPlaybackStopped(
                PlaybackStopInfo(
                    itemId = source.itemId,
                    mediaSourceId = source.mediaSourceId,
                    playSessionId = source.playSessionId,
                    liveStreamId = source.liveStreamId,
                    positionTicks = positionMs * TICKS_PER_MS,
                    failed = failed,
                ),
            )
            if (watched) userDataApi.markPlayedItem(itemId = source.itemId)
        }

    private fun report(what: String, onDone: () -> Unit = {}, call: suspend org.jellyfin.sdk.api.client.ApiClient.() -> Unit) {
        val api = sessions.session.value?.api ?: return
        reportScope.launch {
            // Reporting is best effort: playback goes on if the server misses one.
            runCatching { api.call() }
                .onSuccess { onDone() }
                .onFailure { Log.w(TAG, "Playback report '$what' failed", it) }
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

    /** "video hevc Main 10 DOVI_WITH_HDR10 3840x2160", "audio truehd 8ch ger", for the detailed log. */
    private fun MediaStream.describe(): String = listOfNotNull(
        type.name.lowercase(),
        codec,
        profile,
        videoRangeType?.name,
        width?.let { "${it}x$height" },
        channels?.let { "${it}ch" },
        language,
        "external".takeIf { isExternal },
    ).joinToString(" ")

    private companion object {
        const val TAG = "Playback"
        const val TICKS_PER_MS = 10_000L

        /** Bitrate "Auto": the highest step the design offers (120 Mbit/s). */
        const val AUTO_BITRATE = 120_000_000

        /** The server's reasons in a transcode URL, e.g. "VideoCodecNotSupported,AudioChannelsNotSupported". */
        val TRANSCODE_REASONS = Regex("[?&]TranscodeReasons=([^&]+)")
    }
}
