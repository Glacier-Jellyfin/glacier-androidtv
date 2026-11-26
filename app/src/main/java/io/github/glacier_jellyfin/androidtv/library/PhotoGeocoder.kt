package io.github.glacier_jellyfin.androidtv.library

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import android.os.SystemClock
import android.util.LruCache
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.glacier_jellyfin.androidtv.BuildConfig
import io.github.glacier_jellyfin.androidtv.core.data.AccountRepository
import io.github.glacier_jellyfin.androidtv.core.data.media.PhotoLocation
import io.github.glacier_jellyfin.androidtv.core.data.settings.PhotoGeocodingProtocol
import io.github.glacier_jellyfin.androidtv.core.data.settings.PhotoGeocodingSettings
import io.github.glacier_jellyfin.androidtv.core.data.settings.photoGeocodingBaseUrl
import io.github.glacier_jellyfin.androidtv.core.jellyfin.runSuspendCatching
import java.net.HttpURLConnection
import java.net.URI
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class PhotoPlace(val name: String, val fromOpenStreetMap: Boolean = false)

internal data class PhotoCoordinates(val latitude: Double, val longitude: Double)

internal val PhotoLocation.coordinates: PhotoCoordinates?
    get() {
        val lat = latitude?.takeIf { it in -90.0..90.0 } ?: return null
        val lon = longitude?.takeIf { it in -180.0..180.0 } ?: return null
        return PhotoCoordinates(lat, lon)
    }

@Singleton
class PhotoGeocoder @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val accounts: AccountRepository,
) {
    private data class Request(
        val lookup: PhotoGeocodingLookup,
        val point: PhotoCoordinates,
        val locale: Locale,
    )

    // The public endpoint also caches empty responses to avoid repeating the same request.
    private val cache = LruCache<Request, String>(128)
    private val publicNominatimLock = Mutex()
    private var nextPublicNominatimRequest = 0L

    suspend fun resolve(
        location: PhotoLocation,
        settings: PhotoGeocodingSettings,
        locale: Locale,
        interactive: Boolean,
    ): PhotoPlace? {
        location.names.joinToString(" · ").takeIf(String::isNotBlank)?.let { return PhotoPlace(it) }
        val point = location.coordinates ?: return null
        return resolvePhotoPlace(settings) { lookup ->
            val request = Request(lookup, point, locale)
            val key = if (lookup.protocol == PhotoGeocodingApi.BigDataCloud) {
                accounts.bigDataCloudKey() ?: return@resolvePhotoPlace null
            } else {
                null
            }
            cache.get(request)?.let { return@resolvePhotoPlace it }
            if (lookup.publicNominatim) {
                return@resolvePhotoPlace if (interactive) queryPublicNominatim(request) else null
            }
            query(request, key)?.takeIf(String::isNotBlank)?.also {
                cache.put(request, it)
            }
        }
    }

    private suspend fun queryPublicNominatim(request: Request): String? = publicNominatimLock.withLock {
        cache.get(request)?.let { return@withLock it }
        delay((nextPublicNominatimRequest - SystemClock.elapsedRealtime()).coerceAtLeast(0))
        nextPublicNominatimRequest = SystemClock.elapsedRealtime() + PUBLIC_NOMINATIM_INTERVAL_MS
        query(request, key = null).orEmpty().also { cache.put(request, it) }
    }

    private suspend fun query(request: Request, key: String?): String? {
        val (lookup, point, locale) = request
        val endpoint = lookup.endpoint(point, locale)
        return if (endpoint == null) androidPlace(point, locale) else fetchPlace(endpoint, request, key)
    }

    @Suppress("DEPRECATION") // The listener overload is available from Android 13.
    private suspend fun androidPlace(point: PhotoCoordinates, locale: Locale): String? =
        withTimeoutOrNull(ANDROID_TIMEOUT_MS) {
            if (!Geocoder.isPresent()) return@withTimeoutOrNull null
            val geocoder = Geocoder(context, locale)
            val matches = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                suspendCancellableCoroutine<List<Address>> { continuation ->
                    geocoder.getFromLocation(
                        point.latitude,
                        point.longitude,
                        1,
                        object : Geocoder.GeocodeListener {
                            override fun onGeocode(addresses: MutableList<Address>) = continuation.resume(addresses)
                            override fun onError(errorMessage: String?) = continuation.resume(emptyList())
                        },
                    )
                }
            } else {
                runInterruptible(Dispatchers.IO) {
                    geocoder.getFromLocation(point.latitude, point.longitude, 1).orEmpty()
                }
            }
            matches.firstOrNull()?.let { address ->
                placeName(
                    address.subLocality,
                    address.locality,
                    address.subAdminArea,
                    address.adminArea,
                    address.countryName,
                )
            }
        }

    private suspend fun fetchPlace(
        endpoint: URI,
        request: Request,
        key: String?,
    ): String? = runInterruptible(Dispatchers.IO) {
        val connection = endpoint.toURL().openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = HTTP_TIMEOUT_MS
            connection.readTimeout = HTTP_TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Accept-Language", request.locale.toLanguageTag())
            connection.setRequestProperty("User-Agent", "Glacier/${BuildConfig.VERSION_NAME} (Android TV)")
            if (key != null) connection.setRequestProperty("x-bdc-key", key)
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return@runInterruptible null
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val buffer = CharArray(MAX_RESPONSE_CHARS + 1)
                var length = 0
                while (length < buffer.size) {
                    val count = reader.read(buffer, length, buffer.size - length)
                    if (count < 0) break
                    length += count
                }
                if (length > MAX_RESPONSE_CHARS) return@use null
                parsePhotoPlace(request.lookup.protocol, buffer.concatToString(endIndex = length))
            }
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val ANDROID_TIMEOUT_MS = 2_500L
        const val HTTP_TIMEOUT_MS = 2_500
        const val MAX_RESPONSE_CHARS = 64 * 1024
        const val PUBLIC_NOMINATIM_INTERVAL_MS = 1_000L
    }
}

