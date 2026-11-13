package io.github.glacier_jellyfin.androidtv.core.player

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@UnstableApi
class SyncPointsTest {
    /** A file with a cue point every 30 s, like the cues of a Matroska file: the points around a time, as the extractor gives them. */
    private class Cues(
        private val spacingUs: Long,
        private val durationUs: Long,
        private val seekable: Boolean = true,
    ) : SeekMap {
        override fun isSeekable() = seekable
        override fun getDurationUs() = durationUs
        override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
            val before = timeUs / spacingUs * spacingUs
            val first = SeekPoint(before, before)
            if (before == timeUs) return SeekMap.SeekPoints(first)
            val after = before + spacingUs
            return SeekMap.SeekPoints(first, SeekPoint(after, after))
        }
    }

    @Before
    fun clear() = SyncPoints.clear()

    @Test
    fun `two opens of a file have the same key whatever the session`() {
        val a = SyncPoints.keyOf(
            "http://server:8096/Videos/1234/stream?static=true&mediaSourceId=AbC&playSessionId=s1&api_key=t1",
        )
        val b = SyncPoints.keyOf(
            "http://server:8096/Videos/1234/stream?playSessionId=s2&mediaSourceId=abc&static=true&ApiKey=t2",
        )
        assertEquals(a, b)
    }

    @Test
    fun `another media source or item has another key`() {
        val a = SyncPoints.keyOf("http://server/Videos/1/stream?mediaSourceId=a")
        assertNotEquals(a, SyncPoints.keyOf("http://server/Videos/1/stream?mediaSourceId=b"))
        assertNotEquals(a, SyncPoints.keyOf("http://server/Videos/2/stream?mediaSourceId=a"))
    }

    @Test
    fun `a url without a media source has no key`() {
        assertNull(SyncPoints.keyOf("http://server/Videos/1/master.m3u8?foo=bar"))
        assertNull(SyncPoints.keyOf("http://server/Videos/1/stream"))
        assertNull(SyncPoints.keyOf("http://server/Videos/1/stream?mediaSourceId="))
    }

    @Test
    fun `the first point at or after the time is chosen`() {
        val cues = Cues(30_000_000, 7_200_000_000)
        assertEquals(3_000_000L, SyncPoints.firstAtOrAfter(cues, 2_987_776, 30_000))
        assertEquals(3_000_000L, SyncPoints.firstAtOrAfter(cues, 3_000_000, 0))
        assertEquals(3_030_000L, SyncPoints.firstAtOrAfter(cues, 3_000_001, 30_000))
    }

    @Test
    fun `a point further away than the limit is not taken`() {
        val cues = Cues(30_000_000, 7_200_000_000)
        assertNull(SyncPoints.firstAtOrAfter(cues, 2_970_001, 29_998))
        assertEquals(3_000_000L, SyncPoints.firstAtOrAfter(cues, 2_970_001, 29_999))
        assertNull(SyncPoints.firstAtOrAfter(cues, 2_987_776, -1))
    }

    @Test
    fun `a map that is not seekable or has a single point is not kept`() {
        assertFalse(SyncPoints.hasSeveralPoints(Cues(30_000_000, 600_000_000, seekable = false)))
        assertFalse(SyncPoints.hasSeveralPoints(SeekMap.Unseekable(C.TIME_UNSET)))
        val single = object : SeekMap {
            override fun isSeekable() = true
            override fun getDurationUs() = 600_000_000L
            override fun getSeekPoints(timeUs: Long) = SeekMap.SeekPoints(SeekPoint(0, 0))
        }
        assertFalse(SyncPoints.hasSeveralPoints(single))
        assertTrue(SyncPoints.hasSeveralPoints(Cues(30_000_000, 600_000_000)))
    }

    @Test
    fun `a remembered file gives its sync point to the next open`() {
        val url = "http://server/Videos/1/stream?static=true&mediaSourceId=a&playSessionId=x"
        SyncPoints.remember(SyncPoints.keyOf(url)!!, Cues(30_000_000, 7_200_000_000))
        val other = "http://server/Videos/1/stream?static=true&mediaSourceId=a&playSessionId=y"
        assertEquals(3_000_000L, SyncPoints.atOrAfter(other, 2_987_776, 30_000))
        assertNull(SyncPoints.atOrAfter("http://server/Videos/2/stream?mediaSourceId=a", 2_987_776, 30_000))
    }

    @Test
    fun `only the last few files are kept`() {
        for (i in 1..6) {
            SyncPoints.remember(
                SyncPoints.keyOf("http://s/Videos/$i/stream?mediaSourceId=a")!!,
                Cues(30_000_000, 600_000_000),
            )
        }
        assertNull(SyncPoints.atOrAfter("http://s/Videos/1/stream?mediaSourceId=a", 1_000, 60_000))
        assertEquals(30_000L, SyncPoints.atOrAfter("http://s/Videos/6/stream?mediaSourceId=a", 1_000, 60_000))
    }

    @Test
    fun `the point before a start is chosen within the cap`() {
        val cues = Cues(30_000_000, 7_200_000_000)
        assertEquals(2_970_000L, SyncPoints.lastAtOrBefore(cues, 2_987_776, 45_000))
        assertEquals(3_000_000L, SyncPoints.lastAtOrBefore(cues, 3_000_000, 0))
        assertNull(SyncPoints.lastAtOrBefore(cues, 2_987_776, 17_000))
    }

    @Test
    fun `only a start from outside the player moves back, and not beyond the cap`() {
        assertTrue(SyncPoints.snapsStart(external = true, startMs = 2_987_776, pointMs = 2_970_000))
        assertFalse(SyncPoints.snapsStart(external = false, startMs = 2_987_776, pointMs = 2_970_000))
        assertFalse(SyncPoints.snapsStart(external = true, startMs = 2_987_776, pointMs = null))
        assertFalse(SyncPoints.snapsStart(external = true, startMs = 2_987_776, pointMs = 2_987_776))
        assertTrue(SyncPoints.snapsStart(external = true, startMs = 100_000, pointMs = 55_000))
        assertFalse(SyncPoints.snapsStart(external = true, startMs = 100_000, pointMs = 54_999))
    }

    @Test
    fun `a waiting start is told once when the file is known`() {
        var told = 0
        SyncPoints.whenKnown("k") { told++ }
        SyncPoints.remember("k", Cues(30_000_000, 600_000_000))
        SyncPoints.remember("k", Cues(30_000_000, 600_000_000))
        assertEquals(1, told)
    }
}
