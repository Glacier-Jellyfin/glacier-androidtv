package io.github.glacier_jellyfin.androidtv

import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.disk.DiskCache
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.map.Mapper
import coil3.request.Options
import coil3.size.pxOrElse
import io.github.glacier_jellyfin.androidtv.core.data.media.VideoFrames
import io.github.glacier_jellyfin.androidtv.core.log.Log
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okio.Buffer
import okio.FileSystem

/** A video frame to load: the key of one frame (no credentials), what the loader's disk cache stores it under. */
internal data class VideoFrameRequest(val frame: VideoFrames.Frame) {
    val key: String get() = VideoFrames.encode(frame.streamUrl, frame.positionMs)
}

/** Routes a `glacier-frame:` string to [VideoFrameFetcher] instead of treating it as a URL. */
internal class VideoFrameMapper : Mapper<String, VideoFrameRequest> {
    override fun map(data: String, options: Options): VideoFrameRequest? =
        VideoFrames.decode(data)?.let { VideoFrameRequest(it) }
}

/** Memory cache key of a frame: the frame itself, never the credentials. */
internal class VideoFrameKeyer : Keyer<VideoFrameRequest> {
    override fun key(data: VideoFrameRequest, options: Options): String = data.key
}

/**
 * Grabs a still frame from a video on the server with [MediaMetadataRetriever], upright, and keeps it as a JPEG in
 * the loader's disk cache so the video is only opened once. [authorization] gives the `Authorization` header for
 * the stream, null without a session.
 */
internal class VideoFrameFetcher(
    private val request: VideoFrameRequest,
    private val options: Options,
    private val imageLoader: ImageLoader,
    private val authorization: () -> String?,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val cache = imageLoader.diskCache
        val key = request.key
        cache?.let { read(it, key) }?.let { return it }
        val jpeg = withContext(Dispatchers.IO) {
            // Blocking and not cancellable: nothing inside it suspends.
            grabs.withPermit { runCatching { grab() ?: error("The video gives no frame") } }
        }.getOrElse {
            if (failed.putIfAbsent(key, true) == null) Log.w(TAG, "No frame of ${request.frame.streamUrl}", it)
            throw it
        }
        cache?.let { write(it, key, jpeg) }
        return SourceFetchResult(
            source = ImageSource(Buffer().apply { write(jpeg) }, FileSystem.SYSTEM),
            mimeType = MIME_JPEG,
            dataSource = DataSource.NETWORK,
        )
    }

    private fun read(cache: DiskCache, key: String): FetchResult? {
        val snapshot = cache.openSnapshot(key) ?: return null
        return SourceFetchResult(
            source = ImageSource(snapshot.data, cache.fileSystem, key, snapshot),
            mimeType = MIME_JPEG,
            dataSource = DataSource.DISK,
        )
    }

    private fun write(
        cache: DiskCache,
        key: String,
        jpeg: ByteArray,
    ) {
        val editor = cache.openEditor(key) ?: return
        try {
            cache.fileSystem.write(editor.data) { write(jpeg) }
            editor.commit()
        } catch (e: java.io.IOException) {
            editor.abort()
            Log.w(TAG, "Keeping a video frame failed", e)
        }
    }

    /** The frame as JPEG bytes, or null when the video gives none. Blocking; called off the main thread. */
    private fun grab(): ByteArray? {
        val retriever = MediaMetadataRetriever()
        try {
            val headers = authorization()?.let { mapOf("Authorization" to it) }.orEmpty()
            retriever.setDataSource(request.frame.streamUrl, headers)
            val shape = Shape(
                width = retriever.number(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH),
                height = retriever.number(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT),
                rotation = retriever.number(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION),
            )
            val bitmap = frame(retriever, request.frame.positionMs, shape)
                ?: frame(retriever, 0, shape)
                ?: return null
            val upright = shape.upright(bitmap)
            return ByteArrayOutputStream().also { upright.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
                .toByteArray()
        } finally {
            retriever.release()
        }
    }

    private fun frame(
        retriever: MediaMetadataRetriever,
        positionMs: Long,
        shape: Shape,
    ): Bitmap? {
        val timeUs = positionMs * MICROS_PER_MS
        if (!shape.known) return retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        // The longer side is the requested size; the other keeps the video's shape.
        val side = options.size.width.pxOrElse { 0 }.coerceIn(MIN_SIDE, MAX_SIDE)
        val scale = side.toFloat() / maxOf(shape.shownWidth, shape.shownHeight)
        val outWidth = (shape.shownWidth * scale).toInt().coerceAtLeast(1)
        val outHeight = (shape.shownHeight * scale).toInt().coerceAtLeast(1)
        return retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, outWidth, outHeight)
    }

    /** The video's stored size and its rotation; the size it is shown in follows from them. */
    private class Shape(
        val width: Int,
        val height: Int,
        val rotation: Int,
    ) {
        val quarterTurn = rotation % HALF_TURN == QUARTER_TURN
        val shownWidth = if (quarterTurn) height else width
        val shownHeight = if (quarterTurn) width else height
        val known = shownWidth > 0 && shownHeight > 0

        /**
         * Phones store a portrait video landscape with a rotation. Whether the retriever turned the frame already
         * differs between devices, so the frame is only turned when its shape is not the shown one.
         */
        fun upright(bitmap: Bitmap): Bitmap {
            if (!quarterTurn || !known || shownWidth == shownHeight) return bitmap
            if ((shownHeight > shownWidth) == (bitmap.height > bitmap.width)) return bitmap
            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
            return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }
    }

    private fun MediaMetadataRetriever.number(key: Int): Int = extractMetadata(key)?.toIntOrNull() ?: 0

    class Factory(private val authorization: () -> String?) : Fetcher.Factory<VideoFrameRequest> {
        override fun create(
            data: VideoFrameRequest,
            options: Options,
            imageLoader: ImageLoader,
        ): Fetcher = VideoFrameFetcher(data, options, imageLoader, authorization)
    }

    private companion object {
        const val TAG = "VideoFrame"
        const val MIME_JPEG = "image/jpeg"
        const val JPEG_QUALITY = 85
        const val MIN_SIDE = 640
        const val MAX_SIDE = 1280
        const val MICROS_PER_MS = 1000L
        const val HALF_TURN = 180
        const val QUARTER_TURN = 90
        const val CONCURRENT_GRABS = 2

        /** A grab opens a video over the network: only a few at once, however many cards ask. */
        val grabs = Semaphore(CONCURRENT_GRABS)

        /** Frames that failed already, so a failing video is logged once. */
        val failed = ConcurrentHashMap<String, Boolean>()
    }
}
