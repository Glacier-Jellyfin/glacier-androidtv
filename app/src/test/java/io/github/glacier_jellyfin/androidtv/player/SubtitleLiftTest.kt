package io.github.glacier_jellyfin.androidtv.player

import androidx.media3.common.text.Cue
import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleLiftTest {

    private val osd = SubtitleLift.Osd.fraction
    private val covered = SubtitleLift.Osd.covered

    @Test
    fun `dialogue at the bottom moves up by the covered height`() {
        // SSA "bottom centre": line 0.95, anchored at its end.
        assertEquals(0.95f - osd, liftedLine(0.95f, cueBottom(0.95f, Cue.ANCHOR_TYPE_END, 0.07f), osd, covered), DELTA)
    }

    @Test
    fun `a sign in the middle of the picture stays where it is`() {
        assertEquals(0.5f, liftedLine(0.5f, cueBottom(0.5f, Cue.ANCHOR_TYPE_MIDDLE, 0.07f), osd, covered), DELTA)
        assertEquals(0.1f, liftedLine(0.1f, cueBottom(0.1f, Cue.ANCHOR_TYPE_START, 0.07f), osd, covered), DELTA)
    }

    @Test
    fun `a bitmap reaching into the covered area moves up`() {
        // PGS: line is the top edge, the bitmap is 10 % high and ends at 95 %.
        assertEquals(0.85f - osd, liftedLine(0.85f, cueBottom(0.85f, Cue.ANCHOR_TYPE_START, 0.1f), osd, covered), DELTA)
    }

    @Test
    fun `a sign just above the controls stays`() {
        // Frieren's title sign: ends at 75 %, the timeline starts at 76 %.
        assertEquals(0.71f, liftedLine(0.71f, cueBottom(0.71f, Cue.ANCHOR_TYPE_START, 0.04f), osd, covered), DELTA)
    }

    @Test
    fun `nothing moves without a lift`() {
        assertEquals(0.95f, liftedLine(0.95f, 0.95f, 0f, 0f), DELTA)
    }

    @Test
    fun `lines counted from the bottom land above the covered area`() {
        assertEquals(1f - (osd + 0.03f), bottomLineAsFraction(-1f, osd), DELTA)
        assertEquals(1f - (osd + 0.03f) - 0.07f, bottomLineAsFraction(-2f, osd), DELTA)
    }

    private companion object {
        const val DELTA = 0.0001f
    }
}
