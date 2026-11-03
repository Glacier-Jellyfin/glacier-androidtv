package io.github.glacier_jellyfin.androidtv.core.player

import android.graphics.ImageFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the whole playback check on a device or emulator and prints what it
 * found: `./gradlew :core:player:connectedDebugAndroidTest`. Only formats
 * every Android TV device plays are required to pass.
 *
 * The setup comes from instrumentation arguments, to try what the app would
 * pick on a device: `-Pandroid.testInstrumentationRunnerArguments.profile7=Profile81`
 * (AsIs, Profile81, BaseLayer; BaseLayer if not given) and `…iec61937=true`.
 */
@RunWith(AndroidJUnit4::class)
class PlaybackCheckTest {

    @Test
    fun everySamplePlaysOrSaysWhy() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val arguments = InstrumentationRegistry.getArguments()
        val setup = PlaybackSetup(
            dolbyVisionProfile7 = DolbyVisionProfile7.valueOf(arguments.getString("profile7") ?: DolbyVisionProfile7.BaseLayer.name),
            iec61937 = arguments.getString("iec61937").toBoolean(),
        )
        println("PlaybackCheckTest: $setup")
        val check = PlaybackCheck(context, setup)
        // The decoders render to an offscreen surface, as to the dialog's hidden one; each frame is dropped at once.
        val thread = HandlerThread("PlaybackCheckTest").apply { start() }
        val reader = ImageReader.newInstance(640, 360, ImageFormat.PRIVATE, 4)
        reader.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, Handler(thread.looper))
        val results = PlaybackCheck.SAMPLES.map { sample ->
            check.play(sample) { playback -> playback?.player?.setVideoSurface(reader.surface) }
        }
        reader.close()
        thread.quitSafely()
        results.forEach { println("PlaybackCheckTest: ${it.describe()}") }

        assertEquals(PlaybackCheck.SAMPLES.size, results.size)
        for (label in listOf("H.264 High", "AAC stereo")) {
            assertEquals(label, CheckResult.Outcome.Plays, results.single { it.sample.label == label }.outcome)
        }
    }
}
