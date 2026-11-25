package io.github.glacier_jellyfin.androidtv.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
