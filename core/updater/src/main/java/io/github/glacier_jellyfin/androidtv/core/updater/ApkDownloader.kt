package io.github.glacier_jellyfin.androidtv.core.updater

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Why an update could not be downloaded or installed. */
enum class UpdateError {
    /** The release feed or the download could not be reached. */
    Network,
    /** The release carries no SHA-256 digest, or the file does not match it. */
    Checksum,
    /** The file is not this app, or not the version the release promised. */
    Package,
    /** Android refused or failed the installation. */
    Install,
}

class UpdateException(val error: UpdateError, message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Downloads a release APK into the cache and checks it before anyone installs
 * it: GitHub's SHA-256 digest, then package name and versionCode. The signing
 * key is left to Android, which refuses an update signed by anyone else.
 */
internal class ApkDownloader(private val context: Context) {

    private val dir get() = File(context.cacheDir, "updates")

    /** Removes downloads; call once the app runs a version at least as new as them. */
    fun clear() {
        dir.deleteRecursively()
    }

    suspend fun download(candidate: UpdateCandidate, onProgress: (Long) -> Unit): File = withContext(Dispatchers.IO) {
        val expected = expectedSha256(candidate.apk)
        dir.mkdirs()
        val target = File(dir, candidate.apk.name)
        val partial = File(dir, candidate.apk.name + ".part")
        // A finished download from earlier is reused when it still matches.
        if (target.exists() && sha256(target) == expected) return@withContext target.also { verifyPackage(it, candidate.version) }

        val digest = MessageDigest.getInstance("SHA-256")
        // Like the feed, a local test release may use file:// addresses.
        val connection = URL(candidate.apk.downloadUrl).openConnection()
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("User-Agent", "Glacier")
            if (connection is HttpURLConnection) {
                val status = try {
                    connection.responseCode
                } catch (e: IOException) {
                    throw UpdateException(UpdateError.Network, "Download failed", e)
                }
                if (status != HttpURLConnection.HTTP_OK) throw UpdateException(UpdateError.Network, "Download answered $status")
            }
            var done = 0L
            try {
                connection.getInputStream().use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(BUFFER)
                        while (true) {
                            ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            done += read
                            onProgress(done)
                        }
                    }
                }
            } catch (e: IOException) {
                partial.delete()
                throw UpdateException(UpdateError.Network, "Download interrupted", e)
            }
        } finally {
            (connection as? HttpURLConnection)?.disconnect()
        }

        if (digest.digest().toHex() != expected) {
            partial.delete()
            throw UpdateException(UpdateError.Checksum, "SHA-256 of ${candidate.apk.name} does not match the release")
        }
        target.delete()
        if (!partial.renameTo(target)) throw UpdateException(UpdateError.Network, "Cannot store the download")
        try {
            verifyPackage(target, candidate.version)
        } catch (e: UpdateException) {
            target.delete()
            throw e
        }
        target
    }

    /** Package name and versionCode inside the APK must be exactly what the release promised. */
    private fun verifyPackage(file: File, version: AppVersion) {
        val manager = context.packageManager
        val archive = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            manager.getPackageArchiveInfo(file.path, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            manager.getPackageArchiveInfo(file.path, 0)
        }
        val info = archive ?: throw UpdateException(UpdateError.Package, "Not a readable APK")
        if (info.packageName != context.packageName) {
            throw UpdateException(UpdateError.Package, "APK is ${info.packageName}, expected ${context.packageName}")
        }
        if (info.longVersionCode != version.versionCode.toLong()) {
            throw UpdateException(UpdateError.Package, "APK has versionCode ${info.longVersionCode}, expected ${version.versionCode}")
        }
    }

    private fun expectedSha256(asset: ReleaseAsset): String {
        val digest = asset.digest?.takeIf { it.startsWith(SHA256_PREFIX) }?.removePrefix(SHA256_PREFIX)?.lowercase()
        return digest?.takeIf { it.length == 64 } ?: throw UpdateException(UpdateError.Checksum, "Release has no SHA-256 digest for ${asset.name}")
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private companion object {
        const val SHA256_PREFIX = "sha256:"
        const val TIMEOUT_MS = 30_000
        const val BUFFER = 64 * 1024
    }
}
