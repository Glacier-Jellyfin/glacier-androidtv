package io.github.glacier_jellyfin.androidtv.settings

import io.github.glacier_jellyfin.androidtv.core.data.geodata.GeoDataState
import io.github.glacier_jellyfin.androidtv.core.data.geodata.GeoFailure
import io.github.glacier_jellyfin.androidtv.core.data.geodata.GeoPacks
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaceNamesTest {

    @Test
    fun `sizes have one decimal below 10 MB and none above`() {
        assertEquals("0.9", megabytesNumber(873_851L, Locale.US))
        assertEquals("1.3", megabytesNumber(1_274_436L, Locale.US))
        assertEquals("57", megabytesNumber(56_874_459L, Locale.US))
        assertEquals("120", megabytesNumber(119_923_633L, Locale.US))
    }

    @Test
    fun `sizes use the decimal separator of the locale`() {
        assertEquals("1,3", megabytesNumber(1_274_436L, Locale.GERMANY))
        assertEquals("12,3", progressMegabytes(12_300_000L, Locale.GERMANY))
    }

    @Test
    fun `a progress always has one decimal`() {
        assertEquals("12.3", progressMegabytes(12_300_000L, Locale.US))
        assertEquals("57.0", progressMegabytes(57_000_000L, Locale.US))
    }

    @Test
    fun `levels are off, coarse to fine, then all places`() {
        assertEquals(listOf(null, 15_000, 5_000, 1_000, 500, 0), placeNamesChoices())
    }

    @Test
    fun `off setting is off whatever the files do`() {
        assertEquals(PlaceNamesStatus.Off, placeNamesStatus(null, GeoDataState.Ready(15_000, 1L)))
    }

    @Test
    fun `unpacking the built-in pack is preparing, not a download`() {
        val state = GeoDataState.Downloading(GeoPacks.BUNDLED_LEVEL, 10, 100)
        assertEquals(PlaceNamesStatus.Preparing, placeNamesStatus(GeoPacks.BUNDLED_LEVEL, state))
        assertEquals(PlaceNamesStatus.Preparing, placeNamesStatus(GeoPacks.BUNDLED_LEVEL, GeoDataState.Off))
    }

    @Test
    fun `built-in pack ready`() {
        assertEquals(
            PlaceNamesStatus.Builtin,
            placeNamesStatus(GeoPacks.BUNDLED_LEVEL, GeoDataState.Ready(GeoPacks.BUNDLED_LEVEL, 5L)),
        )
    }

    @Test
    fun `a download shows its percentage`() {
        val status = placeNamesStatus(0, GeoDataState.Downloading(0, 24_000_000L, 57_000_000L, fallbackActive = true))
        assertEquals(PlaceNamesStatus.Downloading(24_000_000L, 57_000_000L), status)
        assertEquals(42, (status as PlaceNamesStatus.Downloading).percent)
    }

    @Test
    fun `the bar fraction follows the bytes and is absent without a total`() {
        assertEquals(0.5f, PlaceNamesStatus.Downloading(50, 100).fraction)
        assertEquals(1f, PlaceNamesStatus.Downloading(150, 100).fraction)
        assertEquals(null, PlaceNamesStatus.Downloading(10, 0).fraction)
        assertEquals(null, PlaceNamesStatus.Downloading(10, -1).fraction)
    }

    @Test
    fun `a downloaded pack is ready with its size`() {
        assertEquals(PlaceNamesStatus.Ready(7L), placeNamesStatus(500, GeoDataState.Ready(500, 7L)))
    }

    @Test
    fun `a failure keeps its reason and the fallback`() {
        assertEquals(
            PlaceNamesStatus.Failed(GeoFailure.Storage, usingFallback = true),
            placeNamesStatus(500, GeoDataState.Failed(500, GeoFailure.Storage, usingFallback = true)),
        )
    }

    @Test
    fun `a state of another level is a change that has not arrived`() {
        assertEquals(PlaceNamesStatus.Preparing, placeNamesStatus(500, GeoDataState.Ready(15_000, 5L)))
    }
}
