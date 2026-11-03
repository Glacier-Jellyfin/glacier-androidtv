package io.github.glacier_jellyfin.androidtv.core.player

import android.content.Context
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioOutputProvider.FormatConfig
import androidx.media3.exoplayer.audio.AudioOutputProvider.FormatSupport
import androidx.media3.exoplayer.audio.AudioOutputProvider.OutputConfig
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import io.github.glacier_jellyfin.androidtv.core.log.Log
import java.nio.ByteBuffer

/**
 * Sends Dolby TrueHD and DTS-HD to HDMI as IEC 61937 bursts that Glacier packs
 * itself ([Iec61937Packer]), the way Kodi does; every other format goes
 * through Media3's own AudioTrack output. Android passes TrueHD and DTS-HD on
 * itself on many devices, but some fail on TrueHD or send only the DTS core.
 *
 * The sink still sees a TrueHD or DTS-HD output at the stream's sample rate
 * and counts its frames as for any bitstream; only the AudioTrack underneath
 * carries the bursts, as 8 channels at 192 kHz.
 */
@OptIn(UnstableApi::class)
internal class Iec61937OutputProvider private constructor(
    /** Media3's output, with [iecTrack] set while it builds the AudioTrack for bursts. */
    private val inner: AudioTrackAudioOutputProvider,
    private val iecTrack: BooleanArray,
) : AudioOutputProvider by inner {

    override fun getFormatSupport(formatConfig: FormatConfig): FormatSupport =
        if (encodingOf(formatConfig) != null) {
            FormatSupport.Builder().setFormatSupportLevel(AudioOutputProvider.FORMAT_SUPPORTED_DIRECTLY).build()
        } else {
            inner.getFormatSupport(formatConfig)
        }

    override fun getOutputConfig(formatConfig: FormatConfig): OutputConfig {
        val encoding = encodingOf(formatConfig) ?: return inner.getOutputConfig(formatConfig)
        return OutputConfig.Builder()
            .setEncoding(encoding)
            .setSampleRate(formatConfig.format.sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_7POINT1_SURROUND)
            .setBufferSize(BUFFER_SIZE)
            .setAudioSessionId(formatConfig.audioSessionId)
            .setAudioAttributes(formatConfig.audioAttributes)
            .setIsTunneling(formatConfig.enableTunneling)
            .setVirtualDeviceId(formatConfig.virtualDeviceId)
            .build()
    }

    override fun getAudioOutput(config: OutputConfig): AudioOutput {
        val packer = when (config.encoding) {
            C.ENCODING_DOLBY_TRUEHD -> Iec61937Packer.TrueHd()
            C.ENCODING_DTS_HD -> Iec61937Packer.DtsHd()
            else -> return inner.getAudioOutput(config)
        }
        // To Media3 a 16-bit PCM track of the bursts' size; the builder modifier makes it IEC 61937.
        val burstConfig = config.buildUpon()
            .setEncoding(C.ENCODING_PCM_16BIT)
            .setSampleRate(BURST_SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_7POINT1_SURROUND)
            .setBufferSize(burstBufferSize())
            .build()
        iecTrack[0] = true
        val output = try {
            inner.getAudioOutput(burstConfig)
        } finally {
            iecTrack[0] = false
        }
        Log.i(TAG, "Audio output: IEC 61937 bursts of ${GlacierPlayback.encodingName(config.encoding)}")
        return Iec61937Output(output, packer, config.sampleRate)
    }

    override fun setClock(clock: Clock) = inner.setClock(clock)

    override fun hasPendingReleases(): Boolean = inner.hasPendingReleases()

    /**
     * The bitstream this output packs itself for [formatConfig], or null for
     * Media3's own output. Only where Android would send TrueHD or DTS-HD as
     * a bitstream, so the receiver takes them: Media3 also picks DTS-HD for a
     * plain DTS track (audio/vnd.dts) when the receiver supports it.
     */
    private fun encodingOf(formatConfig: FormatConfig): Int? {
        val format = formatConfig.format
        if (format.sampleMimeType !in BITSTREAMS || format.sampleRate <= 0) return null
        if (inner.getFormatSupport(formatConfig).supportLevel != AudioOutputProvider.FORMAT_SUPPORTED_DIRECTLY) return null
        val encoding = runCatching { inner.getOutputConfig(formatConfig).encoding }.getOrNull()
        return encoding?.takeIf { it == C.ENCODING_DOLBY_TRUEHD || it == C.ENCODING_DTS_HD }
    }

    companion object {
        private const val TAG = "Iec61937"
        private const val BURST_SAMPLE_RATE = 192_000
        private val BITSTREAMS = setOf(MimeTypes.AUDIO_TRUEHD, MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD)
        /** Eight TrueHD bursts, 160 ms. */
        private const val BUFFER_SIZE = 8 * 61440

        /** [BUFFER_SIZE], or more where the platform asks for more. */
        private fun burstBufferSize(): Int =
            maxOf(BUFFER_SIZE, AudioTrack.getMinBufferSize(BURST_SAMPLE_RATE, AudioFormat.CHANNEL_OUT_7POINT1_SURROUND, AudioFormat.ENCODING_IEC61937))

        fun create(context: Context): Iec61937OutputProvider {
            val iecTrack = BooleanArray(1)
            val inner = AudioTrackAudioOutputProvider.Builder(context)
                .setAudioTrackBuilderModifier { builder, config ->
                    if (iecTrack[0]) {
                        builder.setAudioFormat(
                            AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_IEC61937)
                                .setSampleRate(config.sampleRate)
                                .setChannelMask(config.channelMask)
                                .build(),
                        )
                    }
                }
                .build()
            return Iec61937OutputProvider(inner, iecTrack)
        }
    }
}

