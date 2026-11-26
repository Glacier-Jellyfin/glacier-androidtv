package io.github.glacier_jellyfin.androidtv.library

import io.github.glacier_jellyfin.androidtv.core.data.settings.PhotoGeocodingProtocol
import io.github.glacier_jellyfin.androidtv.core.data.settings.PhotoGeocodingSettings
import io.github.glacier_jellyfin.androidtv.core.data.settings.photoGeocodingBaseUrl
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhotoGeocoderTest {
    private val options = PhotoGeocodingSettings(
        customProtocol = PhotoGeocodingProtocol.Photon,
        customUrl = "http://photon:2322",
        androidEnabled = true,
        nominatimEnabled = true,
        bigDataCloudEnabled = true,
    )

    @Test
    fun `a successful custom service stops the lookup`() = runBlocking {
        val providers = mutableListOf<PhotoGeocodingApi>()
        val place = resolvePhotoPlace(options) {
            providers += it.protocol
            "Kreuzberg, Berlin"
        }

        assertEquals(listOf(PhotoGeocodingApi.Photon), providers)
        assertEquals(PhotoPlace("Kreuzberg, Berlin", fromOpenStreetMap = true), place)
    }

    @Test
    fun `a failed custom service stays local when the other services are off`() = runBlocking {
        val providers = mutableListOf<PhotoGeocodingApi>()
        val localOnly = options.copy(androidEnabled = false, nominatimEnabled = false, bigDataCloudEnabled = false)
        val place = resolvePhotoPlace(localOnly) {
            providers += it.protocol
            throw IOException("offline")
        }

        assertEquals(listOf(PhotoGeocodingApi.Photon), providers)
        assertNull(place)

        providers.clear()
        val invalid = resolvePhotoPlace(localOnly.copy(customUrl = "unusable")) {
            providers += it.protocol
            null
        }
        assertNull(invalid)
        assertEquals(emptyList<PhotoGeocodingApi>(), providers)
    }

    @Test
    fun `fallback goes through Android then Nominatim then BigDataCloud`() = runBlocking {
        val providers = mutableListOf<PhotoGeocodingApi>()
        val place = resolvePhotoPlace(options) {
            providers += it.protocol
            when (it.protocol) {
                PhotoGeocodingApi.Photon -> " "
                PhotoGeocodingApi.Android -> null
                PhotoGeocodingApi.Nominatim -> throw IOException("offline")
                PhotoGeocodingApi.BigDataCloud -> "Berlin"
            }
        }

        assertEquals(
            listOf(
                PhotoGeocodingApi.Photon,
                PhotoGeocodingApi.Android,
                PhotoGeocodingApi.Nominatim,
                PhotoGeocodingApi.BigDataCloud,
            ),
            providers,
        )
        assertEquals(PhotoPlace("Berlin"), place)
    }

    @Test
    fun `disabled services are skipped in place without reordering those enabled`() = runBlocking {
        val lookups = mutableListOf<PhotoGeocodingLookup>()
        resolvePhotoPlace(options.copy(customEnabled = false, androidEnabled = false)) {
            lookups += it
            null
        }
        assertEquals(listOf(PhotoGeocodingApi.Nominatim, PhotoGeocodingApi.BigDataCloud), lookups.map { it.protocol })
        assertEquals(listOf("nominatim.openstreetmap.org", "api-bdc.net"), lookups.map { it.baseUrl?.host })
        assertEquals(listOf(true, false), lookups.map { it.publicNominatim })
    }

    @Test
    fun `with no configured servers only Android is queried`() = runBlocking {
        val providers = mutableListOf<PhotoGeocodingApi>()
        val place = resolvePhotoPlace(PhotoGeocodingSettings()) {
            providers += it.protocol
            null
        }
        assertNull(place)
        assertEquals(listOf(PhotoGeocodingApi.Android), providers)
    }

    @Test
    fun `Nominatim and Photon use different parameters and response formats`() {
        val base = photoGeocodingBaseUrl("http://geocoder:2322/prefix/")
        val point = PhotoCoordinates(52.5, 13.4)
        val nominatim = PhotoGeocodingLookup(PhotoGeocodingApi.Nominatim, base)
        val photon = PhotoGeocodingLookup(PhotoGeocodingApi.Photon, base)

        assertEquals("/prefix/reverse", nominatim.endpoint(point, Locale.GERMAN)?.path)
        assertEquals(
            "format=jsonv2&zoom=14&addressdetails=1&lat=52.5&lon=13.4",
            nominatim.endpoint(point, Locale.GERMAN)?.query,
        )
        assertEquals("limit=1&lang=de&lat=52.5&lon=13.4", photon.endpoint(point, Locale.GERMAN)?.query)
        assertEquals(
            "Kreuzberg, Berlin",
            parsePhotoPlace(PhotoGeocodingApi.Nominatim, """{"address":{"suburb":"Kreuzberg","city":"Berlin"}}"""),
        )
        assertEquals(
            "Kreuzberg, Berlin",
            parsePhotoPlace(
                PhotoGeocodingApi.Photon,
                """{"features":[{"properties":{"district":"Kreuzberg","city":"Berlin","name":"A café"}}]}""",
            ),
        )
        assertNull(parsePhotoPlace(PhotoGeocodingApi.Photon, """{"features":[]}"""))
    }

    @Test
    fun `BigDataCloud uses the fixed stored-coordinate API endpoint`() = runBlocking {
        val cloudOnly = options.copy(customEnabled = false, androidEnabled = false, nominatimEnabled = false)
        val lookups = mutableListOf<PhotoGeocodingLookup>()
        resolvePhotoPlace(cloudOnly) {
            lookups += it
            null
        }
        val endpoint = lookups.single().endpoint(PhotoCoordinates(52.5, 13.4), Locale.GERMAN)
        assertEquals("https", endpoint?.scheme)
        assertEquals("api-bdc.net", endpoint?.host)
        assertEquals("/data/reverse-geocode", endpoint?.path)
        assertEquals("latitude=52.5&longitude=13.4&localityLanguage=de", endpoint?.query)
        assertEquals(
            "Kreuzberg, Berlin",
            parsePhotoPlace(PhotoGeocodingApi.BigDataCloud, """{"locality":"Kreuzberg","city":"Berlin"}"""),
        )
    }
}
