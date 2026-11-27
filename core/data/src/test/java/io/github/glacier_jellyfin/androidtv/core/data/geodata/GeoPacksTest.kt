package io.github.glacier_jellyfin.androidtv.core.data.geodata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GeoPacksTest {

    @Test
    fun `the offered levels are fixed`() {
        assertEquals(listOf(15_000, 5_000, 1_000, 500, 0), GeoPacks.LEVELS)
    }

    @Test
    fun `off stays off`() {
        assertNull(GeoPacks.normalize(null))
    }

    @Test
    fun `a level with a pack stays`() {
        assertEquals(15_000, GeoPacks.normalize(15_000))
        assertEquals(500, GeoPacks.normalize(500))
        assertEquals(0, GeoPacks.normalize(0))
    }

    @Test
    fun `a removed level maps to the next coarser pack`() {
        assertEquals(500, GeoPacks.normalize(300))
    }

    @Test
    fun `a level above every pack maps to the bundled one`() {
        assertEquals(GeoPacks.BUNDLED_LEVEL, GeoPacks.normalize(20_000))
    }
}
