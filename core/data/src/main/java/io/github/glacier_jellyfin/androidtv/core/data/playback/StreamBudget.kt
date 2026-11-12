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

    /**
     * What the tier of [height] lines asks of the link. A tier the server transcodes asks [expectedBitrate]. But a tier of the
     * source's own height whose picture the server only copies ([videoCopied], see [pictureFits]) is as big as the source's
     * picture, [videoBitrate] with the reserve [COPY_PEAK_PERCENT], plus the converted audio: a 1080p copy of a remux takes
     * 30 Mbit/s, not the 8 Mbit/s of a 1080p transcode. Without a known [videoBitrate] the copy is counted as a transcode.
     */
    fun tierDemand(height: Int, sourceHeight: Int?, videoCopied: Boolean, videoBitrate: Int?, audioChannels: Int): Int =
        if (videoCopied && videoBitrate != null && videoBitrate > 0 && height == sourceHeight) {
            (videoBitrate.toLong() * COPY_PEAK_PERCENT / 100 + audioBitrate(audioChannels)).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        } else {
            expectedBitrate(height)
        }

    /** True when a stream of [bitrate] is more than the [budget] carries. Unknown values never exceed. */
    fun exceeds(bitrate: Int?, budget: Int?): Boolean = bitrate != null && budget != null && bitrate > budget

    /** The budget after a stream of [streamBitrate] stalled; never above the [current] one. */
    fun afterStall(streamBitrate: Int, current: Int?): Int =
        minOf(current ?: Int.MAX_VALUE, (streamBitrate.toLong() * AFTER_STALL_PERCENT / 100).toInt()).coerceAtLeast(FLOOR)

    /** The server's wait per segment request, in seconds (one after the other). */
    const val SEGMENT_WAIT_SECONDS = 3

    /** What the server converts the audio to, in bit/s: 640 kbit/s from 6 channels (E-AC-3), else 384 kbit/s. */
    fun audioBitrate(channels: Int): Int = if (channels >= 6) 640_000 else 384_000

    /** What a stream asks of the link when only the audio is converted: the picture as it is plus the new audio. */
    fun copyDemand(videoBitrate: Int, audioChannels: Int): Int = videoBitrate + audioBitrate(audioChannels)

    /** Reserve for bitrate peaks of a video copy, in percent of the demand. A scene measured 27 % above the film's mean (26.6 against 21 Mbit/s). */
    const val COPY_PEAK_PERCENT = 120

    /** The shortest and longest segment of a video copy, in seconds. */
    const val COPY_SEGMENT_MIN_SECONDS = 10
    const val COPY_SEGMENT_MAX_SECONDS = 30

    /**
     * The shortest segment length L in seconds (whole, [COPY_SEGMENT_MIN_SECONDS] to [COPY_SEGMENT_MAX_SECONDS]) for which
     * [budget] carries [demand] with the reserve [COPY_PEAK_PERCENT]: the server answers each request after about
     * [SEGMENT_WAIT_SECONDS], so only (L - 3) / L of the time moves data. Shorter segments start faster. Null when none fits.
     */
    fun copySegmentSeconds(demand: Int, budget: Int): Int? =
        (COPY_SEGMENT_MIN_SECONDS..COPY_SEGMENT_MAX_SECONDS).firstOrNull { l ->
            demand.toLong() * COPY_PEAK_PERCENT * l <= budget.toLong() * 100 * (l - SEGMENT_WAIT_SECONDS)
        }

    /**
     * The maximum bitrate to ask for so the server copies the picture: its video share (the request minus its own audio
     * guess) must reach [videoBitrate], else it transcodes the picture.
     */
    fun copyRequest(videoBitrate: Int, audioChannels: Int): Int =
        (videoBitrate.toLong() * 102 / 100 + audioBitrate(audioChannels) + 64_000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    /** Transcode reasons that leave the picture alone; every other reason (also an unknown one) concerns the video. */
    private val NON_VIDEO_REASONS = setOf(
        "ContainerNotSupported", "ContainerBitrateExceedsLimit", "DirectPlayError", "UnknownAudioStreamInfo",
        "AudioCodecNotSupported", "AudioBitrateNotSupported", "AudioChannelsNotSupported", "AudioProfileNotSupported",
        "AudioSampleRateNotSupported", "AudioBitDepthNotSupported", "AudioIsExternal", "SecondaryAudioNotSupported",
        "StreamCountExceedsLimit",
    )

    /**
     * True when the picture fits the device: the server allows direct play or stream, or it transcodes for reasons
     * that all leave the video alone (the video is then copied). [reasons] is the comma-separated value of
     * `TranscodeReasons` in the transcoding URL; a transcode without reasons does not count.
     */
    fun pictureFits(supportsDirect: Boolean, transcoding: Boolean, reasons: String?): Boolean {
        if (supportsDirect) return true
        if (!transcoding) return false
        val list = reasons?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        return list.isNotEmpty() && list.all { it in NON_VIDEO_REASONS }
    }

    /** True when [budget] carries [bitrate] with room to spare, so a reduced stream may go back to it. */
    fun returnsTo(bitrate: Int, budget: Int): Boolean = budget.toLong() * 100 >= bitrate.toLong() * RETURN_PERCENT
}
