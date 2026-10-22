package io.github.glacier_jellyfin.androidtv.core.data.media

import io.github.glacier_jellyfin.androidtv.core.data.settings.ChannelSort
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

    private fun timer(id: String, start: Long, end: Long) =
        LiveTimer(id, null, id, null, null, null, start, end, seriesTimerId = null, recording = false)

    @Test
    fun `upcoming timers drop finished ones and come soonest first`() {
        val done = timer("done", 0, 100)
        val running = timer("running", 50, 200)
        val later = timer("later", 300, 400)
        val soon = timer("soon", 200, 300)
        assertEquals(listOf(running, soon, later), upcomingTimers(listOf(later, done, soon, running), 100))
    }

    @Test
    fun `a programme counts as recorded with a timer or a series timer`() {
        val plain = program("a", 0, 100)
        assertEquals(false, plain.recorded)
        assertEquals(true, plain.copy(timerId = "t").recorded)
        assertEquals(true, plain.copy(seriesTimerId = "s").recorded)
    }

    private fun named(name: String, number: String?) =
        LiveChannel(UUID.randomUUID(), number, name, null, favorite = false, now = null)

    @Test
    fun `by number the numbered channels come first, then the others by name`() {
        val channels = listOf(
            named("zdf", null), named("Arte", null), named("One", "101"),
            named("Ten", "10"), named("Nine", "9"), named("Five HD", "5.1"), named("Five", "5"),
        )
        assertEquals(
            listOf("Five", "Five HD", "Nine", "Ten", "One", "Arte", "zdf"),
            sortedChannels(channels, ChannelSort.Number).map { it.name },
        )
    }

    @Test
    fun `descending turns both round but channels without a number stay last`() {
        val channels = listOf(named("zdf", null), named("Arte", null), named("Ten", "10"), named("Nine", "9"), named("Five", "5"))
        assertEquals(
            listOf("Ten", "Nine", "Five", "zdf", "Arte"),
            sortedChannels(channels, ChannelSort.Number, descending = true).map { it.name },
        )
        assertEquals(
            listOf("zdf", "Ten", "Nine", "Five", "Arte"),
            sortedChannels(channels, ChannelSort.Name, descending = true).map { it.name },
        )
    }

    @Test
    fun `by name the numbers do not matter`() {
        val channels = listOf(named("ZDF", "2"), named("arte", null), named("Das Erste", "1"))
        assertEquals(listOf("arte", "Das Erste", "ZDF"), sortedChannels(channels, ChannelSort.Name).map { it.name })
    }
}
