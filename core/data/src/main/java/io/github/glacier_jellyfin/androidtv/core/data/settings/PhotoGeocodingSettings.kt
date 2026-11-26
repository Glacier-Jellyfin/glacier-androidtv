package io.github.glacier_jellyfin.androidtv.core.data.settings

import java.net.URI
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

@Serializable
enum class PhotoGeocodingProtocol { Nominatim, Photon }

/** Device-local reverse geocoding preferences. */
@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class PhotoGeocodingSettings(
    val customProtocol: PhotoGeocodingProtocol = PhotoGeocodingProtocol.Nominatim,
    val customUrl: String = "",
    val customEnabled: Boolean = true,
    // Older installs with a custom URL and no fallback used that server exclusively.
    @JsonNames("allowDefaultFallback") val androidEnabled: Boolean = customUrl.isBlank(),
    val nominatimEnabled: Boolean = false,
    val bigDataCloudEnabled: Boolean = false,
)

private const val MAX_PORT = 65535

/** A base address, optionally with a path prefix. Credentials and query parameters belong on the server. */
fun photoGeocodingBaseUrl(value: String): URI? = runCatching { URI(value.trim()) }.getOrNull()?.takeIf {
    (it.scheme.equals("http", ignoreCase = true) || it.scheme.equals("https", ignoreCase = true)) &&
        !it.host.isNullOrEmpty() && it.port in -1..MAX_PORT && it.port != 0 &&
        it.rawUserInfo == null && it.rawQuery == null && it.rawFragment == null
}
