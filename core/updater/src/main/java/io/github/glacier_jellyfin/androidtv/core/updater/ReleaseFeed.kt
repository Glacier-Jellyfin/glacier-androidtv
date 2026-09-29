package io.github.glacier_jellyfin.androidtv.core.updater

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Reads Glacier's releases from the GitHub releases API
 * (`GET /repos/{owner}/{repo}/releases`), newest first.
 *
 * [url] is the full API address; debug builds may point it at a local
 * copy, also as a `file://` address (see docs/RELEASING.md).
 */
class ReleaseFeed(private val url: String) {

    suspend fun releases(): List<Release> = withContext(Dispatchers.IO) {
        // Not always HTTP: a local test feed may be a file:// address.
        val connection = URL(url).openConnection()
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            connection.setRequestProperty("User-Agent", "Glacier")
            if (connection is HttpURLConnection && connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("Release feed answered ${connection.responseCode}")
            }
            parse(connection.getInputStream().use { it.readBytes().decodeToString() })
        } finally {
            (connection as? HttpURLConnection)?.disconnect()
        }
    }

    companion object {
        const val GITHUB = "https://api.github.com/repos/Glacier-Jellyfin/glacier-androidtv/releases?per_page=30"

        private const val TIMEOUT_MS = 15_000

        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): List<Release> =
            json.decodeFromString<List<GitHubRelease>>(text).map { it.toRelease() }
    }
}

@Serializable
private data class GitHubRelease(
    @SerialName("tag_name") val tagName: String,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val body: String? = null,
    @SerialName("published_at") val publishedAt: String? = null,
    val assets: List<GitHubAsset> = emptyList(),
) {
    fun toRelease() = Release(
        tag = tagName,
        isDraft = draft,
        isPrerelease = prerelease,
        assets = assets.map { ReleaseAsset(it.name, it.downloadUrl, it.size, it.digest) },
        body = body.orEmpty(),
        publishedAt = publishedAt,
    )
}

@Serializable
private data class GitHubAsset(
    val name: String,
    @SerialName("browser_download_url") val downloadUrl: String,
    val size: Long = 0,
    val digest: String? = null,
)
