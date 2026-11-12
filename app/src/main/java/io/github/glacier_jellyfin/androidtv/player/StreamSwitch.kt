package io.github.glacier_jellyfin.androidtv.player

import io.github.glacier_jellyfin.androidtv.core.data.playback.PlaybackMethod
import io.github.glacier_jellyfin.androidtv.core.player.HandOverProgress
import kotlin.math.roundToInt

/** What a stream is called on the info panel: the file as it is, or the size of its picture. */
sealed interface StreamLabel {
    /** Direct play or direct stream: the file as the server stores it. */
    data object File : StreamLabel

    /** A transcode with a picture of [height] lines. */
    data class Picture(val height: Int) : StreamLabel

    /** A transcode whose picture size is not known. */
    data object Converted : StreamLabel
}

/** [pictureHeight] is only set for a transcode (see `PlaybackSource.pictureHeight`). */
fun streamLabel(method: PlaybackMethod, pictureHeight: Int?): StreamLabel = when {
    pictureHeight != null && pictureHeight > 0 -> StreamLabel.Picture(pictureHeight)
    method == PlaybackMethod.Transcode -> StreamLabel.Converted
    else -> StreamLabel.File
}

/** "720p", "1080p", "4K". */
fun pictureText(height: Int): String = if (height >= 2160) "4K" else "${height}p"

/** Why the stream changed. */
enum class SwitchCause {
    /** A measurement says the link does not carry the stream. */
    LinkSlow,

    /** The buffer fell while the player was loading. */
    Draining,

    /** Playback ran dry. */
    Stalls,

    /** The link loads less per second than the stream plays. */
    Throughput,

    /** The way back to the file (or to a bigger picture): the link carries more again. */
    LinkRecovered,
}

/** A [cause] with the numbers behind it, bit/s: what the link carries ([linkBps]) against what the stream asks ([needBps]). */
data class SwitchReason(val cause: SwitchCause, val linkBps: Int? = null, val needBps: Int? = null) {
    /** "15 von 37 Mbit/s" as the two whole numbers of Mbit/s, or null when the cause has none to show. */
    val mbit: Pair<Int, Int>?
        get() {
            val link = linkBps ?: return null
            val need = needBps ?: return null
            if (cause != SwitchCause.LinkSlow && cause != SwitchCause.Throughput) return null
            return (link / 1_000_000.0).roundToInt() to (need / 1_000_000.0).roundToInt()
        }
}

/** The reason of a step to another stream; no trigger is the way back, which the recovered link allows. */
internal fun switchReason(trigger: HandoverTrigger?, linkBps: Int? = null, needBps: Int? = null) = SwitchReason(
    when (trigger) {
        HandoverTrigger.Link -> SwitchCause.LinkSlow
        HandoverTrigger.Draining -> SwitchCause.Draining
        HandoverTrigger.Stalls -> SwitchCause.Stalls
        HandoverTrigger.Throughput -> SwitchCause.Throughput
        null -> SwitchCause.LinkRecovered
    },
    linkBps,
    needBps,
)

/**
 * A pending change of stream: a hand over between the file and a transcode. [atMs] is where in the title it happens.
 */
data class StreamSwitch(val from: StreamLabel, val to: StreamLabel, val reason: SwitchReason, val atMs: Long)

/**
 * The buffer bar while a hand over is pending, all as fractions of the bar (the scale is [maxMs] from the position): the
 * playing stream's buffer reaches [playing], the point where the next stream takes over is at [point], and what the next stream
 * has loaded fills the bar from there to [nextEnd].
 */
data class BufferSplit(val playing: Float, val point: Float, val nextEnd: Float)

/**
 * [bufferedMs]: what the playing stream holds ahead of the position; [pointInMs]: how far ahead the hand over point is;
 * [nextLoadedMs]: what the next stream has loaded beyond the point.
 */
fun bufferSplit(bufferedMs: Long, pointInMs: Long, nextLoadedMs: Long, maxMs: Long): BufferSplit {
    if (maxMs <= 0) return BufferSplit(0f, 0f, 0f)
    fun part(ms: Long) = (ms.coerceAtLeast(0).toDouble() / maxMs).toFloat().coerceIn(0f, 1f)
    val point = part(pointInMs)
    return BufferSplit(
        playing = minOf(part(bufferedMs), point),
        point = point,
        nextEnd = part(pointInMs + nextLoadedMs.coerceAtLeast(0)),
    )
}

/** What the panel reads of a pending hand over, sampled with the buffer. */
data class HandOverStats(
    /** How far ahead of the position the point is, ms. */
    val pointInMs: Long,
    /** The point was moved on because the next stream had loaded too little. */
    val postponedMs: Long,
    /** What the next stream has loaded beyond the point, ms. */
    val nextLoadedMs: Long,
    /** What the playing stream holds ahead of the position, ms. */
    val bufferedMs: Long,
)

internal fun HandOverProgress.toStats(positionMs: Long, bufferedPositionMs: Long) = HandOverStats(
    pointInMs = (pointInItemMs - positionMs).coerceAtLeast(0),
    postponedMs = postponedMs,
    nextLoadedMs = nextLoadedMs,
    bufferedMs = (bufferedPositionMs - positionMs).coerceAtLeast(0),
)
