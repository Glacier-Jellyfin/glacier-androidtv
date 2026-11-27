package io.github.glacier_jellyfin.androidtv.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoRotationTest {
    @Test
    fun uprightVideosStayOnTheDirectPath() {
        for (degrees in listOf(null, 0, 360, 45)) {
            assertFalse("$degrees", VideoRotation.appliedByApp(degrees))
            assertEquals("$degrees", 0, VideoRotation.normalized(degrees))
        }
    }

    @Test
    fun turnedVideosAreRotatedByTheApp() {
        val expected = mapOf(90 to 90, 180 to 180, 270 to 270, -90 to 270, 450 to 90)
        for ((degrees, normalized) in expected) {
            assertTrue("$degrees", VideoRotation.appliedByApp(degrees))
            assertEquals("$degrees", normalized, VideoRotation.normalized(degrees))
        }
    }

    @Test
    fun portraitAspectFollowsTheRotation() {
        assertEquals(0.5625f, VideoRotation.portraitAspect(1080, 1920, 0)!!, 0.001f)
        assertEquals(0.5625f, VideoRotation.portraitAspect(1920, 1080, 90)!!, 0.001f)
        assertNull(VideoRotation.portraitAspect(1920, 1080, 0))
        assertNull(VideoRotation.portraitAspect(1080, 1920, 270))
        assertNull(VideoRotation.portraitAspect(null, 1920, 0))
    }
}
