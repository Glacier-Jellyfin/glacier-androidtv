package io.github.glacier_jellyfin.androidtv.music

import io.github.glacier_jellyfin.androidtv.core.data.media.LiveChannel
import io.github.glacier_jellyfin.androidtv.core.data.media.LiveProgram
import io.github.glacier_jellyfin.androidtv.livetv.LiveTvState
import io.github.glacier_jellyfin.androidtv.livetv.LiveTvView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.UUID

class RadioTest {

    private fun channel(name: String, radio: Boolean = false, favorite: Boolean = false, now: LiveProgram? = null) =
        LiveChannel(UUID.randomUUID(), null, name, null, favorite = favorite, now = now, radio = radio)

    private fun program(channelId: UUID, title: String, startMs: Long, endMs: Long) =
        LiveProgram(title, channelId, title, null, null, startMs, endMs, null, null, new = false)

    @Test
    fun `the page lists TV and radio apart`() {
        val tv = channel("Das Erste")
        val radio = channel("WDR 2", radio = true, favorite = true)
        val other = channel("SWR3", radio = true)
        val state = LiveTvState().withChannels(listOf(tv, radio, other))
        assertEquals(listOf(tv), state.shown)
        assertEquals(listOf(radio, other), state.copy(view = LiveTvView.Radio).shown)
        assertEquals(listOf(radio), state.copy(view = LiveTvView.Radio, favoritesOnly = true).shown)
    }

    @Test
    fun `the programme comes from the guide, else from the channel list`() {
        val listed = channel("WDR 2", radio = true)
        val fromList = program(listed.id, "Morning", 0, 1_000)
        val station = listed.copy(now = fromList)
        val radio = RadioState(listOf(station), station)
        assertEquals(fromList to null, radio.nowAndNextAt(500))

        val now = program(station.id, "Noon", 1_000, 2_000)
        val next = program(station.id, "Evening", 2_000, 3_000)
        val loaded = radio.copy(programs = listOf(next, now))
        assertEquals(now to next, loaded.nowAndNextAt(1_500))
    }

    @Test
    fun `an ended programme from the list does not count as now`() {
        val listed = channel("WDR 2", radio = true)
        val station = listed.copy(now = program(listed.id, "Morning", 0, 1_000))
        assertNull(RadioState(listOf(station), station).nowAndNextAt(1_500).first)
    }
}
