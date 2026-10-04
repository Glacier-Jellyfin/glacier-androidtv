package io.github.glacier_jellyfin.androidtv.core.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeIdTest {

    @Test
    fun `reads the common link forms`() {
        assertEquals("dQw4w9WgXcQ", youTubeId("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", youTubeId("http://youtube.com/watch?feature=share&v=dQw4w9WgXcQ&t=10"))
        assertEquals("dQw4w9WgXcQ", youTubeId("https://m.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", youTubeId("https://youtu.be/dQw4w9WgXcQ?si=abc"))
        assertEquals("dQw4w9WgXcQ", youTubeId("https://www.youtube.com/embed/dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", youTubeId("https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ"))
        assertEquals("dQw4w9WgXcQ", youTubeId("https://youtube.com/shorts/dQw4w9WgXcQ"))
    }

    @Test
    fun `rejects other links and broken ids`() {
        assertNull(youTubeId("https://vimeo.com/123456"))
        assertNull(youTubeId("https://www.youtube.com/watch?v=short"))
        assertNull(youTubeId("https://www.youtube.com/channel/UC1234567890"))
        assertNull(youTubeId("not a url"))
        assertNull(youTubeId(""))
    }

    @Test
    fun `ids that could break out of the player script are refused`() {
        assertFalse(isYouTubeId("abc');alert(1)//"))
        assertFalse(isYouTubeId("dQw4w9WgXc'"))
        assertTrue(isYouTubeId("dQw4w9WgXcQ"))
        assertThrows(IllegalArgumentException::class.java) { YouTubeTrailer("x'+evil+'x", null) }
    }
}
