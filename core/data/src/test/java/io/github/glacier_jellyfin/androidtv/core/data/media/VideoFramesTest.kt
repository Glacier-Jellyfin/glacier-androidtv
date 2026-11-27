package io.github.glacier_jellyfin.androidtv.core.data.media

import org.jellyfin.sdk.model.api.BaseItemKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoFramesTest {
    @Test
    fun `a frame survives the round trip`() {
        val url = "https://tv.example/Videos/abc/stream?static=true&mediaSourceId=abc"
        val text = VideoFrames.encode(url, 12_345)
        assertTrue(VideoFrames.isFrame(text))
        assertEquals(VideoFrames.Frame(url, 12_345), VideoFrames.decode(text))
    }

    @Test
    fun `plain URLs and malformed strings are no frames`() {
        assertNull(VideoFrames.decode("https://tv.example/Items/1/Images/Primary"))
        assertNull(VideoFrames.decode(null))
        assertNull(VideoFrames.decode("glacier-frame:abc:https://x"))
        assertNull(VideoFrames.decode("glacier-frame:5:"))
        assertFalse(VideoFrames.isFrame("https://tv.example/a"))
    }

    @Test
    fun `only a video without a primary image needs a frame`() {
        assertTrue(VideoFrames.needsFrame(BaseItemKind.VIDEO, null))
        assertTrue(VideoFrames.needsFrame(BaseItemKind.VIDEO, " "))
        assertFalse(VideoFrames.needsFrame(BaseItemKind.VIDEO, "tag"))
        assertFalse(VideoFrames.needsFrame(BaseItemKind.EPISODE, null))
        assertFalse(VideoFrames.needsFrame(BaseItemKind.MOVIE, null))
        assertFalse(VideoFrames.needsFrame(null, null))
    }

    @Test
    fun `the position is a tenth in, the start when unknown, and capped`() {
        assertEquals(6_000L, VideoFrames.positionMs(60_000))
        assertEquals(0L, VideoFrames.positionMs(null))
        assertEquals(0L, VideoFrames.positionMs(0))
        assertEquals(600_000L, VideoFrames.positionMs(36_000_000))
    }
}
