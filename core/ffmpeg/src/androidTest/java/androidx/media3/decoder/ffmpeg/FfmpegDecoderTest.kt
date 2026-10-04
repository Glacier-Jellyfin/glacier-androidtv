package androidx.media3.decoder.ffmpeg

import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Plays one second of each format TV platforms commonly lack through FFmpeg
 * alone. Needs FFmpeg built into the module (scripts/build-ffmpeg.sh, or the
 * ffmpeg-android artifact of a CI run) and a device or emulator:
 * `./gradlew :core:ffmpeg:connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class FfmpegDecoderTest {

    @Test
    fun libraryIsBuiltIn() {
        assertTrue("FFmpeg is not built into the module", FfmpegLibrary.isAvailable())
    }

    @Test
    fun dts() = play("dts.mka")

    @Test
    fun trueHd() = play("truehd.mka")

    @Test
    fun ac3() = play("ac3.mka")

    @Test
    fun eac3() = play("eac3.mka")

    private fun play(asset: String) {
        assertTrue("FFmpeg is not built into the module", FfmpegLibrary.isAvailable())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val ended = CountDownLatch(1)
        var decoder: String? = null
        var error: PlaybackException? = null
        lateinit var player: ExoPlayer

        // Only the FFmpeg renderer: the platform cannot step in for a format FFmpeg fails on.
        val renderers = RenderersFactory { handler, _, audioListener, _, _ -> arrayOf(FfmpegAudioRenderer(handler, audioListener)) }
        val main = Handler(Looper.getMainLooper())
        main.post {
            player = ExoPlayer.Builder(context, renderers).build()
            player.addAnalyticsListener(object : AnalyticsListener {
                override fun onAudioDecoderInitialized(
                    eventTime: AnalyticsListener.EventTime,
                    decoderName: String,
                    initializedTimestampMs: Long,
                    initializationDurationMs: Long,
                ) {
                    decoder = decoderName
                }
            })
            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) ended.countDown()
                }

                override fun onPlayerError(e: PlaybackException) {
                    error = e
                    ended.countDown()
                }
            })
            player.volume = 0f
            player.setMediaItem(MediaItem.fromUri("asset:///$asset"))
            player.prepare()
            player.play()
        }
        try {
            assertTrue("$asset did not finish playing", ended.await(TIMEOUT_S, TimeUnit.SECONDS))
            assertNull("$asset failed: ${error?.errorCodeName}", error)
            val version = FfmpegLibrary.getVersion()
            assertTrue("$asset was decoded by $decoder", decoder?.startsWith("ffmpeg$version") == true)
        } finally {
            main.post { player.release() }
        }
    }

    @Test
    fun libavcodecMatchesTheBuildScript() {
        // FFmpeg 8.x ships libavcodec 62. Raise together with FFMPEG_VERSION in scripts/build-ffmpeg.sh.
        val version = FfmpegLibrary.getVersion().orEmpty()
        assertTrue("Built with $version", version.startsWith("Lavc62."))
    }

    private companion object {
        const val TIMEOUT_S = 20L
    }
}
