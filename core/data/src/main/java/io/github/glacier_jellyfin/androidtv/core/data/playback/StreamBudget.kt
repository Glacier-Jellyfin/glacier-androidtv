package io.github.glacier_jellyfin.androidtv.core.data.playback

import java.util.Locale

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

    /**
     * No step goes below this: a small picture with small audio fits (see [resolutions]); a transcode cannot do much with less.
     * Below the [HEADROOM_PERCENT] of a 400 kbit/s link, so such a link keeps its reserve: with the budget at the link itself
     * the stream only just kept up on the emulator (segments of 10 s loaded in 9 to 12 s).
     */
    const val FLOOR = 320_000

    /** The highest step of the bitrate setting (120 Mbit/s). */
    const val CEILING = 120_000_000

    /** The budget for a link that moved [measuredBps]. */
    fun of(measuredBps: Long): Int =
        (measuredBps * HEADROOM_PERCENT / 100).coerceIn(FLOOR.toLong(), CEILING.toLong()).toInt()

    /**
     * A tier: a picture of [height] lines (as asked for; the server may deliver a slightly different size), the bitrate asked of
     * the server for it ([bitrate], a ceiling, see [resolutions]) and what it takes of the link ([expected]). For a small tier
     * [bitrate] is below [expected], see [SMALL_OVERHEAD_PERCENT]. [width] is set for a small tier, whose size is computed from the source's shape;
     * null for the big ones, which are 16:9 boxes.
     */
    data class Resolution(
        val height: Int,
        val bitrate: Int,
        val expected: Int,
        val width: Int? = null,
    ) {
        /** The tier is so small that the audio must be small too, see [smallAudioFor]. */
        val smallAudio: Boolean get() = smallAudioFor(bitrate)
    }

    /** The big tiers: 4K, 1080p and 720p, with their ceiling (a multiple of what they need) and what they need, see [expectedBitrate]. */
    private val BIG_TIERS = listOf(
        Resolution(2160, 60_000_000, 25_000_000),
        Resolution(1080, 30_000_000, 8_000_000),
        Resolution(720, 10_000_000, 2_500_000),
    )

    /** What the small tiers take of the link in total, picture and audio, in bit/s. */
    private val SMALL_TARGETS = listOf(1_200_000, 800_000, 500_000)

    /**
     * A small tier asks the server for its target divided by this (in percent), because the stream it gets is bigger than the
     * bitrate asked for: the server sets the picture's bitrate to the request minus the audio, and the HLS muxing and the peaks
     * come on top. Measured from the segment sizes (10 s segments) of a 650 kbit/s request: 676 to 717 kbit/s (+4 to +10 %),
     * of a 400 kbit/s request: 410 to 461 kbit/s (+3 to +15 %). A 400 kbit/s tier on a link of 400 kbit/s stalled after 35 s.
     * With 118 the delivered stream stays under the target; the tier still counts with the target as its demand.
     */
    const val SMALL_OVERHEAD_PERCENT = 118

    /** The bitrate asked of the server for a small tier whose stream takes [target] of the link in the end. */
    fun requestFor(target: Int): Int = (target.toLong() * 100 / SMALL_OVERHEAD_PERCENT).toInt()

    /** A small tier whose target is this close (in percent) to another one is not worth a transcode of its own. */
    private const val CLOSE_PERCENT = 115

    /** A small tier must be at least this much smaller than the one above it, in percent of its height. */
    private const val SMALLER_PERCENT = 90

    /** The smallest picture a tier has. */
    private const val MIN_HEIGHT = 144

    /** Pictures are cut to a multiple of this many lines. */
    private const val HEIGHT_STEP = 8

    /** The shape and the speed of a picture whose source does not say. */
    const val DEFAULT_ASPECT = 16.0 / 9
    const val DEFAULT_FPS = 25.0

    private const val MIN_FPS = 24.0
    private const val MAX_FPS = 60.0

    /**
     * Bits a picture point needs per frame for a good picture, by the codec the server encodes with. An estimate: HEVC takes
     * about 0.05 at a bitrate of 1 Mbit/s for a film of 25 frames; H.264 needs 60 % more.
     */
    fun bitsPerPixel(codec: String?): Double = when (codec?.lowercase()) {
        "hevc", "h265" -> BPP_HEVC
        "av1" -> BPP_AV1
        else -> BPP_H264
    }

    private const val BPP_HEVC = 0.05
    private const val BPP_AV1 = 0.04
    private const val BPP_H264 = 0.08

    /** Widths are rounded up to a multiple of this. */
    private const val WIDTH_STEP = 16

    private const val KILO = 1000.0
    private const val MEGA = 1_000_000.0

    /**
     * The largest height for a small tier of [target] bit/s in total: what is left of it after the small audio buys
     * [bitsPerPixel] per point at [fps] frames, for a picture of [aspect] (width over height). Cut to a multiple of
     * [HEIGHT_STEP]; the width is [widthFor] that. The server is asked for [requestFor] of the target, and the picture gets
     * what is left of that request after the audio.
     */
    fun heightFor(
        target: Int,
        aspect: Double,
        fps: Double,
        codec: String?,
    ): Int {
        val video = (requestFor(target) - SMALL_AUDIO_BITRATE).coerceAtLeast(1)
        val points = video / (bitsPerPixel(codec) * fps.coerceIn(MIN_FPS, MAX_FPS))
        return (kotlin.math.sqrt(points / aspect).toInt() / HEIGHT_STEP) * HEIGHT_STEP
    }

    /** The width of a picture of [height] lines and [aspect], rounded up to a multiple of 16 so it never holds the picture back. */
    fun widthFor(height: Int, aspect: Double): Int =
        ((height * aspect).toInt() + WIDTH_STEP - 1) / WIDTH_STEP * WIDTH_STEP

    /**
     * The tiers of a transcode, biggest first, none bigger than the display or the file. The server encodes with constant
     * quality (CRF) and takes the bitrate only as a ceiling for peaks, so what sets a stream apart is the size of its picture,
     * and so does what it asks of the link: see [expectedBitrate]. 4K, 1080p and 720p are asked for a multiple of that, so a
     * ceiling never cuts a scene. Below them the tiers are set by a bitrate: each of [SMALL_TARGETS] and the link's [budget],
     * when it is under 720p's demand, gets the largest picture that bitrate carries ([heightFor], for the [codec] the server
     * encodes with, the source's [aspect] and [fps]). The link must carry that bitrate in the end, so the ceiling asked of the server is
     * [requestFor] of it, which leaves room for the muxing overhead. A tier only a little smaller than the one above it is left out.
     */
    fun resolutions(
        displayHeight: Int,
        sourceHeight: Int,
        aspect: Double = DEFAULT_ASPECT,
        fps: Double = DEFAULT_FPS,
        codec: String? = "hevc",
        budget: Int? = null,
    ): List<Resolution> {
        val most = minOf(displayHeight, sourceHeight)
        val tiers = BIG_TIERS.filter { it.height <= most }.toMutableList()
        val targets = SMALL_TARGETS.toMutableList()
        if (budget != null && budget >= FLOOR && budget < BIG_TIERS.last().expected) {
            val own = budget / 10_000 * 10_000
            if (targets.none { close(it, own) }) targets += own
        }
        for (target in targets.sortedDescending()) {
            val above = tiers.lastOrNull()
            val height = minOf(heightFor(target, aspect, fps, codec), most / HEIGHT_STEP * HEIGHT_STEP)
            val tooSmall = height < MIN_HEIGHT
            val notSmaller = height.toLong() * 100 >= (above?.height ?: Int.MAX_VALUE).toLong() * SMALLER_PERCENT
            val sameBitrate = above?.width != null && close(above.expected, target)
            if (tooSmall || notSmaller || sameBitrate) continue
            tiers += Resolution(height, requestFor(target), target, widthFor(height, aspect))
        }
        return tiers
    }

    private fun close(a: Int, b: Int): Boolean =
        a.toLong() * 100 <= b.toLong() * CLOSE_PERCENT && b.toLong() * 100 <= a.toLong() * CLOSE_PERCENT

    /**
     * What a big stream of [height] lines asks of the link, with room for peaks and the audio. Measured on a film transcoded
     * at CRF 23: about 1.4 Mbit/s at 720p and 3 to 5 at 1080p; 4K is estimated. A small tier carries its own number
     * ([Resolution.expected]).
     */
    fun expectedBitrate(height: Int): Int = when {
        height >= 2000 -> 25_000_000
        height >= 1000 -> 8_000_000
        else -> 2_500_000
    }

    /**
     * What [tier] asks of the link. A tier the server transcodes asks [Resolution.expected]. But a tier of the
     * source's own height whose picture the server only copies ([videoCopied], see [pictureFits]) is as big as the source's
     * picture, [videoBitrate] with the reserve [COPY_PEAK_PERCENT], plus the converted audio: a 1080p copy of a remux takes
     * 30 Mbit/s, not the 8 Mbit/s of a 1080p transcode. Without a known [videoBitrate] the copy is counted as a transcode.
     */
    fun tierDemand(
        tier: Resolution,
        sourceHeight: Int?,
        videoCopied: Boolean,
        videoBitrate: Int?,
        audioChannels: Int,
    ): Int {
        val copied = videoCopied && tier.height == sourceHeight
        val video = videoBitrate?.takeIf { it > 0 }
        if (!copied || video == null) return tier.expected
        return (video.toLong() * COPY_PEAK_PERCENT / 100 + audioBitrate(audioChannels, tier.smallAudio))
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
    }

    /** True when a stream of [bitrate] is more than the [budget] carries. Unknown values never exceed. */
    fun exceeds(bitrate: Int?, budget: Int?): Boolean = bitrate != null && budget != null && bitrate > budget

    /**
     * Whether a test that found the link too slow for a playing stream of [streamBitrate] is to be believed. The test competes
     * with the stream's own download, so one weak result says little: it counts when the [first] and a [second] test, a moment
     * later, both say the stream does not fit, and the player's own [estimateBps] (Media3's bandwidth meter, null or 0 when it
     * has none) does not carry the stream with the usual headroom either. A stream that keeps up is left alone; a real
     * bottleneck shows in the buffer and the throughput anyway.
     */
    fun weakLinkConfirmed(
        streamBitrate: Int,
        first: Int,
        second: Int?,
        estimateBps: Long?,
    ): Boolean {
        if (!exceeds(streamBitrate, first) || !exceeds(streamBitrate, second)) return false
        val estimate = estimateBps?.takeIf { it > 0 } ?: return true
        return exceeds(streamBitrate, of(estimate))
    }

    /** The budget after a stream of [streamBitrate] stalled; never above the [current] one. */
    fun afterStall(streamBitrate: Int, current: Int?): Int = minOf(
        current ?: Int.MAX_VALUE,
        (streamBitrate.toLong() * AFTER_STALL_PERCENT / 100).toInt(),
    ).coerceAtLeast(FLOOR)

    /** A request at or below this gets small audio (see [smallAudioFor]): 384 kbit/s of audio would be a fifth of it. */
    const val SMALL_AUDIO_BELOW = 2_500_000

    /** What the server converts audio to for a small stream, in bit/s: stereo AAC. */
    const val SMALL_AUDIO_BITRATE = 96_000

    /** True when a stream held to [bitrate] carries small audio, so the audio does not eat the little the link gives. */
    fun smallAudioFor(bitrate: Int): Boolean = bitrate <= SMALL_AUDIO_BELOW

    /** The server's wait per segment request, in seconds (one after the other). */
    const val SEGMENT_WAIT_SECONDS = 3

    /**
     * What the server converts the audio to, in bit/s: 640 kbit/s from 6 channels (E-AC-3), else 384 kbit/s. With [small] the
     * profile asks for stereo AAC at [SMALL_AUDIO_BITRATE] and the server keeps to it, whatever the file has.
     */
    fun audioBitrate(channels: Int, small: Boolean = false): Int = when {
        small -> SMALL_AUDIO_BITRATE
        channels >= 6 -> 640_000
        else -> 384_000
    }

    /** What a stream asks of the link when only the audio is converted: the picture as it is plus the new audio. */
    fun copyDemand(
        videoBitrate: Int,
        audioChannels: Int,
        smallAudio: Boolean = false,
    ): Int = videoBitrate + audioBitrate(audioChannels, smallAudio)

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
    fun copyRequest(
        videoBitrate: Int,
        audioChannels: Int,
        smallAudio: Boolean = false,
    ): Int = (videoBitrate.toLong() * 102 / 100 + audioBitrate(audioChannels, smallAudio) + 64_000)
        .coerceAtMost(Int.MAX_VALUE.toLong())
        .toInt()

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
    fun pictureFits(
        supportsDirect: Boolean,
        transcoding: Boolean,
        reasons: String?,
    ): Boolean {
        if (supportsDirect) return true
        if (!transcoding) return false
        val list = reasons?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        return list.isNotEmpty() && list.all { it in NON_VIDEO_REASONS }
    }

    /** True when [budget] carries [bitrate] with room to spare, so a reduced stream may go back to it. */
    fun returnsTo(bitrate: Int, budget: Int): Boolean = budget.toLong() * 100 >= bitrate.toLong() * RETURN_PERCENT

    /** A bitrate for people and logs: "420 kbit/s" below 1 Mbit/s, else "2.5 Mbit/s". */
    fun describe(bitsPerSecond: Number): String {
        val bps = bitsPerSecond.toDouble()
        return if (bps < MEGA) {
            "${Math.round(bps / KILO)} kbit/s"
        } else {
            String.format(Locale.ROOT, "%.1f Mbit/s", bps / MEGA)
        }
    }
}
