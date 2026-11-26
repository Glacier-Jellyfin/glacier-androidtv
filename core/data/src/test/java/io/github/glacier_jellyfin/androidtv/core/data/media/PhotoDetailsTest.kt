package io.github.glacier_jellyfin.androidtv.core.data.media

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageOrientation
import org.jellyfin.sdk.model.api.MediaProtocol
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaSourceType
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import org.jellyfin.sdk.model.api.MediaStreamType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhotoDetailsTest {
    private val utc = ZoneOffset.UTC

    @Test
    fun `the file size is the total of the Content-Range`() {
        assertEquals(365_513L, totalOfContentRange("bytes 0-0/365513"))
        assertNull(totalOfContentRange("bytes 0-0/*"))
        assertNull(totalOfContentRange("bytes 0-0"))
        assertNull(totalOfContentRange(null))
    }

    @Test
    fun `the server's UTC time is turned into the zone's time of that day`() {
        val berlin = ZoneId.of("Europe/Berlin")
        // Summer time: two hours ahead; winter time: one.
        assertEquals(LocalDateTime.of(2025, 7, 22, 8, 18), LocalDateTime.of(2025, 7, 22, 6, 18).utcToLocal(berlin))
        assertEquals(LocalDateTime.of(2025, 1, 5, 7, 18), LocalDateTime.of(2025, 1, 5, 6, 18).utcToLocal(berlin))
        val taken = photoDetails(dto { it.copy(premiereDate = LocalDateTime.of(2025, 7, 22, 6, 18)) }, berlin).taken
        assertEquals(LocalDateTime.of(2025, 7, 22, 8, 18), taken)
    }

    private fun dto(
        name: String? = "IMG_0001",
        path: String? = null,
        block: (BaseItemDto) -> BaseItemDto = { it },
    ) = block(BaseItemDto(id = UUID(0, 1), type = BaseItemKind.PHOTO, name = name, path = path))

    @Test
    fun `the file name is the last part of the path`() {
        assertEquals("a.jpg", photoDetails(dto(path = "/media/photos/2024/a.jpg")).fileName)
        assertEquals("b.jpg", photoDetails(dto(path = "D:\\photos\\b.jpg")).fileName)
    }

    @Test
    fun `without a path the name stands in and folder and format are missing`() {
        val details = photoDetails(dto(name = "Sunset"))
        assertEquals("Sunset", details.fileName)
        assertNull(details.folderName)
        assertNull(details.format)
    }

    @Test
    fun `the folder is the album, else the directory the file is in`() {
        assertEquals("2024", photoDetails(dto(path = "/media/photos/2024/a.jpg")).folderName)
        assertEquals("photos", photoDetails(dto(path = "D:\\photos\\b.jpg")).folderName)
        val album = photoDetails(dto(path = "/media/photos/2024/a.jpg") { it.copy(album = "Holiday") })
        assertEquals("Holiday", album.folderName)
        assertNull(photoDetails(dto(path = "/a.jpg")).folderName)
    }

    @Test
    fun `the format is the file extension, else the first container`() {
        assertEquals("heic", photoDetails(dto(path = "/p/IMG.HEIC")).format)
        assertEquals("mkv", photoDetails(dto { it.copy(container = "mkv") }).format)
        assertEquals("mov", photoDetails(dto { it.copy(container = "mov,mp4,m4a") }).format)
    }

    @Test
    fun `the position and the turn are taken over`() {
        val details = photoDetails(
            dto {
                it.copy(
                    latitude = 59.9139,
                    longitude = 10.7522,
                    altitude = 12.0,
                    software = " Photos 9 ",
                    imageOrientation = ImageOrientation.RIGHT_TOP,
                )
            },
        )
        assertEquals(59.9139, details.latitude!!, 0.0)
        assertEquals(10.7522, details.longitude!!, 0.0)
        assertEquals(12.0, details.altitude!!, 0.0)
        assertEquals("Photos 9", details.software)
        assertEquals(90, details.rotation)
        assertNull(photoDetails(dto { it.copy(imageOrientation = ImageOrientation.TOP_LEFT) }).rotation)
    }

    @Test
    fun `a video brings its streams, length and bitrate`() {
        val streams = listOf(
            MediaStream(
                type = MediaStreamType.VIDEO,
                index = 0,
                codec = "h264",
                width = 1920,
                height = 1080,
                realFrameRate = 25f,
                isInterlaced = false,
                isDefault = true,
                isForced = false,
                isExternal = false,
                isTextSubtitleStream = false,
                supportsExternalStream = false,
                isHearingImpaired = false,
                isOriginal = false,
            ),
            MediaStream(
                type = MediaStreamType.AUDIO,
                index = 1,
                codec = "aac",
                channels = 2,
                sampleRate = 48_000,
                isInterlaced = false,
                isDefault = true,
                isForced = false,
                isExternal = false,
                isTextSubtitleStream = false,
                supportsExternalStream = false,
                isHearingImpaired = false,
                isOriginal = false,
            ),
        )
        val details = photoDetails(
            dto(path = "/v/clip.mp4") {
                it.copy(
                    type = BaseItemKind.VIDEO,
                    runTimeTicks = 90_000L * 10_000,
                    mediaSources = listOf(source(size = 1L, mediaStreams = streams, bitrate = 8_000_000)),
                )
            },
        )
        assertEquals("h264", details.video?.codec)
        assertEquals(1080, details.video?.height)
        assertEquals("aac", details.audio?.codec)
        assertEquals(2, details.audio?.channels)
        assertEquals(90_000L, details.runtimeMs)
        assertEquals(8_000_000, details.bitrate)
        assertNull(photoDetails(dto { it.copy(mediaSources = listOf(source(1L, streams))) }).video)
    }

    @Test
    fun `the time taken is the premiere date, else the file's date`() {
        val premiere = LocalDateTime.of(2025, 7, 22, 8, 0)
        val created = LocalDateTime.of(2025, 8, 1, 9, 0)
        assertEquals(premiere, photoDetails(dto { it.copy(premiereDate = premiere, dateCreated = created) }, utc).taken)
        assertEquals(created, photoDetails(dto { it.copy(dateCreated = created) }, utc).taken)
    }

    @Test
    fun `camera and exposure values are taken over`() {
        val details = photoDetails(
            dto {
                it.copy(
                    width = 4032,
                    height = 3024,
                    cameraMake = " Apple ",
                    cameraModel = "iPhone 15",
                    aperture = 1.66,
                    exposureTime = 0.004,
                    focalLength = 6.9,
                    isoSpeedRating = 100,
                )
            },
        )
        assertEquals(4032, details.width)
        assertEquals(3024, details.height)
        assertEquals("Apple", details.cameraMake)
        assertEquals("iPhone 15", details.cameraModel)
        assertEquals(1.8, details.aperture!!, 0.05)
        assertEquals(0.004, details.exposureTime!!, 0.0)
        assertEquals(6.9, details.focalLength!!, 0.0)
        assertEquals(100, details.iso)
    }

    @Test
    fun `the size is the first media source's`() {
        val source = source(size = 2_500_000L)
        assertEquals(2_500_000L, photoDetails(dto { it.copy(mediaSources = listOf(source)) }).sizeBytes)
    }

    @Test
    fun `the aperture value in APEX units becomes the f-number`() {
        assertEquals(1.78, fNumberOf(1.66), 0.01)
        assertEquals(2.8, fNumberOf(2.97), 0.05)
        assertEquals(8.0, fNumberOf(6.0), 0.001)
        assertNull(photoDetails(dto()).aperture)
    }

    @Test
    fun `blank camera texts count as missing`() {
        val details = photoDetails(dto { it.copy(cameraMake = " ", cameraModel = "") })
        assertNull(details.cameraMake)
        assertNull(details.cameraModel)
    }

    private fun source(
        size: Long,
        mediaStreams: List<MediaStream> = emptyList(),
        bitrate: Int? = null,
    ) = MediaSourceInfo(
        protocol = MediaProtocol.FILE,
        id = "1",
        type = MediaSourceType.DEFAULT,
        size = size,
        bitrate = bitrate,
        isRemote = false,
        readAtNativeFramerate = false,
        ignoreDts = false,
        ignoreIndex = false,
        genPtsInput = false,
        supportsTranscoding = false,
        supportsDirectStream = true,
        supportsDirectPlay = true,
        isInfiniteStream = false,
        requiresOpening = false,
        requiresClosing = false,
        requiresLooping = false,
        supportsProbing = false,
        mediaStreams = mediaStreams,
        mediaAttachments = emptyList(),
        formats = emptyList(),
        requiredHttpHeaders = emptyMap(),
        transcodingSubProtocol = MediaStreamProtocol.HTTP,
        hasSegments = false,
    )
}
