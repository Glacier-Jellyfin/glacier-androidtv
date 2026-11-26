package io.github.glacier_jellyfin.androidtv.core.data.media

import io.github.glacier_jellyfin.androidtv.core.data.playback.SourceAudio
import io.github.glacier_jellyfin.androidtv.core.data.playback.SourceVideo
import io.github.glacier_jellyfin.androidtv.core.data.playback.toSourceAudio
import io.github.glacier_jellyfin.androidtv.core.data.playback.toSourceVideo
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageOrientation
import org.jellyfin.sdk.model.api.MediaStreamType

/**
 * What the server knows about a photo or video of a folder: its file, when and where it was taken, and for a photo the
 * camera, for a video the streams. Every field is null when it is not known.
 */
data class PhotoDetails(
    val fileName: String? = null,
    /** The album the server files it under, else the name of the folder the file is in. */
    val folderName: String? = null,
    /** The file format in lower case: "jpg", "heic", "mp4". */
    val format: String? = null,
    val taken: LocalDateTime? = null,
    val width: Int? = null,
    val height: Int? = null,
    val sizeBytes: Long? = null,
    val cameraMake: String? = null,
    val cameraModel: String? = null,
    /** The f-number, 1.8 for f/1.8 (converted from the server's APEX value). */
    val aperture: Double? = null,
    /** In seconds. */
    val exposureTime: Double? = null,
    /** In millimetres. */
    val focalLength: Double? = null,
    val iso: Int? = null,
    val software: String? = null,
    /** Degrees clockwise the picture is turned to be upright (90, 180 or 270); null when it is stored upright. */
    val rotation: Int? = null,
    /** Degrees north (negative: south). */
    val latitude: Double? = null,
    /** Degrees east (negative: west). */
    val longitude: Double? = null,
    /** In metres above sea level. */
    val altitude: Double? = null,
    /** Of a video. */
    val runtimeMs: Long? = null,
    /** Of the file as a whole, in bits per second. */
    val bitrate: Int? = null,
    val video: SourceVideo? = null,
    val audio: SourceAudio? = null,
)

/**
 * The f-number for an aperture in APEX units. Jellyfin reports the EXIF ApertureValue, not the f-number:
 * Av = 2 * log2(N), so N = 2^(Av / 2). An Av of 1.66 is f/1.8, 2.97 is f/2.8, 6.0 is f/8.
 */
internal fun fNumberOf(apex: Double): Double = Math.pow(2.0, apex / 2)

/** The details of a photo or video as the server describes it; blank texts count as missing. */
fun photoDetails(dto: BaseItemDto, zone: ZoneId = ZoneId.systemDefault()): PhotoDetails {
    val path = dto.path?.takeIf { it.isNotBlank() }
    val fileName = path?.substringAfterLast('/')?.substringAfterLast('\\')?.takeIf { it.isNotBlank() }
    val source = dto.mediaSources?.firstOrNull()
    val streams = source?.mediaStreams ?: dto.mediaStreams.orEmpty()
    val isVideo = dto.type != BaseItemKind.PHOTO
    return PhotoDetails(
        fileName = fileName ?: dto.name?.takeIf { it.isNotBlank() },
        folderName = dto.album?.trim()?.takeIf { it.isNotEmpty() } ?: path?.let(::parentFolderName),
        format = fileName?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.isNotEmpty() }
            ?: (source?.container ?: dto.container)?.substringBefore(',')?.trim()?.lowercase()
                ?.takeIf { it.isNotEmpty() },
        taken = (dto.premiereDate ?: dto.dateCreated)?.utcToLocal(zone),
        width = dto.width,
        height = dto.height,
        sizeBytes = source?.size,
        cameraMake = dto.cameraMake?.trim()?.takeIf { it.isNotEmpty() },
        cameraModel = dto.cameraModel?.trim()?.takeIf { it.isNotEmpty() },
        aperture = dto.aperture?.let(::fNumberOf),
        exposureTime = dto.exposureTime,
        focalLength = dto.focalLength,
        iso = dto.isoSpeedRating,
        software = dto.software?.trim()?.takeIf { it.isNotEmpty() },
        rotation = when (dto.imageOrientation) {
            ImageOrientation.RIGHT_TOP -> 90
            ImageOrientation.BOTTOM_RIGHT -> 180
            ImageOrientation.LEFT_BOTTOM -> 270
            else -> null
        },
        latitude = dto.latitude,
        longitude = dto.longitude,
        altitude = dto.altitude,
        runtimeMs = dto.runTimeTicks?.takeIf { it > 0 }?.div(TICKS_PER_MS),
        bitrate = source?.bitrate?.takeIf { it > 0 },
        video = streams.takeIf { isVideo }?.firstOrNull { it.type == MediaStreamType.VIDEO }?.toSourceVideo(),
        audio = streams.takeIf { isVideo }?.firstOrNull { it.type == MediaStreamType.AUDIO && !it.isExternal }
            ?.toSourceAudio(),
    )
}

/**
 * A time the server keeps in UTC in [zone]'s local time, with the zone's rules of that day (summer time). The server
 * hands a photo's time taken over as UTC although the camera wrote its local time.
 */
fun LocalDateTime.utcToLocal(zone: ZoneId = ZoneId.systemDefault()): LocalDateTime =
    atOffset(ZoneOffset.UTC).atZoneSameInstant(zone).toLocalDateTime()

private const val TICKS_PER_MS = 10_000L

/** The name of the folder a file path is in, with / or \ as separator; null for a file at the top. */
private fun parentFolderName(path: String): String? =
    path.trimEnd('/', '\\').split('/', '\\').filter { it.isNotEmpty() }.dropLast(1).lastOrNull()?.takeIf {
        it.isNotBlank()
    }
