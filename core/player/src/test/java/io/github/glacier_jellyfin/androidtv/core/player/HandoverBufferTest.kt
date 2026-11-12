package io.github.glacier_jellyfin.androidtv.core.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.test.utils.FakeRenderer
import androidx.media3.test.utils.TestExoPlayerBuilder
import androidx.media3.test.utils.robolectric.TestPlayerRunHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.peerless2012.ass.media.AssHandler
import io.github.peerless2012.ass.media.type.AssRenderType
import java.io.File
import java.io.InterruptedIOException
import java.util.concurrent.atomic.AtomicLong
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * [GlacierPlayback.handOver] on Media3's own machinery: real sources read a small MKV (90 s, one keyframe at the start, so a
 * seek to the middle cannot be served from a buffer that has dropped what lies behind the position) as the stream that plays,
 * and a small HLS stream (6 s segments, 60 s) as the converted one; the data source records what is asked of it. A hand over
 * must not make the playing stream load again, and the stream that was loaded ahead must be used as it is.
 *
 * Why the end of the playing item is not moved: Media3's `MediaPeriodQueue.updateQueuedPeriods` seeks to the playing
 * position (`ExoPlayerImplInternal.seekToCurrentPosition`, renderers disabled) when the duration of the playing period
 * changes and a renderer has read up to the new end, which the renderer of a sparse track does with its next cue. Without a
 * keyframe at the position `ProgressiveMediaPeriod.seekInsideBufferUs` fails, the buffer is dropped and the file is read again
 * from the nearest keyframe (checked with this setup: the state falls to buffering with nothing ahead, and the file is opened
 * again). In these tests the audio renderer stands for such a renderer: it reads 20 s ahead, beyond the hand over point.
 *
 * The app finishes a hand over (as [finishHandOver] is called here) in the player's callback for the move to the next item,
 * which is how the loaded period was lost: see [GlacierPlayback.enterNext].
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class HandoverBufferTest {
    private val opens = java.util.Collections.synchronizedList(mutableListOf<String>())

    /** The link: nothing arrives beyond this many bytes of the main load (the stream outruns the connection). */
    private val limit = AtomicLong(200_000)

    /** The bytes of a segment that arrive while [holdSegments] is set: the server is still making it. */
    @Volatile
    private var segmentHoldAt = 0L

    @Volatile
    private var holdSegments = false

    /** How far into each file the main load has read. */
    private val readTo = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private lateinit var player: ExoPlayer
    private lateinit var playback: GlacierPlayback
    private lateinit var loadControl: HandoverLoadControl
    private lateinit var first: File
    private lateinit var second: File
    private lateinit var hlsDir: File
    private val transitions = mutableListOf<String>()

    @Before
    fun setUp() {
        val dir = File(System.getProperty("java.io.tmpdir"), "handover-test-${System.nanoTime()}").apply { mkdirs() }
        val source = javaClass.classLoader!!.getResourceAsStream("handover-sample.mkv")!!.readBytes()
        first = File(dir, "first.mkv").apply { writeBytes(source) }
        second = File(dir, "second.mkv").apply { writeBytes(source) }
        hlsDir = File(dir, "hls").apply { mkdirs() }
        for (name in listOf("index.m3u8") + (0..9).map { "seg$it.ts" }) {
            File(hlsDir, name).writeBytes(javaClass.classLoader!!.getResourceAsStream("handover-hls/$name")!!.readBytes())
        }
        build()
    }

    @After
    fun tearDown() {
        player.release()
    }

    /** A renderer that has read far ahead of the position, as one of a sparse track does with its next cue. */
    private class AheadRenderer(type: Int) : FakeRenderer(type) {
        override fun shouldProcessBuffer(bufferTimeUs: Long, playbackPositionUs: Long) = bufferTimeUs < playbackPositionUs + 20_000_000
    }

    /** Files are read as they are (the stream that plays), playlists as HLS (the converted stream). */
    private class FileOrPlaylist(data: DataSource.Factory) : MediaSource.Factory {
        private val progressive = ProgressiveMediaSource.Factory(data).setContinueLoadingCheckIntervalBytes(16 * 1024)
        private val hls = HlsMediaSource.Factory(data)

        override fun getSupportedTypes() = intArrayOf(C.CONTENT_TYPE_OTHER, C.CONTENT_TYPE_HLS)

        override fun setDrmSessionManagerProvider(drmSessionManagerProvider: DrmSessionManagerProvider) = apply {
            progressive.setDrmSessionManagerProvider(drmSessionManagerProvider)
            hls.setDrmSessionManagerProvider(drmSessionManagerProvider)
        }

        override fun setLoadErrorHandlingPolicy(loadErrorHandlingPolicy: LoadErrorHandlingPolicy) = apply {
            progressive.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
            hls.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
        }

        override fun createMediaSource(mediaItem: MediaItem): MediaSource =
            if (mediaItem.localConfiguration?.mimeType == MimeTypes.APPLICATION_M3U8) hls.createMediaSource(mediaItem) else progressive.createMediaSource(mediaItem)
    }

    private fun build() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val recording = DataSource.Factory {
            val file = DefaultDataSource.Factory(context).createDataSource()
            object : DataSource {
                private var position = 0L
                private var main = false
                private var segment = false
                private var segmentRead = 0L
                private var name: String? = null

                override fun addTransferListener(transferListener: TransferListener) = file.addTransferListener(transferListener)

                override fun open(dataSpec: DataSpec): Long {
                    // The playlist of the converted stream is a data address: nothing to record.
                    name = dataSpec.uri.path?.let { File(it).name }
                    val name = name
                    if (name != null) opens += "$name@${dataSpec.position}"
                    position = dataSpec.position
                    segmentRead = 0
                    // The first opens read the header and the cues at the end; only the load from the start is slow.
                    main = name?.endsWith(".mkv") == true && position in 1..100_000
                    segment = name?.endsWith(".ts") == true
                    return file.open(dataSpec)
                }

                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    while ((main && position >= limit.get()) || (segment && holdSegments && segmentRead >= segmentHoldAt)) {
                        try {
                            Thread.sleep(2)
                        } catch (e: InterruptedException) {
                            throw InterruptedIOException()
                        }
                    }
                    val read = file.read(buffer, offset, if (main) minOf(length.toLong(), limit.get() - position).toInt() else length)
                    if (read > 0) {
                        position += read
                        segmentRead += read
                        if (main) readTo.merge(name.orEmpty(), position, ::maxOf)
                    }
                    return read
                }

                override fun getUri() = file.uri
                override fun getResponseHeaders() = file.responseHeaders
                override fun close() = file.close()
            }
        }
        loadControl = HandoverLoadControl(
            minBufferMs = 80_000,
            maxBufferMs = 80_000,
            bufferForPlaybackMs = 1_000,
            bufferForPlaybackAfterRebufferMs = 1_000,
            targetBufferBytes = C.LENGTH_UNSET,
        )
        player = TestExoPlayerBuilder(context)
            .setMediaSourceFactory(GlacierPlayer.ClippedSources(FileOrPlaylist(recording)))
            .setLoadControl(loadControl)
            .setRenderers(FakeRenderer(C.TRACK_TYPE_VIDEO), AheadRenderer(C.TRACK_TYPE_AUDIO))
            .build()
        val item = GlacierPlayer.titleItem(request(first), "title", 0)
        playback = GlacierPlayback(
            player,
            AssHandler(AssRenderType.OVERLAY_OPEN_GL),
            SteeredBandwidthMeter(DefaultBandwidthMeter.Builder(context).build()),
            current = item,
            loadControl = loadControl,
        )
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                transitions += "${mediaItem?.mediaId}/$reason"
                // As the app does: in the callback, while the player is still on its way to the next item.
                if (mediaItem?.mediaId == playback.handOverId) playback.finishHandOver()
            }
        })
        player.setMediaItem(item)
        player.prepare()
    }

    private fun request(file: File) = StreamRequest(url = Uri.fromFile(file).toString(), isHls = false, headers = emptyMap(), startPositionMs = 0, title = true)

    /** The converted stream: the playlist of one tier, as the app builds it, with the address of the files. */
    private fun hlsRequest(): StreamRequest {
        val tier = "file:///" + File(hlsDir, "index.m3u8").path.replace(File.separatorChar, '/').trimStart('/')
        val playlist = "#EXTM3U\n#EXT-X-INDEPENDENT-SEGMENTS\n" +
            "#EXT-X-STREAM-INF:BANDWIDTH=60000,RESOLUTION=160x90,CODECS=\"avc1.42c01e,mp4a.40.2\"\n$tier\n"
        return StreamRequest(url = tier, isHls = true, headers = emptyMap(), startPositionMs = 0, title = true, playlist = playlist, startBitrate = 60_000)
    }

    private fun opened(name: String) = synchronized(opens) { opens.filter { it.startsWith(name) } }

    /**
     * The next stream is not only prepared but loading from the hand over point: its file was opened for the header, for the
     * cues at the end, for the start of the data, and again at the keyframe before the position where it takes over.
     */
    private fun loadingFromTheHandOverPoint() = opened("second").size >= 4

    /**
     * Waits for the player to be ready, in the time of the threads that load (not of the test clock, which runs on while they
     * work and ends a wait for loading on a busy machine as a player that is stuck).
     */
    private fun awaitReady() {
        TestPlayerRunHelper.advance(player).untilBackgroundThreadCondition { player.playbackState == Player.STATE_READY }
    }

    /** Plays to 10 s and lets the link fill the buffer until about 35 s: more than the 25 s where the hand over is to be. */
    private fun playAndBuffer() {
        awaitReady()
        player.playWhenReady = true
        TestPlayerRunHelper.playUntilPosition(player, 0, 10_000)
        player.pause()
        TestPlayerRunHelper.advance(player).untilBackgroundThreadCondition { player.bufferedPosition >= 35_000 }
        assertEquals(Player.STATE_READY, player.playbackState)
    }

    /** Lets the player's playback thread run for a moment of real time, then hands its state to the test. */
    private fun settle(ms: Long = 400) {
        Thread.sleep(ms)
        TestPlayerRunHelper.runUntilPendingCommandsAreFullyHandled(player)
    }

    /** Plays until the hand over has been completed (the app's callback has run). */
    private fun playUntilHandedOver() {
        player.play()
        TestPlayerRunHelper.advance(player).untilBackgroundThreadCondition { playback.handOverId == null }
        TestPlayerRunHelper.runUntilPendingCommandsAreFullyHandled(player)
    }

    @Test
    fun theBufferOfThePlayingStreamSurvivesTheHandOver() {
        playAndBuffer()
        val filesBefore = opened("first")
        val bufferedBefore = player.bufferedPosition

        assertTrue(playback.handOver(25_000, request(second)))
        TestPlayerRunHelper.runUntilPendingCommandsAreFullyHandled(player)
        // The next stream loads ahead while the playing one is still loading.
        TestPlayerRunHelper.advance(player).untilBackgroundThreadCondition { opened("second").isNotEmpty() }

        assertEquals("ready, not buffering", Player.STATE_READY, player.playbackState)
        assertEquals("the playing stream is not read again", filesBefore, opened("first"))
        assertTrue("the buffer is still there: $bufferedBefore -> ${player.bufferedPosition}", player.bufferedPosition >= bufferedBefore)
        assertEquals(2, player.mediaItemCount)
        assertEquals(0, player.currentMediaItemIndex)
    }

    @Test
    fun thePlayerMovesOnAtTheHandOverPointWithoutLoadingAgain() {
        playAndBuffer()
        val filesBefore = opened("first")
        assertTrue(playback.handOver(25_000, request(second)))
        TestPlayerRunHelper.advance(player).untilBackgroundThreadCondition { loadingFromTheHandOverPoint() }
        val secondBefore = opened("second")

        // The preloaded file has 12 s: the move is not postponed.
        player.play()
        TestPlayerRunHelper.advance(player).untilBackgroundThreadCondition { transitions.any { it.startsWith("handover-1") } }
        TestPlayerRunHelper.runUntilPendingCommandsAreFullyHandled(player)

        assertEquals("handover-1", player.currentMediaItem?.mediaId)
        assertEquals("handover-1/${Player.MEDIA_ITEM_TRANSITION_REASON_SEEK}", transitions.last())
        assertTrue("the next stream starts at its beginning, was ${player.currentPosition}", player.currentPosition < 1_000)
        assertEquals("the playing stream was not read again", filesBefore, opened("first"))
        assertEquals("the preloaded stream is used as it is", secondBefore, opened("second"))

        // The old stream is let go once the player has taken the next over.
        assertEquals(1, player.mediaItemCount)
        assertEquals(0, player.currentMediaItemIndex)
        assertNull(playback.handOverId)
    }

    @Test
    fun aCancelledHandOverLeavesThePlayingStreamAlone() {
        playAndBuffer()
        val filesBefore = opened("first")
        assertTrue(playback.handOver(25_000, request(second)))
        TestPlayerRunHelper.runUntilPendingCommandsAreFullyHandled(player)

        playback.cancelHandOver()
        TestPlayerRunHelper.runUntilPendingCommandsAreFullyHandled(player)
        assertEquals(1, player.mediaItemCount)
        assertEquals(Player.STATE_READY, player.playbackState)

        // Past the point where it was to happen: no move, no reading again.
        player.play()
        TestPlayerRunHelper.playUntilPosition(player, 0, 27_000)
        assertEquals(0, player.currentMediaItemIndex)
        assertEquals("title", player.currentMediaItem?.mediaId)
        assertEquals(filesBefore, opened("first"))
    }

    @Test
    fun thePlayingStreamLoadsNoFurtherThanTenSecondsBeyondTheHandOverPoint() {
        // The link carries about 23 s of the file when the hand over is asked for; then it opens up.
        limit.set(100_000)
        awaitReady()
        player.playWhenReady = true
        TestPlayerRunHelper.playUntilPosition(player, 0, 10_000)
        player.pause()
        TestPlayerRunHelper.advance(player).untilBackgroundThreadCondition { player.bufferedPosition >= 20_000 }
        assertTrue(playback.handOver(20_000, request(second)))
        limit.set(400_000)

        // On to just before the hand over point: the playing stream would load all of the file in that time.
        player.play()
        TestPlayerRunHelper.playUntilPosition(player, 0, 19_000)
        TestPlayerRunHelper.advance(player).untilBackgroundThreadCondition { loadingFromTheHandOverPoint() }

        // 30 s is where loading stops (the file is 4.2 KB per second, 127 KB); it checks every 16 KB, and the link would carry
        // all 380 KB.
        val loaded = readTo["first.mkv"] ?: 0
        assertTrue("the playing stream has read to $loaded bytes", loaded in 100_000..150_000)
        assertTrue("the next stream has the connection: ${opened("second")}", loadingFromTheHandOverPoint())
    }

    @Test
    fun aPreloadThatIsStillRunningAtTheMoveIsNotStartedAgain() {
        // Only 26 s of the playing stream: no room to wait for the next, the move is at the hand over point.
        limit.set(110_000)
        awaitReady()
        player.playWhenReady = true
        TestPlayerRunHelper.playUntilPosition(player, 0, 10_000)
        player.pause()
        TestPlayerRunHelper.advance(player).untilBackgroundThreadCondition { player.bufferedPosition >= 20_000 }
        settle()
        assertTrue("less than 27.5 s buffered, was ${player.bufferedPosition}", player.bufferedPosition < 27_500)

        // The server is still making the first segment: a part of it has arrived.
        holdSegments = true
        segmentHoldAt = 20_000
        assertTrue(playback.handOver(25_000, hlsRequest()))
        TestPlayerRunHelper.advance(player).untilBackgroundThreadCondition { opened("seg4").isNotEmpty() }
        settle()
        assertEquals("the segment is being loaded", 1, opened("seg4").size)

        player.play()
        TestPlayerRunHelper.advance(player).untilBackgroundThreadCondition { transitions.any { it.startsWith("handover-1") } }
        TestPlayerRunHelper.runUntilPendingCommandsAreFullyHandled(player)
        settle()
        holdSegments = false
        awaitReady()

        assertEquals("the move was at the hand over point", "handover-1", player.currentMediaItem?.mediaId)
        assertTrue("the next stream plays from its start, was ${player.currentPosition}", player.currentPosition < 1_000)
        assertEquals("the segment was loaded once: ${synchronized(opens) { opens.toList() }}", 1, opened("seg4").size)
    }

    @Test
    fun aPreloadThatIsDoneAtTheMoveIsUsedAsItIs() {
        playAndBuffer()
        assertTrue(playback.handOver(25_000, hlsRequest()))
        TestPlayerRunHelper.advance(player).untilBackgroundThreadCondition { opened("seg6").isNotEmpty() }
        settle()
        val segmentsBefore = opened("seg")

        playUntilHandedOver()
        awaitReady()

        assertEquals("handover-1", player.currentMediaItem?.mediaId)
        // What was loaded ahead is not asked for again; only what follows it is.
        assertEquals(segmentsBefore, opened("seg").take(segmentsBefore.size))
        assertEquals(1, opened("seg4").size)
    }

    @Test
    fun theLoadedAmountOfTheNextStreamFollowsItsCompletedSegments() {
        val ends = java.util.Collections.synchronizedList(mutableListOf<Long>())
        player.addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
            override fun onLoadCompleted(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                loadEventInfo: androidx.media3.exoplayer.source.LoadEventInfo,
                mediaLoadData: androidx.media3.exoplayer.source.MediaLoadData,
            ) {
                if (eventTime.windowIndex == 1 && mediaLoadData.dataType == C.DATA_TYPE_MEDIA) ends += mediaLoadData.mediaEndTimeMs
            }
        })
        playAndBuffer()
        assertTrue(playback.handOver(25_000, hlsRequest()))
        TestPlayerRunHelper.advance(player).untilBackgroundThreadCondition { opened("seg6").isNotEmpty() }
        settle()
        // The end times are in time of the title (the clip starts at 25 s): three segments of 6 s are loaded, up to 42 s.
        val completed = synchronized(ends) { ends.toList() }
        val loadedToMs = completed.max() - 25_000
        // The load control was asked after the second segment only: it knows 11 s of the 17 s that are loaded.
        assertTrue("the load control lags: $completed, ${loadControl.preloadedMs}", loadControl.preloadedMs < loadedToMs)
        assertEquals("what the playback reports is what the loads completed: $completed", loadedToMs, playback.nextLoadedMs)
    }

    @Test
    fun theMoveWaitsWhileTheNextStreamHasHardlyLoadedAndThePlayingOneHasData() {
        playAndBuffer()
        // The server is still making the first segment: nothing has arrived.
        holdSegments = true
        segmentHoldAt = 0
        assertTrue(playback.handOver(25_000, hlsRequest()))
        TestPlayerRunHelper.advance(player).untilBackgroundThreadCondition { opened("seg4").isNotEmpty() }

        // Past the hand over point, still on the playing stream, and not stalled.
        player.play()
        TestPlayerRunHelper.playUntilPosition(player, 0, 27_500)
        assertEquals("title", player.currentMediaItem?.mediaId)
        assertEquals(Player.STATE_READY, player.playbackState)
        assertEquals("handover-1", playback.handOverId)

        // The segment arrives: at the next step the next stream has what it needs, and the move is made, to where the playing
        // stream is, without loading what the next stream already has.
        holdSegments = false
        TestPlayerRunHelper.advance(player).untilBackgroundThreadCondition { opened("seg6").isNotEmpty() }
        settle()
        val segmentsBefore = opened("seg")
        playUntilHandedOver()
        awaitReady()

        assertEquals("handover-1", player.currentMediaItem?.mediaId)
        val position = player.currentPosition
        assertTrue("on the position of the playing stream (between 2 s and 6 s past the point), was $position", position in 2_000..6_500)
        assertEquals("the loaded segments are not loaded again: ${synchronized(opens) { opens.toList() }}", segmentsBefore, opened("seg").take(segmentsBefore.size))
        assertEquals(1, opened("seg4").size)
    }

    @Test
    fun theMoveWaitsTenSecondsAtMostAndThenGoesOn() {
        playAndBuffer()
        holdSegments = true
        segmentHoldAt = 0
        assertTrue(playback.handOver(25_000, hlsRequest()))
        TestPlayerRunHelper.advance(player).untilBackgroundThreadCondition { opened("seg4").isNotEmpty() }

        player.play()
        TestPlayerRunHelper.playUntilPosition(player, 0, 34_000)
        assertEquals("still waiting at 34 s", "title", player.currentMediaItem?.mediaId)

        playUntilHandedOver()
        assertEquals("handover-1", player.currentMediaItem?.mediaId)
        // The playing stream had nothing beyond 35 s: the next one takes over there, nothing loaded, and waits for its data.
        assertTrue("took over at 35 s of the title (10 s into the next stream), was ${player.currentPosition}", player.currentPosition in 10_000..11_500)
    }
}
