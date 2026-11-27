package io.github.glacier_jellyfin.androidtv.core.data.geodata

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Where the bytes of a pack come from. [open] returns the content of the release asset [name] (for example
 * `places-15000.bin.gz`) and throws [IOException] when it cannot be had; the caller closes the stream.
 *
 * The manager uses [AssetPackSource] for the bundled pack and [HttpPackSource] for finer ones; both go through the same
 * checksum check and unpacking into `filesDir/geodata`.
 */
fun interface PackSource {
    fun open(name: String): InputStream
}

/**
 * Opens the packs shipped in the APK; the only source of [GeoPacks.BUNDLED_LEVEL]. The gzipped pack `<x>.bin.gz` is
 * stored as `assets/geodata/<x>.pack` with the same bytes: the Android build unpacks assets ending in `.gz` and drops
 * the suffix, so the checksum of the gzipped file would no longer match.
 */
class AssetPackSource(private val context: Context) : PackSource {
    override fun open(name: String): InputStream = context.assets.open("geodata/" + assetName(name))

    companion object {
        /** The asset name of the release asset [name]: `places-15000.bin.gz` is `places-15000.pack`. */
        fun assetName(name: String): String = name.removeSuffix(".bin.gz") + ".pack"
    }
}

/** Downloads from [baseUrl] (ends with a slash) over HTTP(S), like the updater does. */
class HttpPackSource(private val baseUrl: () -> String) : PackSource {

    override fun open(name: String): InputStream {
        val connection = URL(baseUrl() + name).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("User-Agent", "Glacier")
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) throw IOException("Download of $name answered $status")
            return object : FilterInputStream(connection.inputStream) {
                override fun close() {
                    try {
                        super.close()
                    } finally {
                        connection.disconnect()
                    }
                }
            }
        } catch (e: IOException) {
            connection.disconnect()
            throw e
        }
    }

    private companion object {
        const val TIMEOUT_MS = 30_000
    }
}

/**
 * Debug builds only: points the pack download at a local test server. The adb command
 * (`10.0.2.2` is the host as seen from the emulator; start `python -m http.server 8765` in a folder holding the
 * `places-<level>.bin.gz` files and a `places-<level>.bin.gz.sha256` next to each):
 *
 *     adb shell am start -n <package>/io.github.glacier_jellyfin.androidtv.MainActivity \
 *         -a io.github.glacier_jellyfin.androidtv.GEODATA_BASE_URL --es url http://10.0.2.2:8765/
 *
 * An empty `--es url ""` clears it. While it is set the checksum comes from the `.sha256` file instead of the pinned
 * table. Ignored by release builds, whatever is stored.
 */
@Singleton
class GeoDataDebug @Inject constructor(@ApplicationContext private val context: Context) {

    private val debuggable get() = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    private val prefs get() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The test server, with a trailing slash, or null; always null in a release build. */
    var baseUrl: String?
        get() = if (debuggable) prefs.getString(KEY_URL, null)?.takeIf { it.isNotBlank() } else null
        set(value) {
            if (!debuggable) return
            val url = value?.trim()?.takeIf { it.isNotEmpty() }?.let { if (it.endsWith("/")) it else "$it/" }
            prefs.edit { if (url == null) remove(KEY_URL) else putString(KEY_URL, url) }
        }

    private companion object {
        const val PREFS = "geodata_debug"
        const val KEY_URL = "base_url"
    }
}
