package io.github.glacier_jellyfin.androidtv.diagnostics

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioCapabilities
import io.github.glacier_jellyfin.androidtv.core.jellyfin.playback.DeviceCapabilities
import io.github.glacier_jellyfin.androidtv.core.jellyfin.playback.VideoCodecSupport
import java.lang.reflect.Modifier

/**
 * What the device decodes and where its sound goes, in detail, for the top of
 * a bug report: enough to see why a format fails without asking for more.
 */
internal object DeviceReport {

    /** One line per decoder of the formats whose profiles matter, e.g. "  video/hevc OMX.x (hardware): Main, Main10; up to MainTierLevel51, 4096x2304". */
    fun videoDecoderProfiles(): List<String> {
        val decoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { !it.isEncoder && !it.name.endsWith(".secure") }
        return PROFILED_VIDEO.flatMap { (mime, names) ->
            decoders.filter { info -> info.supportedTypes.any { it.equals(mime, ignoreCase = true) } }
                .map { info -> "  $mime ${info.name}${info.kind()}: ${describe(info.getCapabilitiesForType(mime), names)}" }
        }
    }

    /** The audio outputs Android reports, with the encodings each takes. */
    fun audioOutputs(context: Context): List<String> {
        val manager = context.getSystemService(AudioManager::class.java) ?: return emptyList()
        return manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { device ->
            val encodings = device.encodings.map { ENCODINGS[it] ?: "encoding $it" }.ifEmpty { listOf("any") }
            val channels = device.channelCounts.maxOrNull()?.toString() ?: "any"
            "  ${DEVICE_TYPES[device.type] ?: "type ${device.type}"} \"${device.productName}\": ${encodings.joinToString()}, channels $channels"
        }
    }

    /** How Media3 sees the output: the bitstreams it passes through and the channels it allows. */
    @OptIn(UnstableApi::class)
    fun media3Audio(context: Context): String =
        runCatching { AudioCapabilities.getCapabilities(context, AudioAttributes.DEFAULT, null).toString() }
            .getOrElse { "unknown (${it.javaClass.simpleName})" }

    /** What the device profile sent to the server is built from. */
    fun capabilities(device: DeviceCapabilities): List<String> = buildList {
        val video = listOfNotNull(
            device.h264?.let { "H.264 ${it.text()}" },
            device.hevc?.let { "HEVC ${it.text()}" },
            device.av1?.let { "AV1 ${it.text()}" },
            device.vp9?.let { "VP9 ${it.text()}" },
        ) + device.otherVideoCodecs.sorted()
        add("  Video: ${video.joinToString()}")
        add("  HDR: ${device.hdr.joinToString { it.name }.ifEmpty { "none" }}")
        add("  Dolby Vision profiles: ${device.dolbyVisionProfiles?.sorted()?.joinToString() ?: "not declared"}")
        add("  Dolby Vision profile 7: ${if (device.decodesDolbyVisionProfile7) "Dolby Vision decoder" else "HDR10 base layer"}")
        add("  Audio: ${device.audioCodecs.sorted().joinToString()}")
        add("  Passthrough: ${device.passthroughAudio.sorted().joinToString().ifEmpty { "none" }}")
        add("  Audio channels: ${device.maxAudioChannels}")
    }

    private fun VideoCodecSupport.text() =
        "${maxWidth}x$maxHeight" + (maxLevel?.let { " level $it" } ?: "") + if (tenBit) " 10-bit" else ""

    private fun MediaCodecInfo.kind(): String = when {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> ""
        isHardwareAccelerated -> " (hardware)"
        else -> " (software)"
    }

    private fun describe(caps: MediaCodecInfo.CodecCapabilities, names: ConstantNames): String {
        val profiles = caps.profileLevels.map { it.profile }.distinct().sorted().map { names.profile(it) }
        val level = caps.profileLevels.maxOfOrNull { it.level }?.let { names.level(it) }
        val size = caps.videoCapabilities?.let { "${it.supportedWidths.upper}x${it.supportedHeights.upper}" }
        return listOfNotNull(
            profiles.joinToString().ifEmpty { "no profiles declared" },
            level?.let { "up to $it" },
            size,
        ).joinToString("; ")
    }

    /** Names of CodecProfileLevel constants, read from the platform so new ones show up by name. */
    private class ConstantNames(codec: String, private val numbered: Boolean = false) {
        // "HEVCProfileMain10" → "Main10"; "HEVCHighTierLevel51" → "HighTierLevel51", "AVCLevel51" → "Level51".
        // Profiles and levels share values (AVCLevel62 and AVCProfileConstrainedBaseline are both 0x10000), so map them apart.
        private val profiles = constants(codec + "Profile") { true }
        private val levels = constants(codec) { "Level" in it && !it.startsWith("Profile") }

        fun profile(value: Int): String {
            // VP9's profiles are plain numbers: "Profile2" reads better than "2".
            val name = profiles[value]?.let { if (it.first().isDigit()) "Profile$it" else it } ?: "profile $value"
            // Dolby Vision profiles are one bit each: DvheStn is profile 5.
            return if (numbered) "$name (${Integer.numberOfTrailingZeros(value)})" else name
        }

        fun level(value: Int): String = levels[value] ?: "level $value"

        private fun constants(prefix: String, keep: (String) -> Boolean): Map<Int, String> =
            CodecProfileLevel::class.java.fields
                .filter { Modifier.isStatic(it.modifiers) && it.type == Int::class.javaPrimitiveType && it.name.startsWith(prefix) }
                .map { it.getInt(null) to it.name.removePrefix(prefix) }
                .filter { keep(it.second) }
                .toMap()
    }

    private val PROFILED_VIDEO = listOf(
        "video/avc" to ConstantNames("AVC"),
        "video/hevc" to ConstantNames("HEVC"),
        "video/av01" to ConstantNames("AV1"),
        "video/x-vnd.on2.vp9" to ConstantNames("VP9"),
        "video/dolby-vision" to ConstantNames("DolbyVision", numbered = true),
    )

    /** AudioFormat.ENCODING_* by value, e.g. "E_AC3_JOC". */
    private val ENCODINGS: Map<Int, String> =
        AudioFormat::class.java.fields
            .filter { Modifier.isStatic(it.modifiers) && it.type == Int::class.javaPrimitiveType && it.name.startsWith("ENCODING_") }
            .associate { it.getInt(null) to it.name.removePrefix("ENCODING_") }

    /** AudioDeviceInfo.TYPE_* by value, e.g. "HDMI_EARC". */
    private val DEVICE_TYPES: Map<Int, String> =
        AudioDeviceInfo::class.java.fields
            .filter { Modifier.isStatic(it.modifiers) && it.type == Int::class.javaPrimitiveType && it.name.startsWith("TYPE_") }
            .associate { it.getInt(null) to it.name.removePrefix("TYPE_") }
}