/**
 * The bursts' AudioTrack, seen from the sink as a TrueHD or DTS-HD output at
 * [sampleRate]. Packed bursts wait in [pending] until the track takes them.
 */
@OptIn(UnstableApi::class)
private class Iec61937Output(
    private val inner: AudioOutput,
    private val packer: Iec61937Packer,
    private val sampleRate: Int,
) : AudioOutput by inner {

    private val pending = Iec61937Bursts()
    /** Stopped by the sink at the end of the stream: the rest of [pending] goes before the track stops. */
    private var stopping = false

    override fun write(buffer: ByteBuffer, encodedAccessUnitCount: Int, presentationTimeUs: Long): Boolean {
        // Bursts from earlier input first; new input waits until they are gone.
        if (!drain(presentationTimeUs)) return false
        packer.pack(buffer, pending)
        return drain(presentationTimeUs)
    }

    /** Writes what the track takes; true once nothing waits. */
    private fun drain(presentationTimeUs: Long): Boolean {
        if (pending.size == 0) return true
        val bursts = pending.buffer()
        inner.write(bursts, 0, presentationTimeUs)
        pending.consume(bursts.position())
        return pending.size == 0
    }

    override fun stop() {
        if (packer is Iec61937Packer.TrueHd) packer.finish(pending)
        stopping = true
        stopWhenDrained()
    }

    private fun stopWhenDrained() {
        if (stopping && drain(C.TIME_END_OF_SOURCE)) {
            stopping = false
            inner.stop()
        }
    }

    // The sink keeps asking for the position until the end has played: the moment to write what is left.
    override fun getPositionUs(): Long {
        stopWhenDrained()
        return inner.getPositionUs()
    }

    override fun flush() {
        pending.clear()
        packer.reset()
        stopping = false
        inner.flush()
    }

    override fun getSampleRate(): Int = sampleRate

    override fun getBufferSizeInFrames(): Long = inner.bufferSizeInFrames * sampleRate / inner.sampleRate

    override fun setPlayerId(playerId: PlayerId) = inner.setPlayerId(playerId)

    override fun canReuseAudioOutput(currentConfig: OutputConfig, newFormat: FormatConfig, newConfig: OutputConfig) = false

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) = Unit
}
