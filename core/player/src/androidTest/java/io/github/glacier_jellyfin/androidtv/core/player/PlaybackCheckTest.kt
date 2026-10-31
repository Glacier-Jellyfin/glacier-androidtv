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
 */
@RunWith(AndroidJUnit4::class)
class PlaybackCheckTest {

    @Test
    fun everySamplePlaysOrSaysWhy() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val check = PlaybackCheck(context, dolbyVisionBaseLayer = true)
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
