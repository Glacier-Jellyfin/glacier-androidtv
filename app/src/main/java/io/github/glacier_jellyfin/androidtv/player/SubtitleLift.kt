package io.github.glacier_jellyfin.androidtv.player

import androidx.annotation.OptIn
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi

/**
 * What covers the bottom of the picture, as shares of the height: [fraction]
 * is how far subtitles move up, [covered] where the covering starts. The two
 * differ so a sign just above the controls stays where it belongs.
 */
enum class SubtitleLift(val fraction: Float, val covered: Float) {
    None(0f, 0f),

    /** Timeline and controls: the timeline starts about 250 above the bottom of 1080. */
    Osd(0.27f, 0.24f),

    /** The chapter sheet (about 440 of 1080). */
    Chapters(0.42f, 0.41f),
}

/**
 * The cue as shown while [lift] of the bottom is covered. Cues without a
 * position are lifted by the bottom padding ([liftedPadding]). Positioned
 * cues (SSA, PGS) keep their place, since signs belong to something in the
 * picture, unless they reach into the covered area: then they move up by
 * the lift as a whole, which keeps several of them in the same order.
 */
@OptIn(UnstableApi::class)
internal fun Cue.lifted(lift: SubtitleLift): Cue {
    if (lift.fraction <= 0f || line == Cue.DIMEN_UNSET) return this
    return when (lineType) {
        Cue.LINE_TYPE_FRACTION -> {
            val height = if (bitmap != null && bitmapHeight != Cue.DIMEN_UNSET) bitmapHeight else TEXT_LINE
            val moved = liftedLine(line, cueBottom(line, lineAnchor, height), lift.fraction, lift.covered)
            if (moved == line) this else buildUpon().setLine(moved, Cue.LINE_TYPE_FRACTION).build()
        }
        // Negative line numbers count up from the bottom (WebVTT): put them above the covered area.
        Cue.LINE_TYPE_NUMBER -> if (line < 0) {
            buildUpon().setLine(bottomLineAsFraction(line, lift.fraction), Cue.LINE_TYPE_FRACTION).setLineAnchor(Cue.ANCHOR_TYPE_END).build()
        } else {
            this
        }
        else -> this
    }
}

/** Space below cues without a position: [BOTTOM_MARGIN], or above the covered area. */
internal fun liftedPadding(lift: Float): Float =
    if (lift <= 0f) BOTTOM_MARGIN else lift + LIFT_MARGIN

/** A positioned cue moves up by [lift] when its lower edge reaches below [covered] from the bottom, else it stays. */
internal fun liftedLine(line: Float, bottom: Float, lift: Float, covered: Float): Float =
    if (lift > 0f && bottom > 1f - covered) line - lift else line

/** Lower edge of a cue whose [line] is a fraction of the height; [height] is its own height, also as a fraction. */
internal fun cueBottom(line: Float, anchor: Int, height: Float): Float = when (anchor) {
    Cue.ANCHOR_TYPE_END -> line
    Cue.ANCHOR_TYPE_MIDDLE -> line + height / 2
    // Start, or not set: the line is the top edge.
    else -> line + height
}

/** Line -1 is the lowest; each further one sits a text line higher. */
internal fun bottomLineAsFraction(line: Float, lift: Float): Float = 1f - liftedPadding(lift) + (line + 1) * TEXT_LINE

/** Height of one text line as a share of the picture (default size 5.33 % plus spacing). */
private const val TEXT_LINE = 0.07f

/** Space below the lowest line with nothing covering the picture; lower than Media3's 8 %, which sat high on a TV. */
private const val BOTTOM_MARGIN = 0.04f

/** Gap between lifted subtitles and whatever covers the bottom. */
private const val LIFT_MARGIN = 0.03f
