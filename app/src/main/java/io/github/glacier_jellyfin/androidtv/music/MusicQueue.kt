package io.github.glacier_jellyfin.androidtv.music

import kotlin.random.Random

/** A queue order and the position playing in it. */
data class QueueOrder<T>(val items: List<T>, val index: Int)

/** The design's queue rules (`startMusic`, `mShuffle`), apart from the player so they can be tested. */
object MusicQueue {

    /**
     * The queue to start with: in source order from [start], or with shuffle
     * the start first and the rest mixed. Without a chosen song ([start] null)
     * a shuffled queue starts on a random one.
     */
    fun <T> start(source: List<T>, start: Int?, shuffle: Boolean, random: Random = Random.Default): QueueOrder<T> {
        if (source.isEmpty()) return QueueOrder(source, 0)
        if (!shuffle) return QueueOrder(source, (start ?: 0).coerceIn(0, source.lastIndex))
        val first = start?.coerceIn(0, source.lastIndex) ?: random.nextInt(source.size)
        val rest = source.filterIndexed { i, _ -> i != first }.shuffled(random)
        return QueueOrder(listOf(source[first]) + rest, 0)
    }

    /** Shuffle switched on: what already played stays, everything after the current song is mixed. */
    fun <T> shuffleRest(queue: List<T>, index: Int, random: Random = Random.Default): List<T> =
        queue.take(index + 1) + queue.drop(index + 1).shuffled(random)

    /** Shuffle switched off: back to the source order, at the current song's place in it. */
    fun <T> restore(source: List<T>, current: T): QueueOrder<T> =
        QueueOrder(source, source.indexOf(current).coerceAtLeast(0))
}
