package io.github.glacier_jellyfin.androidtv.core.data.playback

/**
 * What a stream may take of the link. A file's bitrate is an average, so a stream may use most of the
 * measured bandwidth, not all: the player's buffer carries the peaks and short dips, and a stall
 * (see [afterStall]) is the safety net. Quality comes first; a stream is only reduced when it clearly
 * does not fit.
 */
object StreamBudget {
    /** The share of the measured bandwidth a stream may use, in percent. */
    const val HEADROOM_PERCENT = 85

    /** A stream that stalled gets this share of the bitrate it had, in percent. */
    const val AFTER_STALL_PERCENT = 70

    /** A reduced stream goes back to the file once the link carries this much more than the file needs, in percent. */
    const val RETURN_PERCENT = 115

    /** No step goes below this; a transcode cannot do much with less. */
    const val FLOOR = 1_500_000

    /** The highest step of the bitrate setting (120 Mbit/s). */
    const val CEILING = 120_000_000

    /** The budget for a link that moved [measuredBps]. */
    fun of(measuredBps: Long): Int =
        (measuredBps * HEADROOM_PERCENT / 100).coerceIn(FLOOR.toLong(), CEILING.toLong()).toInt()

    /** A picture of [height] lines for a tier, and the bitrate asked of the server for it (a ceiling, see [resolutions]). */
    data class Resolution(val height: Int, val bitrate: Int)

    /**
     * The tiers of a transcode, biggest first: 4K, 1080p and 720p, none bigger than the display or the file. The server
     * encodes with constant quality (CRF) and takes the bitrate only as a ceiling for peaks, so what sets a stream apart is
     * the size of its picture, and so does what it asks of the link: see [expectedBitrate].
     */
    fun resolutions(displayHeight: Int, sourceHeight: Int): List<Resolution> {
        val most = minOf(displayHeight, sourceHeight)
        return listOf(Resolution(2160, 60_000_000), Resolution(1080, 30_000_000), Resolution(720, 10_000_000))
            .filter { it.height <= most }
    }

    /**
     * What a stream of [height] lines asks of the link, with room for peaks and the audio. Measured on a film transcoded
     * at CRF 23: about 1.4 Mbit/s at 720p and 3 to 5 at 1080p; 4K is estimated.
     */
    fun expectedBitrate(height: Int): Int = when {
        height >= 2000 -> 25_000_000
        height >= 1000 -> 8_000_000
        else -> 2_500_000
    }

    /** True when a stream of [bitrate] is more than the [budget] carries. Unknown values never exceed. */
    fun exceeds(bitrate: Int?, budget: Int?): Boolean = bitrate != null && budget != null && bitrate > budget

    /** The budget after a stream of [streamBitrate] stalled; never above the [current] one. */
    fun afterStall(streamBitrate: Int, current: Int?): Int =
        minOf(current ?: Int.MAX_VALUE, (streamBitrate.toLong() * AFTER_STALL_PERCENT / 100).toInt()).coerceAtLeast(FLOOR)

    /** Segment length of a video copy, in seconds; see [fitsWithSegments]. */
    const val COPY_SEGMENT_SECONDS = 30

    /** The server's wait per segment request, in seconds (one after the other). */
    const val SEGMENT_WAIT_SECONDS = 3

    /** What the server converts the audio to, in bit/s: 640 kbit/s from 6 channels (E-AC-3), else 384 kbit/s. */
    fun audioBitrate(channels: Int): Int = if (channels >= 6) 640_000 else 384_000

    /** What a stream asks of the link when only the audio is converted: the picture as it is plus the new audio. */
    fun copyDemand(videoBitrate: Int, audioChannels: Int): Int = videoBitrate + audioBitrate(audioChannels)

    /**
     * True when [budget] carries [demand] with segments of [COPY_SEGMENT_SECONDS]: the server answers each request after
     * about [SEGMENT_WAIT_SECONDS], so only (L - 3) / L of the time moves data.
     */
    fun fitsWithSegments(demand: Int, budget: Int): Boolean =
        demand.toLong() * COPY_SEGMENT_SECONDS <= budget.toLong() * (COPY_SEGMENT_SECONDS - SEGMENT_WAIT_SECONDS)

    /**
     * The maximum bitrate to ask for so the server copies the picture: its video share (the request minus its own audio
     * guess) must reach [videoBitrate], else it transcodes the picture.
     */
    fun copyRequest(videoBitrate: Int, audioChannels: Int): Int =
        (videoBitrate.toLong() * 102 / 100 + audioBitrate(audioChannels) + 64_000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    /** True when [budget] carries [bitrate] with room to spare, so a reduced stream may go back to it. */
    fun returnsTo(bitrate: Int, budget: Int): Boolean = budget.toLong() * 100 >= bitrate.toLong() * RETURN_PERCENT
}
