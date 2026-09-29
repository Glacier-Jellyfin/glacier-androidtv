package io.github.glacier_jellyfin.androidtv.core.data.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgeFilterTest {

    @Test
    fun `ratings count, the server's unrated markers do not`() {
        listOf("FSK-12", "DE-16", "PG-13", "TV-MA", "12").forEach { assertTrue(it, AgeFilter.isRated(it)) }
        listOf(null, "", "  ", "NR", "Not Rated", "unrated", "N/A").forEach { assertFalse(it.toString(), AgeFilter.isRated(it)) }
    }
}
