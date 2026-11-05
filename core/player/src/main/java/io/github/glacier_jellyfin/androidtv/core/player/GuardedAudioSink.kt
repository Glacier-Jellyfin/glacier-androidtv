package io.github.glacier_jellyfin.androidtv.core.player

import android.media.AudioTrack
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import io.github.glacier_jellyfin.androidtv.core.log.Log
import java.nio.ByteBuffer

/**
 * The audio sink with a [BitstreamGuard] in front. Until a bitstream track
 * goes silent or dies, every call goes straight through.
 *
 * - A silent track: [onStall] asks the player for a new one (a seek to the
 *   current position flushes the sink), and the next track waits a moment.
 * - A dead track: the sink lets the dead track go and refuses data until the
 *   output takes the format again, so the next write opens the same bitstream
 *   track. Capability changes wait meanwhile: on hearing that the receiver is
 *   gone, the player would decode the sound. If the link stays away, the
 *   dead track's error goes up as it would have at once.
 *
 * [onStall] is called on the playback thread.
 */
@OptIn(UnstableApi::class)
internal class GuardedAudioSink(
    sink: AudioSink,
    private val onStall: () -> Unit,
    private val guard: BitstreamGuard = BitstreamGuard(),
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) : ForwardingAudioSink(sink) {

    private var format: Format? = null
    private var listener: AudioSink.Listener? = null
    /** The dead track's error, thrown if the link does not come back. */
    private var deadTrack: AudioSink.WriteException? = null
    /** A capability change held back during a link hold. */
    private var capabilitiesChanged = false

    override fun setListener(listener: AudioSink.Listener) {
        this.listener = listener
        super.setListener(GuardedListener(listener))
    }

    override fun configure(audioSinkConfig: AudioSink.AudioSinkConfig) {
        format = audioSinkConfig.format
        guard.onConfigure(bitstream = audioSinkConfig.format.sampleMimeType != MimeTypes.AUDIO_RAW, clock())
        super.configure(audioSinkConfig)
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        val now = clock()
        // Refusing data is ordinary back pressure: the renderer offers the buffer again.
        if (guard.holdWrites(now)) return false
        val holding = guard.holdingRoute
        when (guard.routeDecision(routeBack = holding && format?.let(::supportsFormat) == true, now)) {
            BitstreamGuard.RouteDecision.Wait -> return false
            BitstreamGuard.RouteDecision.GiveUp -> giveUp()
            BitstreamGuard.RouteDecision.Write -> Unit
        }
        return try {
            super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount).also {
                if (holding) {
                    Log.i(TAG, "The HDMI link is back, the bitstream plays on")
                    guard.onWritten()
                    deadTrack = null
                    // The output is as it was before the drop; the change in between does not count.
                    capabilitiesChanged = false
                }
            }
        } catch (e: AudioSink.InitializationException) {
            if (!holding) throw e
            // The output takes the format again but the link still settles: the next write tries again.
            super.flush()
            false
        } catch (e: AudioSink.WriteException) {
            if (!e.isDeadTrack() || !guard.onDeadTrack(clock())) throw e
            if (deadTrack == null) Log.w(TAG, "The bitstream output dropped, waiting for the HDMI link")
            deadTrack = e
            // Lets the dead track go; the configuration stays, so the next write opens the same output.
            super.flush()
            false
        }
    }

    private fun giveUp() {
        Log.w(TAG, "The HDMI link did not come back")
        if (capabilitiesChanged) {
            capabilitiesChanged = false
            listener?.onAudioCapabilitiesChanged()
        }
        deadTrack?.let {
            deadTrack = null
            throw it
        }
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        val position = super.getCurrentPositionUs(sourceEnded)
        if (!sourceEnded && guard.onPosition(position, hasPendingData(), clock())) {
            Log.w(TAG, "The bitstream output stopped playing, opening it again")
            onStall()
        }
        return position
    }

    override fun play() {
        guard.onPlay(clock())
        super.play()
    }

    override fun pause() {
        guard.onPause()
        super.pause()
    }

    override fun flush() {
        guard.onFlush(clock())
        super.flush()
    }

    override fun reset() {
        guard.onReset(clock())
        deadTrack = null
        capabilitiesChanged = false
        super.reset()
    }

    private fun AudioSink.WriteException.isDeadTrack() = errorCode == AudioTrack.ERROR_DEAD_OBJECT || errorCode == ERROR_NATIVE_DEAD_OBJECT

    /** Passes everything on to the renderer, except capability changes during a link hold. */
    private inner class GuardedListener(private val inner: AudioSink.Listener) : AudioSink.Listener {
        override fun onAudioCapabilitiesChanged() {
            if (guard.holdingRoute) capabilitiesChanged = true else inner.onAudioCapabilitiesChanged()
        }

        override fun onPositionDiscontinuity() = inner.onPositionDiscontinuity()
        override fun onPositionAdvancing(playoutStartSystemTimeMs: Long) = inner.onPositionAdvancing(playoutStartSystemTimeMs)
        override fun onUnderrun(bufferSize: Int, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) = inner.onUnderrun(bufferSize, bufferSizeMs, elapsedSinceLastFeedMs)
        override fun onSkipSilenceEnabledChanged(skipSilenceEnabled: Boolean) = inner.onSkipSilenceEnabledChanged(skipSilenceEnabled)
        override fun onOffloadBufferEmptying() = inner.onOffloadBufferEmptying()
        override fun onOffloadBufferFull() = inner.onOffloadBufferFull()
        override fun onAudioSinkError(audioSinkError: Exception) = inner.onAudioSinkError(audioSinkError)
        override fun onAudioTrackInitialized(audioTrackConfig: AudioSink.AudioTrackConfig) = inner.onAudioTrackInitialized(audioTrackConfig)
        override fun onAudioTrackReleased(audioTrackConfig: AudioSink.AudioTrackConfig) = inner.onAudioTrackReleased(audioTrackConfig)
        override fun onSilenceSkipped() = inner.onSilenceSkipped()
        override fun onAudioSessionIdChanged(audioSessionId: Int) = inner.onAudioSessionIdChanged(audioSessionId)
    }

    private companion object {
        const val TAG = "AudioGuard"
        /** The native code for AudioTrack.ERROR_DEAD_OBJECT, which some devices return (see Media3's AudioTrackAudioOutput). */
        const val ERROR_NATIVE_DEAD_OBJECT = -32
    }
}
