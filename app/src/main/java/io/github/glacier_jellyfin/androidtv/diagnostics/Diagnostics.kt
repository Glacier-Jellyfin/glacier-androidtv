package io.github.glacier_jellyfin.androidtv.diagnostics

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodecList
import android.os.Build
import android.view.Display
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.glacier_jellyfin.androidtv.BuildConfig
import io.github.glacier_jellyfin.androidtv.core.data.SessionManager
import io.github.glacier_jellyfin.androidtv.core.log.Log
import io.github.glacier_jellyfin.androidtv.core.player.FfmpegAudio
import io.github.glacier_jellyfin.androidtv.ui.TvPlatform
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.extensions.systemApi
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/** The app log for bug reports: a device summary on top, sent to the Jellyfin server. */
@Singleton
class Diagnostics @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessions: SessionManager,
) {

    /**
     * Uploads the log to the signed-in profile's server, where admins find it
     * in the dashboard under Logs. Returns the file name the server gave it.
     */
    suspend fun sendToServer(): String = withContext(Dispatchers.IO) {
        val session = checkNotNull(sessions.session.value) { "No one is signed in" }
        val version = try {
            session.api.systemApi.getPublicSystemInfo().content.version
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Asking the server for its version failed", e)
            null
        } ?: session.server.version
        val header = header(version)
        val report = header + "\n" + Log.read(MAX_UPLOAD_BYTES - header.encodeToByteArray().size - 1)
        val fileName = session.api.systemApi.logFile(report).content.fileName
        Log.crashReported()
        fileName
    }

    private fun header(serverVersion: String?): String = buildString {
        appendLine("Glacier ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}, ${BuildConfig.BUILD_TYPE})")
        appendLine("Created: ${ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)}")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, ${TvPlatform.of(context).name}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("ABIs: ${Build.SUPPORTED_ABIS.joinToString()}")
        appendLine("Display: ${display()}")
        appendLine("Video decoders: ${videoDecoders()}")
        appendLine("FFmpeg: ${FfmpegAudio.version() ?: "not included"}")
        appendLine("Server: Jellyfin ${serverVersion ?: "unknown"}")
    }

    private fun display(): String {
        val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY) ?: return "unknown"
        val mode = display.mode
        val hdr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            mode.supportedHdrTypes
        } else {
            @Suppress("DEPRECATION")
            display.hdrCapabilities?.supportedHdrTypes ?: IntArray(0)
        }
        val hdrNames = hdr.map { HDR_TYPES[it] ?: "type $it" }.ifEmpty { listOf("none") }
        return "${mode.physicalWidth}x${mode.physicalHeight} @ ${mode.refreshRate.roundToInt()} Hz, HDR: ${hdrNames.joinToString()}"
    }

    /** Video formats the device decodes, e.g. "video/avc, video/hevc, video/dolby-vision". */
    private fun videoDecoders(): String =
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { !it.isEncoder }
            .flatMap { it.supportedTypes.asList() }
            .filter { it.startsWith("video/") }
            .distinct()
            .sorted()
            .joinToString()

    private companion object {
        const val TAG = "Diagnostics"

        /** Jellyfin refuses client logs above 1 MB; a little room to spare. */
        const val MAX_UPLOAD_BYTES = 990_000

        // The constants are compiled in, so the API 29 one is fine on API 28.
        @SuppressLint("InlinedApi")
        val HDR_TYPES = mapOf(
            Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION to "Dolby Vision",
            Display.HdrCapabilities.HDR_TYPE_HDR10 to "HDR10",
            Display.HdrCapabilities.HDR_TYPE_HLG to "HLG",
            Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS to "HDR10+",
        )
    }
}
