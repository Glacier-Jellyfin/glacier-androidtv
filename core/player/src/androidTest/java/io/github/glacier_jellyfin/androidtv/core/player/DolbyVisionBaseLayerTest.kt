package io.github.glacier_jellyfin.androidtv.core.player

import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ExtractorsFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Plays files that declare Dolby Vision profile 7 (from
 * scripts/make-dolby-vision-sample.py) through the base layer path. Needs a
 * device or emulator: `./gradlew :core:player:connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class DolbyVisionBaseLayerTest {

    @Test
    fun profile7BecomesItsMain10BaseLayer() {
        // Only the tracks: the emulator has no Main 10 decoder to play it with.
        val result = play("dv-profile7.mkv", DolbyVisionBaseLayerExtractors(DefaultExtractorsFactory()), untilTracks = true)
        assertEquals(MimeTypes.VIDEO_H265, result.video?.sampleMimeType)
        // Main 10 (profile 2) at level 2.1 (L63) from the SPS, so Media3 checks the HEVC decoder for both.
        assertEquals("hvc1.2.4.L63.90", result.video?.codecs)
    }

    @Test
    fun profile7PlaysWithTheHevcDecoder() {
        val result = play("dv-profile7-8bit.mkv", DolbyVisionBaseLayerExtractors(DefaultExtractorsFactory()))
        assertNull("Playback failed: ${result.error?.errorCodeName}", result.error)
        assertEquals(MimeTypes.VIDEO_H265, result.video?.sampleMimeType)
        assertTrue("Decoded by ${result.decoder}", result.decoder?.contains("hevc", ignoreCase = true) == true)
    }

    @Test
    fun withoutTheBaseLayerPathTheFileStaysDolbyVision() {
        val result = play("dv-profile7.mkv", DefaultExtractorsFactory(), untilTracks = true)
        assertEquals(MimeTypes.VIDEO_DOLBY_VISION, result.video?.sampleMimeType)
        assertEquals("dvhe.07.06", result.video?.codecs)
    }

    private class Result(val video: Format?, val decoder: String?, val error: PlaybackException?)

    private fun play(asset: String, extractors: ExtractorsFactory, untilTracks: Boolean = false): Result {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val done = CountDownLatch(1)
        var video: Format? = null
        var decoder: String? = null
        var error: PlaybackException? = null
        lateinit var player: ExoPlayer

        val main = Handler(Looper.getMainLooper())
        main.post {
            // The emulator's goldfish HEVC decoder fails on these headers; Android's software decoder plays them.
            val decoders = MediaCodecSelector { mimeType, secure, tunneling ->
                MediaCodecUtil.getDecoderInfos(mimeType, secure, tunneling).filterNot { it.name.startsWith("c2.goldfish.") }
            }
            player = ExoPlayer.Builder(context, DefaultRenderersFactory(context).setMediaCodecSelector(decoders))
                .setMediaSourceFactory(DefaultMediaSourceFactory(context, extractors))
                .build()
            player.addAnalyticsListener(object : AnalyticsListener {
                override fun onVideoDecoderInitialized(
                    eventTime: AnalyticsListener.EventTime,
                    decoderName: String,
                    initializedTimestampMs: Long,
                    initializationDurationMs: Long,
                ) {
                    decoder = decoderName
                }
            })
            player.addListener(object : Player.Listener {
                override fun onTracksChanged(tracks: Tracks) {
                    tracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO }?.let { video = it.getTrackFormat(0) }
                    if (untilTracks && video != null) done.countDown()
                }

                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) done.countDown()
                }

                override fun onPlayerError(e: PlaybackException) {
                    error = e
                    done.countDown()
                }
            })
            player.setMediaItem(MediaItem.fromUri("asset:///$asset"))
            player.prepare()
            if (!untilTracks) player.play()
        }
        try {
            assertTrue("$asset did not finish", done.await(TIMEOUT_S, TimeUnit.SECONDS))
            return Result(video, decoder, error)
        } finally {
            main.post { player.release() }
        }
    }

    private companion object {
        const val TIMEOUT_S = 20L
    }
}
