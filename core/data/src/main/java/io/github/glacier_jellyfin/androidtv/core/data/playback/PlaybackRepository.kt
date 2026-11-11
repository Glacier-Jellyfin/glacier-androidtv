package io.github.glacier_jellyfin.androidtv.core.data.playback

import io.github.glacier_jellyfin.androidtv.core.log.DebugOptions
import io.github.glacier_jellyfin.androidtv.core.log.DebugThrottle
import android.content.res.Resources
import io.github.glacier_jellyfin.androidtv.core.log.Log
import io.github.glacier_jellyfin.androidtv.core.data.Session
import io.github.glacier_jellyfin.androidtv.core.data.SessionManager
import io.github.glacier_jellyfin.androidtv.core.data.media.MusicTrack
import io.github.glacier_jellyfin.androidtv.core.data.media.Track
import io.github.glacier_jellyfin.androidtv.core.data.media.fullInsteadOfForced
import io.github.glacier_jellyfin.androidtv.core.data.media.mainInsteadOfExtra
import io.github.glacier_jellyfin.androidtv.core.data.media.toTrack
import io.github.glacier_jellyfin.androidtv.core.data.settings.HdAudioOutput
import io.github.glacier_jellyfin.androidtv.core.data.settings.PlaybackSettings
import io.github.glacier_jellyfin.androidtv.core.data.settings.ServerPreferencesRepository
import io.github.glacier_jellyfin.androidtv.core.data.settings.SettingsRepository
import io.github.glacier_jellyfin.androidtv.core.data.settings.SubtitleMode
import io.github.glacier_jellyfin.androidtv.core.jellyfin.playback.DeviceCapabilities
import io.github.glacier_jellyfin.androidtv.core.jellyfin.playback.VideoCodecSupport
import io.github.glacier_jellyfin.androidtv.core.jellyfin.playback.DeviceProfiles
import io.github.glacier_jellyfin.androidtv.core.jellyfin.playback.HlsSegments
import io.github.glacier_jellyfin.androidtv.core.jellyfin.playback.MediaCapabilityDetector
import io.github.glacier_jellyfin.androidtv.core.jellyfin.runSuspendCatching
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.audioApi
import org.jellyfin.sdk.api.client.extensions.mediaInfoApi
import org.jellyfin.sdk.api.client.extensions.mediaSegmentApi
import org.jellyfin.sdk.api.client.extensions.sessionApi
import org.jellyfin.sdk.api.client.extensions.userDataApi
import org.jellyfin.sdk.api.client.extensions.videoApi
import org.jellyfin.sdk.model.api.HardwareAccelerationType
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import org.jellyfin.sdk.model.api.MediaSegmentType
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.PlaybackInfoDto
import org.jellyfin.sdk.model.api.PlaybackInfoResponse
import org.jellyfin.sdk.model.api.PlaybackOrder
import org.jellyfin.sdk.model.api.PlaybackProgressInfo
import org.jellyfin.sdk.model.api.PlaybackStartInfo
import org.jellyfin.sdk.model.api.PlaybackStopInfo
import org.jellyfin.sdk.model.api.RepeatMode
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.minutes

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
    /** What the stream asks of the link, in bit/s: the file's bitrate, or the cap a transcode was held to. */
    val streamBitrate: Int? = null,
    /**
     * A master playlist (HLS) the app built from several tiers of the same transcode, so the player moves between
     * them with the link; [url] is the single stream it is made from. Null for everything else.
     */
    val ladder: String? = null,
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
    private val serverPreferences: ServerPreferencesRepository,
    private val jellyfin: Jellyfin,
) {

    /** Reports must outlive the player screen: "stopped" is sent while it closes. */
    private val reportScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _stopped = MutableSharedFlow<UUID>(extraBufferCapacity = 8)

    private data class OpenKey(
        val itemId: UUID,
        val startMs: Long,
        val audioIndex: Int?,
        val subtitleIndex: Int?,
        val segments: HlsSegments?,
        val versionId: String?,
    )

    /** The opens under way, for [open]'s retries. */
    private val inflight = HashMap<OpenKey, Deferred<PlaybackSource>>()

    /** The client [patientApi] made, with the session client it was made for. */
    private var patient: Pair<ApiClient, ApiClient>? = null

    /**
     * A title whose "stopped" report reached the server: resume points and watched state changed.
     * Live TV channels have neither, so they never show up here.
     */
    val stopped: SharedFlow<UUID> = _stopped.asSharedFlow()

    /**
     * What the server may send this device, after the [playback] settings
     * narrowed what it detected. With E-AC-3 from the server, TrueHD and
     * DTS-HD each convert only when no bitstream leaves for it: Android takes
     * TrueHD only with its own encoding, DTS-HD likewise, and Glacier packs
     * both where HDMI takes IEC 61937.
     */
    private fun DeviceCapabilities.forSettings(playback: PlaybackSettings): DeviceCapabilities {
        val convert = playback.hdAudio == HdAudioOutput.ServerEac3
        val glacierPacks = convert && capabilities.iec61937()
        return copy(
            maxAudioChannels = playback.audioChannels.max?.let { minOf(maxAudioChannels, it) } ?: maxAudioChannels,
            convertTrueHd = convert && !glacierPacks && "truehd" !in passthroughAudio,
            convertDtsHd = convert && !glacierPacks && !dtsHdPassthrough,
        )
    }

    /**
     * The picture sizes the server may transcode to, no bigger than what the display shows (or [heightCap]). Not for direct play: a 4K
     * file on a 1080p display plays as it is, the decoder scales it.
     */
    private fun DeviceCapabilities.fittingDisplay(heightCap: Int? = null): DeviceCapabilities {
        val (displayWidth, displayHeight) = displaySize()
        val height = minOf(displayHeight, heightCap ?: displayHeight)
        val width = if (heightCap == null) displayWidth else minOf(displayWidth, height * 16 / 9)
        fun VideoCodecSupport?.fit() = this?.copy(maxWidth = minOf(maxWidth, width), maxHeight = minOf(maxHeight, height))
        return copy(h264 = h264.fit(), hevc = hevc.fit(), av1 = av1.fit(), vp9 = vp9.fit())
    }

    /** The display's width and height in pixels, landscape. */
    private fun displaySize(): Pair<Int, Int> {
        val metrics = Resources.getSystem().displayMetrics
        return maxOf(metrics.widthPixels, metrics.heightPixels) to minOf(metrics.widthPixels, metrics.heightPixels)
    }

    /** What this device decodes and passes on, for the player's setup. */
    val device: DeviceCapabilities get() = capabilities.capabilities

    /** HDMI takes IEC 61937 bursts right now, so Glacier can hand TrueHD and DTS-HD on itself. */
    val iec61937: Boolean get() = capabilities.iec61937()

    /**
     * Ids of the files of [itemId] this device plays as they are, without the
     * server converting the picture: the server weighs every file against the
     * device profile when no file is named.
     */
    suspend fun directVersions(itemId: UUID): Set<String> = withContext(Dispatchers.IO) {
        val session = requireSession()
        val playback = settings.current().playback
        val device = capabilities.capabilities.forSettings(playback)
        val info = session.api.mediaInfoApi.getPostedPlaybackInfo(
            itemId = itemId,
            data = PlaybackInfoDto(
                userId = session.userId,
                maxStreamingBitrate = AUTO_BITRATE,
                maxAudioChannels = device.maxAudioChannels,
                // The picture decides; a subtitle burned in would make every file look converted.
                subtitleStreamIndex = -1,
                deviceProfile = DeviceProfiles.build(device, AUTO_BITRATE, playback.subtitleBurnIn, HlsSegments.preferredFor(device)),
                enableDirectPlay = true,
                enableDirectStream = true,
                enableTranscoding = true,
                allowVideoStreamCopy = true,
                allowAudioStreamCopy = true,
                autoOpenLiveStream = false,
            ),
        ).content
        // Direct stream: the server only repacks the file.
        info.mediaSources.filter { it.supportsDirectPlay || it.supportsDirectStream }.mapNotNull { it.id }.toSet()
    }

    /**
     * Asks the server how to play [itemId] on this device and builds the stream URL.
     * [segments] null: the container that suits the device, see [HlsSegments.preferredFor].
     * [versionId]: the file to play of a title with several (media source id); null for the main one.
     * [reuse]: a retry of an open that did not answer in time takes that open's answer, if it is still coming.
     */
    suspend fun open(
        itemId: UUID,
        startMs: Long,
        audioIndex: Int?,
        subtitleIndex: Int?,
        segments: HlsSegments? = null,
        versionId: String? = null,
        reuse: Boolean = false,
    ): PlaybackSource {
        // The caller may give up (focus moved on, screen closed, no answer in time) while the server
        // goes on opening a channel's stream. The open runs on here and gives that stream back.
        val key = OpenKey(itemId, startMs, audioIndex, subtitleIndex, segments, versionId)
        val opening = synchronized(inflight) {
            // A retry waits for the answer still on its way instead of asking the server once more.
            inflight[key]?.takeIf { reuse && it.isActive } ?: reportScope.async {
                openNow(itemId, startMs, audioIndex, subtitleIndex, segments, versionId)
            }.also { started ->
                inflight[key] = started
                started.invokeOnCompletion { synchronized(inflight) { if (inflight[key] === started) inflight.remove(key) } }
            }
        }
        val opened = try {
            withTimeoutOrNull(OPEN_TIMEOUT_MS) { opening.await() }
        } catch (e: CancellationException) {
            giveBackWhenDone(opening)
            throw e
        }
        if (opened == null) {
            giveBackWhenDone(opening)
            throw SocketTimeoutException("No answer for playing $itemId within ${OPEN_TIMEOUT_MS / 1000} s")
        }
        return opened
    }

    /** Closes the live stream [opening] brings, nobody plays it. */
    private fun giveBackWhenDone(opening: Deferred<PlaybackSource>) {
        opening.invokeOnCompletion { failure ->
            if (failure != null) return@invokeOnCompletion
            reportScope.launch {
                val source = opening.await()
                val id = source.liveStreamId ?: return@launch
                Log.i(TAG, "Closing the live stream of ${source.itemId}, it was opened after the caller gave up")
                closeLiveStreams(requireSession().api, setOf(id))
            }
        }
    }

    /** What a stream may use of the link, and until when [budget] counts. */
    private var budget: Int? = null
    private var budgetUntilMs = 0L
    /** A stall set [budget]: the next open measures the link again, and the lower of the two counts. */
    private var verify = false

    @Synchronized
    private fun currentBudget(): Int? = budget?.takeIf { System.currentTimeMillis() < budgetUntilMs }

    @Synchronized
    private fun keepBudget(value: Int, forMs: Long, afterStall: Boolean = false) {
        verify = afterStall
        budget = value
        budgetUntilMs = System.currentTimeMillis() + forMs
    }

    /** One download of the server's test; bit/s, or null when it failed. A timeout counts as the most it could have been. */
    private suspend fun downloadBps(api: ApiClient, blockBytes: Int): Double? {
        val started = System.nanoTime()
        var failed = false
        val bytes = withTimeoutOrNull(PROBE_TIMEOUT_MS) {
            runSuspendCatching {
                val size = api.mediaInfoApi.getBitrateTestBytes(blockBytes).content.size
                // A debug cap makes the test as slow as a stream would be.
                delay(DebugThrottle.waitNs(size) / 1_000_000)
                size
            }
                .onFailure {
                    failed = true
                    Log.w(TAG, "Measuring the bandwidth failed", it)
                }
                .getOrNull()
        }
        if (failed || bytes == 0) return null
        val seconds = (System.nanoTime() - started) / 1e9
        // No answer in time: less than the block over the time limit; half of that is a safe guess.
        return if (bytes == null) blockBytes * 8 / (PROBE_TIMEOUT_MS / 1000.0) / 2 else bytes * 8 / seconds
    }

    /**
     * Measures the link to the server with the server's own download test and keeps what a stream
     * may take of it. Wi-Fi dips and throttling pass, so a slow result is measured once more and the
     * better one counts. Null when the test fails: the stream is then not held back.
     * [fresh]: measure even when a measurement is still valid.
     */
    private suspend fun measureBudget(api: ApiClient, fresh: Boolean = false): Int? {
        if (!fresh) currentBudget()?.let { return it }
        // A small block first: on a slow link a big one takes many seconds. A fast link gets the big block as well,
        // since a small one is over before the connection has sped up. The better of the two counts.
        var measured = downloadBps(api, PROBE_SMALL_BYTES) ?: return null
        val second = if (measured >= PROBE_FAST_BPS) PROBE_BYTES else PROBE_SMALL_BYTES
        downloadBps(api, second)?.let { measured = maxOf(measured, it) }
        // After a stall the stream's own numbers count too: the link may carry less than a short test shows.
        val value = synchronized(this) { StreamBudget.of(measured.toLong()).let { if (verify) minOf(it, budget ?: it) else it } }
        Log.i(TAG, "Bandwidth to the server ${(measured / 1e6).toInt()} Mbit/s, streams may use ${value / 1_000_000} Mbit/s")
        keepBudget(value, PROBE_VALID_MS)
        return value
    }

    /** What streams may use of the link by the latest measurement; null when there is none or it is out of date. */
    fun budget(): Int? = currentBudget()

    /** A new measurement for a reduced stream that may go back to the file; null when it fails. */
    suspend fun probeBudget(): Int? = withContext(Dispatchers.IO) { measureBudget(requireSession().api, fresh = true) }

    /**
     * A master playlist of the [resolutions] of a transcode, each asked from the server with [transcodingUrl] and joined.
     * The player can then move to a smaller picture when the link needs it, without opening the title again. Null when fewer
     * than two tiers answer; every tier needs the video codec of the first, or the player cannot move between them.
     */
    private suspend fun buildLadder(
        session: Session,
        resolutions: List<StreamBudget.Resolution>,
        baseUrl: String,
        transcodingUrl: suspend (StreamBudget.Resolution) -> String?,
    ): String? = coroutineScope {
        val tiers = resolutions.map { resolution ->
            async {
                runSuspendCatching {
                    transcodingUrl(resolution)?.let { fetchTier(session, baseUrl + it, main = resolution == resolutions.first()) }
                }.onFailure { Log.w(TAG, "Tier of ${resolution.height}p failed", it) }.getOrNull()
            }
        }.awaitAll().filterNotNull()
        val codec = tiers.firstOrNull { it.main }?.codec ?: tiers.firstOrNull()?.codec
        val fitting = tiers.filter { it.codec == codec }
        if (fitting.size < 2) {
            Log.i(TAG, "No tiers for this stream: ${tiers.size} answered, ${fitting.size} with the main codec")
            return@coroutineScope null
        }
        Log.i(TAG, "Tiers: " + fitting.joinToString { "${RESOLUTION.find(it.info)?.groupValues?.get(1) ?: "?"} (${it.bandwidth / 1_000_000} Mbit/s)" })
        // Segments start on a keyframe (the server forces one at every segment), which Media3 only assumes with this tag:
        // without it the first segment of a new tier overlaps the last of the old one and is loaded twice.
        "#EXTM3U\n#EXT-X-INDEPENDENT-SEGMENTS\n" + fitting.joinToString("") { "${it.info}\n${it.url}\n" }
    }

    /** The one variant of the server's master playlist at [masterUrl], with its address made absolute. */
    private fun fetchTier(session: Session, masterUrl: String, main: Boolean = false): Tier? {
        val connection = (URL(masterUrl).openConnection() as HttpURLConnection).apply {
            setRequestProperty("Authorization", session.authorization())
            connectTimeout = TIER_TIMEOUT_MS
            readTimeout = TIER_TIMEOUT_MS
        }
        val lines = try {
            connection.inputStream.bufferedReader().use { it.readLines() }
        } finally {
            connection.disconnect()
        }
        val at = lines.indexOfFirst { it.startsWith("#EXT-X-STREAM-INF:") }
        val uri = lines.drop(at + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") }
        if (at < 0 || uri == null) return null
        // The subtitle group the line names is not part of the joined playlist.
        val info = lines[at].replace(SUBTITLES_GROUP, "")
        return Tier(
            info = info,
            url = URI(masterUrl).resolve(uri).toString(),
            bandwidth = BANDWIDTH.find(info)?.groupValues?.get(1)?.toLongOrNull() ?: 0,
            codec = CODECS.find(info)?.groupValues?.get(1)?.substringBefore(',')?.substringBefore('.'),
            main = main,
        )
    }

    private class Tier(val info: String, val url: String, val bandwidth: Long, val codec: String?, val main: Boolean)

    /**
     * A stream of [streamBitrate] stalled: streams may use less from now on, so the next one is
     * held to it. Returns the new budget.
     */
    fun lowerBudget(streamBitrate: Int): Int {
        val value = StreamBudget.afterStall(streamBitrate, currentBudget())
        keepBudget(value, STALL_VALID_MS, afterStall = true)
        Log.w(TAG, "Playback stalled at ${streamBitrate / 1_000_000} Mbit/s, streams may use ${value / 1_000_000} Mbit/s")
        return value
    }

    /**
     * A client that waits as long as the server takes. Opening a channel can take minutes: the
     * server probes the stream first, and a dead one only fails after a long wait. The answer must
     * still come in, or the live stream it opened is never closed.
     */
    private fun patientApi(api: ApiClient): ApiClient = synchronized(this) {
        patient?.takeIf { it.first === api }?.second
            ?: jellyfin.createApi(
                baseUrl = api.baseUrl,
                accessToken = api.accessToken,
                clientInfo = api.clientInfo,
                deviceInfo = api.deviceInfo,
                httpClientOptions = api.httpClientOptions.copy(requestTimeout = PATIENT_TIMEOUT, socketTimeout = PATIENT_TIMEOUT),
            ).also { patient = api to it }
    }

    private suspend fun openNow(
        itemId: UUID,
        startMs: Long,
        audioIndex: Int?,
        subtitleIndex: Int?,
        segments: HlsSegments?,
        versionId: String?,
    ): PlaybackSource = withContext(Dispatchers.IO) {
        val session = requireSession()
        val api = session.api
        val playback = settings.current().playback
        val device = capabilities.capabilities.forSettings(playback)
        // The server only takes the streams asked for when the source is named too
        // (MediaInfoHelper.SetDeviceSpecificData); otherwise a transcode carries its own default
        // audio. A title's main source has the item's id.
        var sourceId = versionId ?: itemId.toString().replace("-", "").takeIf { audioIndex != null || subtitleIndex != null }
        // forLadder: for the tiers of a ladder the transcode is no bigger than the display shows, so the server spends the
        // bitrate on the detail of that picture, not on pixels the display throws away.
        suspend fun playbackInfo(bitrate: Int, forLadder: Boolean = false, maxHeight: Int? = null) = patientApi(api).mediaInfoApi.getPostedPlaybackInfo(
            itemId = itemId,
            data = PlaybackInfoDto(
                userId = session.userId,
                maxStreamingBitrate = bitrate,
                maxAudioChannels = device.maxAudioChannels,
                startTimeTicks = startMs * TICKS_PER_MS,
                audioStreamIndex = audioIndex,
                subtitleStreamIndex = subtitleIndex,
                mediaSourceId = sourceId,
                deviceProfile = DeviceProfiles.build(
                    if (forLadder && DebugOptions.fitDisplay) device.fittingDisplay(maxHeight) else device,
                    bitrate,
                    playback.subtitleBurnIn,
                    segments ?: HlsSegments.preferredFor(device),
                ),
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
        val cap = playback.maxBitrate.bitsPerSecond
        // "Auto" measures the link while the server answers: a file that needs more than the link
        // carries stalls, so the server converts it to what fits.
        val verifying = synchronized(this) { verify }
        val measuring = if (cap == null && (currentBudget() == null || verifying)) reportScope.async { measureBudget(api, fresh = verifying) } else null
        var info = playbackInfo(AUTO_BITRATE)
        // What a transcode was held to, null while the stream is not limited.
        var limit: Int? = null
        // Whatever goes wrong from here on, the live stream an answer opened is given back.
        var handedOn = false
        try {
            // Each answer opens a channel's live stream once more. The answer left behind closes its own
            // after the new one is open, so the tuner is not given up in between.
            suspend fun askAgain(bitrate: Int, fitDisplay: Boolean = false) {
                val unused = info
                info = playbackInfo(bitrate, forLadder = fitDisplay)
                limit = bitrate.takeIf { it < AUTO_BITRATE }
                closeLiveStreams(api, unused)
            }
            // Several versions where the first is not named after the item: ask without the source.
            if (sourceId != null && info.errorCode == null && info.mediaSources.isEmpty()) {
                sourceId = null
                askAgain(AUTO_BITRATE)
            }
            if (cap != null && info.errorCode == null && info.mediaSources.firstOrNull()?.supportsDirectPlay == false) {
                askAgain(cap)
            } else if (cap == null && info.errorCode == null) {
                val first = info.mediaSources.firstOrNull()
                // A live channel has no file bitrate worth comparing.
                if (first != null && first.liveStreamId == null && first.bitrate != null) {
                    val budget = if (verifying) measuring?.await() ?: currentBudget() else currentBudget() ?: measuring?.await()
                    if (budget != null && StreamBudget.exceeds(first.bitrate, budget)) {
                        Log.i(TAG, "The file takes ${first.bitrate!! / 1_000_000} Mbit/s, the link carries ${budget / 1_000_000}: asking for a stream that fits")
                        askAgain(budget)
                    }
                }
            }
            // A video the server has to transcode anyway is made for the display, not for the file's size: a 4K file
            // on a 1080p display comes as 1080p. A video it only repackages, or direct play, stays as it is.
            run {
                val first = info.mediaSources.firstOrNull() ?: return@run
                val video = first.mediaStreams.orEmpty().firstOrNull { it.type == MediaStreamType.VIDEO }
                val (displayWidth, displayHeight) = displaySize()
                val bigger = (video?.width ?: 0) > displayWidth || (video?.height ?: 0) > displayHeight
                if (info.errorCode == null && bigger && !first.supportsDirectPlay && !first.supportsDirectStream && first.liveStreamId == null) {
                    askAgain(limit ?: AUTO_BITRATE, fitDisplay = true)
                }
            }
            info.errorCode?.let { error("Server refused playback: $it") }
            val source = info.mediaSources.firstOrNull() ?: error("Server returned no media source")

            val baseUrl = checkNotNull(api.baseUrl).trimEnd('/')
            val streams = source.mediaStreams.orEmpty()
            // The server's own audio pick may be a commentary the file marked as default: ask again with the main track.
            if (audioIndex == null) {
                val serverPick = source.defaultAudioStreamIndex
                val main = mainInsteadOfExtra(serverPick, streams.filter { it.type == MediaStreamType.AUDIO }.map { it.toTrack() })
                if (main != null && main != serverPick) {
                    Log.i(TAG, "Audio of $itemId: main track $main instead of extra track $serverPick")
                    handedOn = true
                    return@withContext try {
                        openNow(itemId, startMs, main, subtitleIndex, segments, versionId)
                    } finally {
                        closeLiveStreams(api, info)
                    }
                }
            }
            // The server's own pick may be a forced track that shows next to nothing: ask again with the full one.
            if (subtitleIndex == null) {
                val serverPick = source.defaultSubtitleStreamIndex?.takeIf { it >= 0 }
                val audio = streams.firstOrNull { it.type == MediaStreamType.AUDIO && it.index == (audioIndex ?: source.defaultAudioStreamIndex) }
                val onlyForced = (serverPreferences.preferences.value ?: serverPreferences.refresh())?.subtitleMode == SubtitleMode.OnlyForced
                val subtitles = streams.filter { it.type == MediaStreamType.SUBTITLE }.map { it.toTrack() }
                val full = fullInsteadOfForced(serverPick, subtitles, audio?.toTrack(), onlyForced)
                if (full != null && full != serverPick) {
                    Log.i(TAG, "Subtitles of $itemId: full track $full instead of forced track $serverPick")
                    handedOn = true
                    return@withContext try {
                        openNow(itemId, startMs, audioIndex, full, segments, versionId)
                    } finally {
                        closeLiveStreams(api, info)
                    }
                }
            }
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
            // A video the server transcodes comes in tiers of its size (4K, 1080p, 720p; none bigger than the display or the
            // file), so the player can move to a smaller picture when the link needs it. A video it only repackages (direct
            // stream) stays as it is: tiers would be transcodes of it.
            val (displayWidth, displayHeight) = displaySize()
            Log.i(TAG, "Transcodes for a display of ${displayWidth}x$displayHeight, fit: ${DebugOptions.fitDisplay}")
            val resolutions = if (cap == null && method == PlaybackMethod.Transcode && source.liveStreamId == null &&
                source.transcodingSubProtocol == MediaStreamProtocol.HLS
            ) {
                StreamBudget.resolutions(displayHeight, streams.firstOrNull { it.type == MediaStreamType.VIDEO }?.height ?: displayHeight)
            } else {
                emptyList()
            }
            // The biggest picture the link carries: its measured speed against what a stream of that size takes.
            val linkBudget = currentBudget()
            val startTier = resolutions.indexOfFirst { linkBudget == null || StreamBudget.expectedBitrate(it.height) <= linkBudget }
                .takeIf { it >= 0 } ?: resolutions.lastIndex
            val ladder = if (resolutions.size >= 2) {
                buildLadder(session, resolutions, baseUrl) { tier ->
                    playbackInfo(tier.bitrate, forLadder = true, maxHeight = tier.height).mediaSources.firstOrNull()?.transcodingUrl
                }
            } else {
                null
            }
            val reasons = source.transcodingUrl?.takeIf { method != PlaybackMethod.DirectPlay }?.let { TRANSCODE_REASONS.find(it)?.groupValues?.get(1) }
            // The tracks asked for, and for a stream the server builds the audio it was told to put in.
            val streamAudio = source.transcodingUrl?.takeIf { method != PlaybackMethod.DirectPlay }?.let { AUDIO_STREAM_INDEX.find(it)?.groupValues?.get(1) }
            // What the file holds, so a bug report shows the formats without detailed logging.
            val video = streams.firstOrNull { it.type == MediaStreamType.VIDEO }
            val audio = streams.firstOrNull { it.type == MediaStreamType.AUDIO && it.index == (audioIndex ?: source.defaultAudioStreamIndex) }
            Log.i(
                TAG,
                "Playing $itemId: $method, container ${source.container}, ${source.bitrate} bit/s" + reasons?.let { ", because $it" }.orEmpty() +
                    video?.let { ", video ${it.describe()}" }.orEmpty() + audio?.let { ", audio track ${it.describe()}" }.orEmpty() +
                    ", audio ${audioIndex ?: "default " + source.defaultAudioStreamIndex}, subtitles ${subtitleIndex ?: "default " + source.defaultSubtitleStreamIndex}" +
                    streamAudio?.let { ", stream audio $it" }.orEmpty(),
            )
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
                ladder = ladder,
                // A ladder starts at what it was built for; the file's bitrate is no guide for a transcode to the display.
                streamBitrate = if (ladder != null) resolutions[startTier].bitrate else listOfNotNull(source.bitrate, limit.takeIf { method != PlaybackMethod.DirectPlay }).minOrNull(),
                method = method,
                headers = mapOf("Authorization" to session.authorization()),
                // A stream carries the audio the server put in it, whatever was asked for.
                audioIndex = streamAudio?.toIntOrNull() ?: audioIndex ?: source.defaultAudioStreamIndex,
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
                            rangeType = it.videoRangeType.serialName,
                            dolbyVisionProfile = it.dvProfile,
                        )
                    },
                    audio = streams.filter { it.type == MediaStreamType.AUDIO && !it.isExternal }.map {
                        SourceAudio(it.index, it.codec, it.profile, it.channels, it.sampleRate, it.bitRate)
                    },
                    transcodeReasons = reasons?.split(',')?.filter { it.isNotBlank() }.orEmpty(),
                ),
            )
        } catch (e: Throwable) {
            if (!handedOn) closeLiveStreams(api, info)
            throw e
        }
    }

    /**
     * The transcoder's state for [source], from this device's session on the server; null
     * for direct play, when the server does not say, or when this account may not see sessions.
     */
    suspend fun transcodeStatus(source: PlaybackSource): TranscodeStatus? = withContext(Dispatchers.IO) {
        val api = sessions.session.value?.api ?: return@withContext null
        val info = runSuspendCatching { api.sessionApi.getSessions(deviceId = api.deviceInfo.id).content }
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
            headers = mapOf("Authorization" to session.authorization()),
            audioIndex = null,
            subtitleIndex = null,
            audioTracks = emptyList(),
            subtitles = emptyList(),
        )
    }

    /** Intro, credits and the like; empty when the server has none or cannot tell (no segment provider). */
    suspend fun segments(itemId: UUID): List<MediaSegment> = withContext(Dispatchers.IO) {
        val api = requireSession().api
        runSuspendCatching { api.mediaSegmentApi.getItemSegments(itemId).content.items }
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
        report("stopped", onDone = { if (!source.live) _stopped.tryEmit(source.itemId) }) {
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

    private fun report(what: String, onDone: () -> Unit = {}, call: suspend ApiClient.() -> Unit) {
        val api = sessions.session.value?.api ?: return
        reportScope.launch {
            // Reporting is best effort: playback goes on if the server misses one.
            runSuspendCatching { api.call() }
                .onSuccess { onDone() }
                .onFailure { Log.w(TAG, "Playback report '$what' failed", it) }
        }
    }

    /** Gives back the live streams [info] opened: on the server, each answer is one more user of a channel's tuner. */
    private suspend fun closeLiveStreams(api: ApiClient, info: PlaybackInfoResponse) =
        closeLiveStreams(api, info.mediaSources.mapNotNullTo(HashSet()) { it.liveStreamId })

    private suspend fun closeLiveStreams(api: ApiClient, ids: Set<String>) {
        // Also when playback was cancelled meanwhile: the tuner stays taken otherwise.
        withContext(NonCancellable) {
            for (id in ids) {
                runCatching { api.mediaInfoApi.closeLiveStream(id) }
                    .onFailure { Log.w(TAG, "Closing an unused live stream failed", it) }
            }
        }
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
        videoRangeType.name,
        dvProfile?.let { "Dolby Vision profile $it" },
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

        const val TIER_TIMEOUT_MS = 10_000

        val SUBTITLES_GROUP = Regex(",SUBTITLES=\"[^\"]*\"")
        val BANDWIDTH = Regex("[:,]BANDWIDTH=(\\d+)")
        val RESOLUTION = Regex("RESOLUTION=([0-9x]+)")
        val CODECS = Regex("CODECS=\"([^\"]*)\"")

        /** The size of the download that measures the link: a few seconds on a weak one. */
        const val PROBE_BYTES = 5_000_000

        /** A link that needs longer than this for [PROBE_BYTES] is below 5 Mbit/s. */
        const val PROBE_TIMEOUT_MS = 8_000L

        const val PROBE_SMALL_BYTES = 1_000_000

        /** A link above this gets the big block as well. */
        const val PROBE_FAST_BPS = 12_000_000

        /** How long a measurement counts; a link changes (Wi-Fi, other devices). */
        const val PROBE_VALID_MS = 3 * 60_000L

        /** How long a stall counts, so the next titles do not stall the same way. */
        const val STALL_VALID_MS = 5 * 60_000L

        /** How long a caller waits for [open], the old request timeout of the client. */
        const val OPEN_TIMEOUT_MS = 30_000L

        /** Longer than the server takes to give up on a dead channel (about five minutes). */
        val PATIENT_TIMEOUT = 10.minutes

        /** The server's reasons in a transcode URL, e.g. "VideoCodecNotSupported,AudioChannelsNotSupported". */
        val TRANSCODE_REASONS = Regex("[?&]TranscodeReasons=([^&]+)")
        /** The audio stream a transcode URL tells the server to use. */
        val AUDIO_STREAM_INDEX = Regex("[?&]AudioStreamIndex=([^&]+)", RegexOption.IGNORE_CASE)
    }
}
