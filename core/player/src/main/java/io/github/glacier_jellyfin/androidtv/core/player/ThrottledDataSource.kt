package io.github.glacier_jellyfin.androidtv.core.player

import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import io.github.glacier_jellyfin.androidtv.core.log.DebugOptions
import io.github.glacier_jellyfin.androidtv.core.log.DebugThrottle
import io.github.glacier_jellyfin.androidtv.core.log.Log
import java.util.concurrent.TimeUnit

/** Slows what [upstream] reads to [DebugThrottle]'s cap, if there is one. */
@UnstableApi
internal class ThrottledDataSource(private val upstream: DataSource) : DataSource {

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(
        transferListener,
    )

    private var openedAtMs = 0L
    private var headersAtMs = 0L
    private var bytes = 0L
    private var name = ""

    override fun open(dataSpec: DataSpec): Long {
        openedAtMs = SystemClock.elapsedRealtime()
        bytes = 0
        name = dataSpec.uri.lastPathSegment.orEmpty().takeLast(14)
        val length = upstream.open(dataSpec)
        headersAtMs = SystemClock.elapsedRealtime()
        return length
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        val read = upstream.read(buffer, offset, length)
        if (read > 0) bytes += read
        val waitNs = DebugThrottle.waitNs(read)
        if (waitNs > 0) Thread.sleep(TimeUnit.NANOSECONDS.toMillis(waitNs), (waitNs % 1_000_000).toInt())
        DownloadRate.shared.record(read)
        return read
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        if (DebugOptions.segmentLog && openedAtMs != 0L) {
            val now = SystemClock.elapsedRealtime()
            Log.i(
                "Http",
                "$name: waited ${headersAtMs - openedAtMs} ms for the answer, ${bytes / 1024} KiB in ${now - headersAtMs} ms",
            )
        }
        openedAtMs = 0
        upstream.close()
    }

    class Factory(private val upstream: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = ThrottledDataSource(upstream.createDataSource())
    }
}
