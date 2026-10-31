package io.github.glacier_jellyfin.androidtv.core.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener
import io.github.glacier_jellyfin.androidtv.core.log.Log
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** A short file the playback check plays, from assets/playback-check (scripts/make-playback-samples.py). */
data class CheckSample(val file: String, val label: String, val audio: Boolean = false)

data class CheckResult(
    val sample: CheckSample,
    val outcome: Outcome,
    /** The decoder Media3 picked; null for sound passed through as a bitstream. */
    val decoder: String? = null,
    /** How the sound left the device, e.g. "E-AC-3 bitstream" or "PCM 16-bit". */
    val audioOutput: String? = null,
    /** Media3's error code, e.g. "ERROR_CODE_DECODING_FAILED", and the first cause. */
    val error: String? = null,
    /** The decoder does not declare the format, and Media3 tried it anyway. */
    val beyondDeclared: Boolean = false,
    val droppedFrames: Int = 0,
) {
    /** [Crashed]: the decoder took the whole process down, noted by whoever runs the check in a process of its own. */
    enum class Outcome { Plays, NotSupported, Fails, Crashed }

    /** One line for the log. */
    fun describe(): String = listOfNotNull(
        "${sample.label}: $outcome",
        "beyond what the decoder declares".takeIf { beyondDeclared },
        decoder?.let { "decoder $it" },
        audioOutput?.let { "output $it" },
        droppedFrames.takeIf { it > 0 }?.let { "$it frames dropped" },
        error,
    ).joinToString(", ")
}

/**
 * Plays [SAMPLES] one after another through the same player as real titles,
 * to see what this device and its HDMI chain play: a diagnosis for bug
 * reports, never used to change what the server is asked for.
 */
class PlaybackCheck(private val context: Context, private val dolbyVisionBaseLayer: Boolean) {

    /**
     * Plays [sample] on the main thread and reports what happened. [show]
     * gets the player while it plays (for the preview) and null afterwards.
     */
    @OptIn(UnstableApi::class)
    suspend fun play(sample: CheckSample, show: (GlacierPlayback?) -> Unit): CheckResult = withContext(Dispatchers.Main) {
        val playback = GlacierPlayer.create(
            context,
            StreamRequest(
                url = "asset:///playback-check/${sample.file}",
                isHls = false,
                headers = emptyMap(),
                startPositionMs = 0,
                dolbyVisionBaseLayer = dolbyVisionBaseLayer,
            ),
        )
        show(playback)
        val watch = Watch(sample, playback)
        try {
            val result = withTimeoutOrNull(TIMEOUT_MS) { suspendCancellableCoroutine { watch.start(it) } }
                ?: watch.result(CheckResult.Outcome.Fails, "no end within ${TIMEOUT_MS / 1000} s")
            Log.i(TAG, result.describe())
            result
        } finally {
            show(null)
            playback.player.release()
        }
    }

    /** Follows one sample from start to end, error or a sound output that dies. */
    @OptIn(UnstableApi::class)
    private class Watch(private val sample: CheckSample, private val playback: GlacierPlayback) {
        private var done: CancellableContinuation<CheckResult>? = null
        private var beyondDeclared = false
        private var firstFrame = false
        private var dropped = 0

        fun start(continuation: CancellableContinuation<CheckResult>) {
            done = continuation
            val player = playback.player
            player.addListener(object : Player.Listener {
                override fun onTracksChanged(tracks: Tracks) {
                    val type = if (sample.audio) C.TRACK_TYPE_AUDIO else C.TRACK_TYPE_VIDEO
                    val group = tracks.groups.firstOrNull { it.type == type } ?: return
                    // Media3 leaves out what no renderer takes; it still tries what goes beyond a decoder's declared profiles.
                    if (!group.isTrackSupported(0, /* allowExceedsCapabilities = */ true)) {
                        finish(result(CheckResult.Outcome.NotSupported))
                    } else {
                        beyondDeclared = !group.isTrackSupported(0)
                    }
                }

                override fun onRenderedFirstFrame() {
                    firstFrame = true
                }

                override fun onPlaybackStateChanged(state: Int) {
                    if (state != Player.STATE_ENDED) return
                    val played = if (sample.audio) playback.audioEncoding != null else firstFrame
                    finish(if (played) result(CheckResult.Outcome.Plays) else result(CheckResult.Outcome.Fails, "nothing reached the screen or the speakers"))
                }

                override fun onPlayerError(error: PlaybackException) {
                    finish(result(CheckResult.Outcome.Fails, error.describe()))
                }
            })
            player.addAnalyticsListener(object : AnalyticsListener {
                override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
                    dropped += droppedFrames
                }

                // Media3 recovers from a dead sound output by itself, so it never becomes a player error.
                override fun onAudioSinkError(eventTime: AnalyticsListener.EventTime, audioSinkError: Exception) {
                    finish(result(CheckResult.Outcome.Fails, "sound output failed: ${audioSinkError.message}"))
                }
            })
            player.volume = 0f
            player.play()
        }

        fun result(outcome: CheckResult.Outcome, error: String? = null) = CheckResult(
            sample = sample,
            outcome = outcome,
            decoder = if (sample.audio) playback.audioDecoder else playback.videoDecoder,
            audioOutput = playback.audioEncoding?.let { GlacierPlayback.encodingName(it) },
            error = error,
            beyondDeclared = beyondDeclared,
            droppedFrames = dropped,
        )

        private fun finish(result: CheckResult) {
            val continuation = done ?: return
            done = null
            if (continuation.isActive) continuation.resume(result)
        }

        private fun PlaybackException.describe(): String =
            errorCodeName + (cause?.let { ": ${it.javaClass.simpleName} ${it.message.orEmpty()}" } ?: "")
    }

    companion object {
        private const val TAG = "PlaybackCheck"
        private const val TIMEOUT_MS = 15_000L

        /** In the order they play: video first, then the sound formats a receiver may take as a bitstream. */
        val SAMPLES = listOf(
            CheckSample("h264.mkv", "H.264 High"),
            CheckSample("hevc.mkv", "HEVC Main"),
            CheckSample("hevc-hdr10.mkv", "HEVC Main 10 HDR10"),
            CheckSample("hevc-hlg.mkv", "HEVC Main 10 HLG"),
            CheckSample("dv-p5.mkv", "Dolby Vision profile 5"),
            CheckSample("dv-p7.mkv", "Dolby Vision profile 7"),
            CheckSample("dv-p81.mkv", "Dolby Vision profile 8.1"),
            CheckSample("av1-hdr10.mkv", "AV1 Main 10 HDR10"),
            CheckSample("vp9-profile2.webm", "VP9 profile 2"),
            CheckSample("aac.mka", "AAC stereo", audio = true),
            CheckSample("ac3.mka", "AC-3 5.1", audio = true),
            CheckSample("eac3.mka", "E-AC-3 5.1", audio = true),
            CheckSample("truehd.mka", "TrueHD 5.1", audio = true),
            CheckSample("dts.mka", "DTS 5.1", audio = true),
        )
    }
}
