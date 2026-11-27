package io.github.glacier_jellyfin.androidtv.core.data.media

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JellyfinEnhancedActivitySourceTest {

    private val alex = "0123abcd456789ef0123456789abcdef"
    private val sam = "11111111222233334444555555555555"
    private val movie = "aaaaaaaabbbbccccddddeeeeeeeeeeee"
    private val episode = "bbbbbbbbccccddddeeeeffffffffffff"
    private val series = "cccccccc0000111122223333aaaaaaaa"

    private val json = """
        {"items":[
          {"ActivityType":"Watched","UserId":"$alex","UserName":"Alex","UserHasPrimaryImage":true,
           "Timestamp":1764200000000,"Completed":true,"Progress":1.0,"Extra":{"a":1},
           "Item":{"Id":"$movie","Name":"A Movie","Type":"Movie","ProductionYear":2020,"HasPrimaryImage":true,
                   "HasThumbImage":false,"SeriesHasPrimaryImage":false,"SeriesHasThumbImage":false,"Unknown":[1]}},
          {"ActivityType":"Watched","UserId":"$sam","UserName":"Sam","UserHasPrimaryImage":false,
           "Timestamp":1764100000000,"Completed":false,"Progress":0.42,
           "Item":{"Id":"$episode","Name":"Pilot","Type":"Episode","SeriesName":"A Show","SeriesId":"$series",
                   "SeasonNumber":1,"EpisodeNumber":1}},
          {"ActivityType":"Favorited","UserId":"$alex","UserName":"Alex","UserHasPrimaryImage":true,
           "Timestamp":1764000000000,"Completed":false,"Progress":0.0,
           "Item":{"Id":"$series","Name":"A Show","Type":"Series"}},
          {"ActivityType":"Reviewed","UserId":"$alex","UserName":"Alex","UserHasPrimaryImage":true,
           "Timestamp":1763900000000,"Item":{"Id":"$movie","Name":"A Movie","Type":"Movie"}},
          {"ActivityType":"Watched","UserId":"$alex","UserName":"Alex","UserHasPrimaryImage":true,
           "Timestamp":1763800000000,"Item":{"Id":"$movie","Name":"A Song","Type":"Audio"}},
          {"ActivityType":"Watched","UserId":"$alex","UserName":"Alex","UserHasPrimaryImage":true,
           "Timestamp":1763700000000,"Item":{"Id":"$movie","Name":"A Photo","Type":"Photo"}},
          {"ActivityType":"Watched","UserId":"$alex","UserName":"Alex","UserHasPrimaryImage":true,
           "Timestamp":1763600000000,"Item":{"Id":"not-an-id","Name":"Broken","Type":"Movie"}},
          {"ActivityType":"Watched","UserId":"nope","UserName":"Ghost","UserHasPrimaryImage":false,
           "Timestamp":1763500000000,"Item":{"Id":"$movie","Name":"A Movie","Type":"Movie"}},
          {"ActivityType":"Watched","UserId":"$sam","UserName":"Sam","UserHasPrimaryImage":false,
           "Timestamp":1763400000000,"Progress":7.5,"Item":{"Id":"$series","Name":"A Show","Type":"Series"}}
        ]}
    """.trimIndent()

    @Test
    fun `only watched and favorited videos survive`() {
        val parsed = parseActivity(json)
        assertEquals(listOf("Movie", "Episode", "Series"), parsed.map { it.itemType })
        assertEquals(listOf(ActivityType.Watched, ActivityType.Watched, ActivityType.Favorited), parsed.map { it.type })
    }

    @Test
    fun `values are read`() {
        val (first, second, third) = parseActivity(json)
        assertEquals(UUID.fromString("0123abcd-4567-89ef-0123-456789abcdef"), first.userId)
        assertEquals("Alex", first.userName)
        assertTrue(first.userHasImage)
        assertEquals(1764200000000, first.timestampMs)
        assertEquals(UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"), first.itemId)
        assertTrue(first.completed)
        assertEquals(1f, first.progress, 0f)
        assertNull(first.seriesId)

        assertFalse(second.userHasImage)
        assertFalse(second.completed)
        assertEquals(0.42f, second.progress, 0.0001f)
        assertEquals(UUID.fromString("cccccccc-0000-1111-2222-3333aaaaaaaa"), second.seriesId)

        assertEquals(UUID.fromString("cccccccc-0000-1111-2222-3333aaaaaaaa"), third.itemId)
    }

    @Test
    fun `a watched show is dropped and progress is coerced`() {
        assertTrue(parseActivity(json).none { it.type == ActivityType.Watched && it.itemType == "Series" })
        val odd = """{"items":[{"ActivityType":"Watched","UserId":"$alex","UserName":"A","Timestamp":1,
            "Progress":7.5,"Item":{"Id":"$movie","Type":"Movie"}}]}"""
        assertEquals(1f, parseActivity(odd).single().progress, 0f)
    }

    @Test
    fun `an empty feed is empty`() {
        assertTrue(parseActivity("""{"items":[]}""").isEmpty())
    }
}
