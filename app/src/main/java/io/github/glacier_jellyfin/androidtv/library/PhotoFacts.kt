package io.github.glacier_jellyfin.androidtv.library

import io.github.glacier_jellyfin.androidtv.core.data.media.PhotoDetails
import io.github.glacier_jellyfin.androidtv.core.data.playback.SourceFile
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** "1/250 s" for a shutter faster than a second, "2 s" or "1.5 s" from a second on; null when unknown. */
internal fun exposureText(seconds: Double?): String? {
    if (seconds == null || seconds <= 0.0) return null
    return if (seconds < 1.0) {
        "1/${(1.0 / seconds).roundToInt().coerceAtLeast(1)} s"
    } else {
        "${trimmed(seconds)} s"
    }
}

/** "f/1.8", "f/2" for a whole number; null when unknown. */
internal fun apertureText(fNumber: Double?): String? = fNumber?.takeIf { it > 0.0 }?.let { "f/${trimmed(it)}" }

/** "26 mm", with one decimal only when it is not a whole number; null when unknown. */
internal fun focalLengthText(millimetres: Double?): String? =
    millimetres?.takeIf { it > 0.0 }?.let { "${trimmed(it)} mm" }

/** "ISO 100"; null when unknown. */
internal fun isoText(iso: Int?): String? = iso?.takeIf { it > 0 }?.let { "ISO $it" }

/**
 * "4032 × 3024", width first also in a right-to-left layout (kept left to right by an isolate);
 * null when either side is unknown.
 */
internal fun dimensionsText(width: Int?, height: Int?): String? {
    val w = width?.takeIf { it > 0 } ?: return null
    val h = height?.takeIf { it > 0 } ?: return null
    return "\u2066$w × $h\u2069"
}

/** "make model", just the model when it starts with the make already; null when both are missing. */
internal fun cameraText(make: String?, model: String?): String? {
    val m = make?.trim().orEmpty()
    val n = model?.trim().orEmpty()
    return when {
        m.isEmpty() -> n.ifEmpty { null }
        n.isEmpty() -> m
        n.startsWith(m, ignoreCase = true) -> n
        else -> "$m $n"
    }
}

/**
 * "59.9139° N, 10.7522° E" with four decimals and a dot, plus " · 12 m" for a known altitude; kept left to right by an
 * isolate. Null when either coordinate is unknown.
 */
internal fun locationText(
    latitude: Double?,
    longitude: Double?,
    altitude: Double? = null,
): String? {
    if (latitude == null || longitude == null) return null
    val lat = String.format(Locale.ROOT, "%.4f° %s", abs(latitude), if (latitude < 0) "S" else "N")
    val lon = String.format(Locale.ROOT, "%.4f° %s", abs(longitude), if (longitude < 0) "W" else "E")
    val height = altitude?.let { " · ${it.roundToInt()} m" }.orEmpty()
    return "\u2066$lat, $lon$height\u2069"
}

/** "JPEG" for "jpg", the extension in capitals otherwise ("heic" is "HEIC"); null when unknown. */
internal fun formatText(format: String?): String? {
    val f = format?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
    return if (f == "jpg" || f == "jpeg") "JPEG" else f.uppercase()
}

/**
 * What the server knows of a video's file as the player's [SourceFile], for the info tabs while no stream is open; null
 * when it knows neither picture nor sound.
 */
internal fun staticSourceFile(details: PhotoDetails): SourceFile? {
    if (details.video == null && details.audio == null) return null
    return SourceFile(
        container = details.format,
        sizeBytes = details.sizeBytes,
        bitrate = details.bitrate,
        streamContainer = null,
        video = details.video,
        audio = listOfNotNull(details.audio),
        transcodeReasons = emptyList(),
    )
}

/** One decimal at most, none for a whole number, always with a dot. */
private fun trimmed(value: Double): String {
    val rounded = (value * 10).roundToInt() / 10.0
    return if (rounded == rounded.toLong().toDouble()) {
        rounded.toLong().toString()
    } else {
        String.format(Locale.ROOT, "%.1f", rounded)
    }
}
