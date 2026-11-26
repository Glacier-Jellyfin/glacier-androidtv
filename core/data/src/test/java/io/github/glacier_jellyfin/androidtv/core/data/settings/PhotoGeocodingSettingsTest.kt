package io.github.glacier_jellyfin.androidtv.core.data.settings

import java.io.ByteArrayOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoGeocodingSettingsTest {
    @Test
    fun `an older local-only configuration still uses the local server exclusively`() = runTest {
        val state = read("""{"customProtocol":"Photon","customUrl":"http://photomap:2322"}""")

        assertEquals(PhotoGeocodingProtocol.Photon, state.customProtocol)
        assertEquals("http://photomap:2322", state.customUrl)
        assertTrue(state.customEnabled)
        assertFalse(state.androidEnabled)
        assertFalse(state.nominatimEnabled)
        assertFalse(state.bigDataCloudEnabled)
    }

    @Test
    fun `an older opt-in to fallback keeps Android enabled`() = runTest {
        val state = read("""{"customUrl":"http://photomap:2322","allowDefaultFallback":true}""")
        assertTrue(state.androidEnabled)
    }

    @Test
    fun `each switch round trips independently of the custom URL`() = runTest {
        val state = SettingsState(
            photoGeocoding = PhotoGeocodingSettings().copy(
                customUrl = "http://photomap:2322",
                customEnabled = false,
                nominatimEnabled = true,
                bigDataCloudEnabled = true,
            ),
        )
        val stored = ByteArrayOutputStream()
        SettingsSerializer.writeTo(state, stored)

        assertEquals(state, SettingsSerializer.readFrom(stored.toByteArray().inputStream()))
        assertTrue(read("{}").androidEnabled)
    }

    private suspend fun read(options: String) =
        SettingsSerializer.readFrom("""{"photoGeocoding":$options}""".byteInputStream()).photoGeocoding
}
