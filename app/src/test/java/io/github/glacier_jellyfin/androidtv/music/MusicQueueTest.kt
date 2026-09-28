package io.github.glacier_jellyfin.androidtv.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class MusicQueueTest {

    private val songs = (1..8).toList()

    @Test
    fun `without shuffle the queue keeps the source order`() {
        assertEquals(QueueOrder(songs, 3), MusicQueue.start(songs, start = 3, shuffle = false))
        assertEquals(QueueOrder(songs, 0), MusicQueue.start(songs, start = null, shuffle = false))
    }

    @Test
    fun `with shuffle the chosen song plays first and the rest is mixed`() {
        val order = MusicQueue.start(songs, start = 5, shuffle = true, random = Random(1))
        assertEquals(0, order.index)
        assertEquals(6, order.items.first())
        assertEquals(songs.toSet(), order.items.toSet())
        assertEquals(songs.size, order.items.size)
    }

    @Test
    fun `shuffle without a chosen song starts anywhere`() {
        val firsts = (0 until 40).map { seed -> MusicQueue.start(songs, start = null, shuffle = true, random = Random(seed)).items.first() }
        assertTrue(firsts.toSet().size > 1)
    }

    @Test
    fun `switching shuffle on keeps what already played`() {
        val queue = MusicQueue.shuffleRest(songs, index = 2, random = Random(7))
        assertEquals(listOf(1, 2, 3), queue.take(3))
        assertEquals(songs.toSet(), queue.toSet())
    }

    @Test
    fun `switching shuffle off returns to the current song's place`() {
        assertEquals(QueueOrder(songs, 5), MusicQueue.restore(songs, current = 6))
    }
}
