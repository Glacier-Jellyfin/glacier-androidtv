package io.github.glacier_jellyfin.androidtv.core.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

class LiveTvTest {

    private val channelId = UUID.randomUUID()

    private fun program(title: String, start: Long, end: Long) =
        LiveProgram(title, channelId, title, null, null, start, end, null, null, new = false)

    private fun channel(number: String?) =
        LiveChannel(UUID.randomUUID(), number, "Channel $number", null, favorite = false, now = null)

    @Test
    fun `now is the programme on air and next the one after it`() {
        val a = program("a", 0, 100)
        val b = program("b", 100, 200)
        val c = program("c", 200, 300)
        assertEquals(b to c, nowAndNext(listOf(c, a, b), 150))
    }

    @Test
    fun `a programme counts as next from the second it starts`() {
        val a = program("a", 0, 100)
        val b = program("b", 100, 200)
        assertEquals(b to null, nowAndNext(listOf(a, b), 100))
    }

    @Test
    fun `a gap in the guide has no now but a next`() {
        val a = program("a", 0, 100)
        val b = program("b", 150, 200)
        assertEquals(null to b, nowAndNext(listOf(a, b), 120))
    }

    @Test
    fun `progress stays between zero and one`() {
        val a = program("a", 100, 200)
        assertEquals(0f, a.progressAt(50))
        assertEquals(0.5f, a.progressAt(150))
        assertEquals(1f, a.progressAt(500))
    }

    @Test
    fun `zapping wraps around at both ends`() {
        val channels = listOf(channel("1"), channel("2"), channel("3"))
        assertEquals(channels[1], channelAfter(channels, channels[0].id, 1))
        assertEquals(channels[0], channelAfter(channels, channels[2].id, 1))
        assertEquals(channels[2], channelAfter(channels, channels[0].id, -1))
        assertNull(channelAfter(emptyList(), channels[0].id, 1))
    }

    @Test
    fun `typed numbers ignore leading zeros`() {
        val channels = listOf(channel("1"), channel("007"), channel("101"))
        assertEquals(channels[2], channelByNumber(channels, "101"))
        assertEquals(channels[1], channelByNumber(channels, "7"))
        assertEquals(channels[0], channelByNumber(channels, "01"))
        assertNull(channelByNumber(channels, "5"))
        assertNull(channelByNumber(channels, "000"))
    }
}