internal enum class PhotoGeocodingApi {
    Android,
    Nominatim,
    Photon,
    BigDataCloud,
    ;

    val fromOpenStreetMap: Boolean get() = this == Nominatim || this == Photon
}

internal data class PhotoGeocodingLookup(val protocol: PhotoGeocodingApi, val baseUrl: URI? = null) {
    val publicNominatim: Boolean
        get() = protocol == PhotoGeocodingApi.Nominatim && baseUrl?.host.equals(PublicNominatim.host, ignoreCase = true)

    fun endpoint(point: PhotoCoordinates, locale: Locale): URI? {
        val base = baseUrl?.toASCIIString()?.trimEnd('/') ?: return null
        val coordinates = "lat=${point.latitude}&lon=${point.longitude}"
        val cloudCoordinates = "latitude=${point.latitude}&longitude=${point.longitude}"
        val language = locale.language
        val path = when (protocol) {
            PhotoGeocodingApi.Android -> return null
            PhotoGeocodingApi.Nominatim -> "/reverse?format=jsonv2&zoom=14&addressdetails=1&$coordinates"
            PhotoGeocodingApi.Photon -> "/reverse?limit=1&lang=$language&$coordinates"
            PhotoGeocodingApi.BigDataCloud -> "/data/reverse-geocode?$cloudCoordinates&localityLanguage=$language"
        }
        return URI(base + path)
    }
}

private val PublicNominatim = URI("https://nominatim.openstreetmap.org")
private val BigDataCloud = URI("https://api-bdc.net")

internal suspend fun resolvePhotoPlace(
    settings: PhotoGeocodingSettings,
    query: suspend (PhotoGeocodingLookup) -> String?,
): PhotoPlace? {
    val lookups = buildList {
        if (settings.customEnabled) {
            val protocol = when (settings.customProtocol) {
                PhotoGeocodingProtocol.Nominatim -> PhotoGeocodingApi.Nominatim
                PhotoGeocodingProtocol.Photon -> PhotoGeocodingApi.Photon
            }
            photoGeocodingBaseUrl(settings.customUrl)?.let { add(PhotoGeocodingLookup(protocol, it)) }
        }
        if (settings.androidEnabled) add(PhotoGeocodingLookup(PhotoGeocodingApi.Android))
        if (settings.nominatimEnabled) add(PhotoGeocodingLookup(PhotoGeocodingApi.Nominatim, PublicNominatim))
        if (settings.bigDataCloudEnabled) add(PhotoGeocodingLookup(PhotoGeocodingApi.BigDataCloud, BigDataCloud))
    }.distinct()

    for (lookup in lookups) {
        val name = runSuspendCatching { query(lookup) }.getOrNull()?.takeIf(String::isNotBlank) ?: continue
        return PhotoPlace(name, lookup.protocol.fromOpenStreetMap)
    }
    return null
}

internal fun parsePhotoPlace(protocol: PhotoGeocodingApi, body: String): String? {
    val root = Json.parseToJsonElement(body) as? JsonObject ?: return null
    return when (protocol) {
        PhotoGeocodingApi.Android -> null

        PhotoGeocodingApi.Nominatim -> {
            val address = root["address"] as? JsonObject ?: return null
            placeName(
                address.firstText("suburb", "city_district", "borough", "quarter", "neighbourhood"),
                address.firstText("city", "town", "village", "municipality", "hamlet"),
                address.firstText("county", "state", "country"),
            )
        }

        PhotoGeocodingApi.Photon -> {
            val feature = (root["features"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
            val place = feature["properties"] as? JsonObject ?: return null
            val district = place.firstText("district", "locality") ?: place.firstText("name").takeIf {
                place.firstText("type") in listOf("district", "locality")
            }
            placeName(district, place.firstText("city"), place.firstText("county", "state", "country"))
        }

        PhotoGeocodingApi.BigDataCloud -> placeName(
            root.firstText("locality"),
            root.firstText("city"),
            root.firstText("principalSubdivision", "countryName"),
        )
    }
}

private fun JsonObject.firstText(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf(String::isNotEmpty)
}

private fun placeName(
    district: String?,
    city: String?,
    vararg broader: String?,
): String? {
    val local = listOfNotNull(district, city).map(String::trim).filter(String::isNotEmpty)
        .distinctBy { it.lowercase(Locale.ROOT) }
    return if (local.isEmpty()) broader.firstOrNull { !it.isNullOrBlank() }?.trim() else local.joinToString(", ")
}
