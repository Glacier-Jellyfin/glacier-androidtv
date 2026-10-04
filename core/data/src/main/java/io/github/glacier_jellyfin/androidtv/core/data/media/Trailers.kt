package io.github.glacier_jellyfin.androidtv.core.data.media

import java.net.URI
import java.util.UUID

/** What a title's "Trailer" button can play. */
data class Trailers(
    /** Trailer files next to the title on the server; listed with [DetailRepository.localTrailers]. */
    val localCount: Int,
    /** YouTube links from the metadata (TMDb); other remote links cannot be played. */
    val youTube: List<YouTubeTrailer>,
) {
    val any: Boolean get() = localCount > 0 || youTube.isNotEmpty()
}

sealed interface Trailer {
    /** As the server or the metadata names it ("Official Trailer"); null when unnamed. */
    val name: String?
    val imageUrl: String?
}

/** A trailer file on the server, played like any other video. */
data class LocalTrailer(
    val id: UUID,
    override val name: String?,
    val durationMs: Long?,
    override val imageUrl: String?,
) : Trailer

/** Played in YouTube's embedded player. */
data class YouTubeTrailer(val videoId: String, override val name: String?) : Trailer {
    // The id is written into the player page's script, so nothing else may get through.
    init {
        require(isYouTubeId(videoId)) { "Not a YouTube video id: $videoId" }
    }

    override val imageUrl: String get() = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
}

/**
 * The video id of a YouTube link (watch, youtu.be, embed and shorts forms),
 * or null for anything else.
 */
fun youTubeId(url: String): String? {
    val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
    val host = uri.host?.lowercase()?.removePrefix("www.")?.removePrefix("m.") ?: return null
    val path = uri.path.orEmpty().trim('/').split('/')
    val id = when (host) {
        "youtu.be" -> path.firstOrNull()
        "youtube.com", "music.youtube.com", "youtube-nocookie.com" -> when (path.firstOrNull()) {
            "watch" -> uri.rawQuery.orEmpty().split('&').firstNotNullOfOrNull { param ->
                param.split('=', limit = 2).takeIf { it.size == 2 && it[0] == "v" }?.get(1)
            }
            "embed", "shorts", "v", "live" -> path.getOrNull(1)
            else -> null
        }
        else -> null
    }
    return id?.takeIf(::isYouTubeId)
}

/** Eleven characters of A-Z, a-z, 0-9, "_" and "-". */
fun isYouTubeId(id: String): Boolean = YOUTUBE_ID.matches(id)

private val YOUTUBE_ID = Regex("[A-Za-z0-9_-]{11}")
