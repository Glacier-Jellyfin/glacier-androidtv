package io.github.glacier_jellyfin.androidtv.core.data.playback

/**
 * The sizes and the arithmetic of the bandwidth test, which downloads blocks of the server's test file. The first block is
 * small, so a link of 0.5 Mbit/s answers within a few seconds; the second is sized by the first result, so it runs long
 * enough on a fast link to be worth something and not much longer than a few seconds on a slow one.
 *
 * The server's test answers with the next power of two of the size asked for (200,000 bytes come as 262,144, 5,000,000 as
 * 8,388,608), so the sizes here are powers of two: a block that is asked for is the block that arrives.
 */
internal object BandwidthProbe {
    /** The first block: 1 Mbit (128 KiB), a second on a link of 1 Mbit/s and 2.6 s on one of 0.4. */
    const val FIRST_BYTES = 131_072

    /** The biggest block (4 MiB). */
    const val BIG_BYTES = 4_194_304

    /** A download is stopped after this long; what moved until then counts. */
    const val TIMEOUT_MS = 8_000L

    /** The second block should take about this long, in seconds. */
    const val TARGET_SECONDS = 3

    /**
     * When the first block took this long (in seconds) or longer, there is no second one: on such a link it costs 2.7 s or more
     * and adds little, since a slow link does not speed up.
     */
    const val SKIP_SECOND_SECONDS = 2.0

    private const val BITS_PER_BYTE = 8
    private const val NANOS_PER_SECOND = 1e9

    /**
     * The second block for a first result of [firstBps]: about [TARGET_SECONDS] of transfer (rounded down to a power of two),
     * between the first block and the big one. A fast link's first result is low (the connection was still speeding up), so its block comes out bigger than
     * it needs, which is what lets the second download reach the real speed.
     */
    fun secondBlock(firstBps: Double): Int = Integer.highestOneBit(
        (firstBps * TARGET_SECONDS / BITS_PER_BYTE).coerceIn(FIRST_BYTES.toDouble(), BIG_BYTES.toDouble()).toInt(),
    )

    /** False when the first block ([FIRST_BYTES] at [firstBps]) took [SKIP_SECOND_SECONDS] or longer: the link is slow, the first result stands. */
    fun needsSecond(firstBps: Double): Boolean =
        firstBps > 0 && FIRST_BYTES * BITS_PER_BYTE / firstBps < SKIP_SECOND_SECONDS

    /** The speed of [bytes] moved in [nanos], in bit/s. */
    fun bitsPerSecond(bytes: Long, nanos: Long): Double {
        val elapsed = maxOf(nanos, 1L)
        return bytes * BITS_PER_BYTE * NANOS_PER_SECOND / elapsed
    }
}
