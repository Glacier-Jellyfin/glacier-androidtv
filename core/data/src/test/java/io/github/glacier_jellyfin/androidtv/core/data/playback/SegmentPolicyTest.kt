package io.github.glacier_jellyfin.androidtv.core.data.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SegmentPolicyTest {

    private val recap = MediaSegment(SegmentKind.Recap, 2_000, 46_000)
    private val intro = MediaSegment(SegmentKind.Intro, 50_000, 138_000)
    private val ad = MediaSegment(SegmentKind.Commercial, 1_320_000, 1_380_000)
    private val outro = MediaSegment(SegmentKind.Outro, 2_680_000, 2_832_000)
    private val segments = listOf(recap, intro, ad, outro)
    private val policy = SegmentPolicy()

    @Test
    fun `finds the segment at the position`() {
        assertEquals(intro, policy.active(segments, 50_000))
        assertEquals(intro, policy.active(segments, 137_999))
        assertNull(policy.active(segments, 138_000))
        assertNull(policy.active(segments, 48_000))
    }

    @Test
    fun `ignores segments set to no action`() {
        assertNull(policy.active(segments, 1_350_000))
    }

    @Test
    fun `up next with credits starts at the outro`() {
        assertEquals(2_680_000L, policy.upNextAtMs(segments, 2_832_000))
    }

    @Test
    fun `up next without an outro falls back to 30 seconds before the end`() {
        assertEquals(2_802_000L, policy.upNextAtMs(listOf(intro), 2_832_000))
    }

    @Test
    fun `up next honours a fixed lead time and off`() {
        assertEquals(2_822_000L, policy.copy(upNext = UpNextMode.Before(10_000)).upNextAtMs(segments, 2_832_000))
        assertNull(policy.copy(upNext = UpNextMode.Off).upNextAtMs(segments, 2_832_000))
        assertNull(policy.upNextAtMs(segments, 0))
    }
}
