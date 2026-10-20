package io.github.glacier_jellyfin.androidtv.core.data.media

import kotlinx.serialization.Serializable

/**
 * One file of a title that has several (Jellyfin: media sources): the same
 * film in 2160p and 1080p, or another cut such as a Director's Cut. Each file
 * has its own tracks, seek previews and length.
 */
data class MediaVersion(
    /** The media source id. */
    val id: String,
    /** The server's name, taken from the file name ("1080p", "Director's Cut"). */
    val name: String?,
    /** The cut this file holds ("Director's Cut", "Noir"); null for the main one. See [editionOf]. */
    val edition: String?,
    val width: Int?,
    val height: Int?,
    val quality: VideoQuality?,
    /** Jellyfin codec name: h264, hevc, av1, ... */
    val videoCodec: String?,
    val sizeBytes: Long?,
    val runtimeMs: Long?,
    val tracks: TrackChoices?,
    val trickplay: Trickplay?,
) {
    val resolution: Resolution get() = Resolution.of(width, height)

    val key: VersionKey get() = VersionKey(resolution, hdr = quality?.hdr != null, edition = edition)

    /** Lines, as files are named: "2160p", "1080p", "720p"; below that the real height ("576p"). */
    val sizeLabel: String
        get() = when (resolution) {
            Resolution.Uhd -> "2160p"
            Resolution.FullHd -> "1080p"
            Resolution.Hd -> "720p"
            Resolution.Sd -> height?.takeIf { it > 0 }?.let { "${it}p" } ?: "SD"
        }
}

/** Picture size classes, as people name versions. */
@Serializable
enum class Resolution {
    Sd, Hd, FullHd, Uhd;

    companion object {
        /** By width first: a 2.39:1 film in 1920×800 is still 1080p. */
        fun of(width: Int?, height: Int?): Resolution {
            val w = width ?: 0
            val h = height ?: 0
            return when {
                w >= 3200 || h >= 1800 -> Uhd
                w >= 1700 || h >= 1000 -> FullHd
                w >= 1200 || h >= 700 -> Hd
                else -> Sd
            }
        }
    }
}

/**
 * The version picked for a movie or a whole show. A movie keeps the very file
 * ([id]); a show keeps the kind of file, since each episode has files of its own.
 */
@Serializable
data class VersionKey(
    val resolution: Resolution,
    val hdr: Boolean = false,
    /** The cut, see [MediaVersion.edition]; null for the main one. */
    val edition: String? = null,
    val id: String? = null,
)

/**
 * The cut a file holds, from the name the server gave it: what is left once
 * sizes, picture formats and codecs are taken out ("Director's Cut 1080p" →
 * "Director's Cut"). Null for the main file: a name of sizes alone ("2160p"),
 * or one that repeats the [title] (a file without a suffix).
 */
fun editionOf(name: String?, title: String?): String? {
    val trimmed = name?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (title != null && title.length >= 3 && trimmed.contains(title.trim(), ignoreCase = true)) return null
    return TECH_WORDS.replace(trimmed, " ").replace(SEPARATORS, " ").trim().ifEmpty { null }
}

private val TECH_WORDS = Regex(
    """(?i)\b(\d{3,4}[pi]|[48]k|uhd|full ?hd|fhd|hd|sd|sdr|hdr10\+?|hdr|hlg|dv|dovi|dolby vision|remux|hevc|h\.?26[45]|x26[45]|avc|av1)(?!\w)""",
)
private val SEPARATORS = Regex("""[\s\-_.\[\]()]+""")

/**
 * The version to play.
 * 1. The file picked for the movie, else the kind picked for the show: of its
 *    cut, same size and range, else same size, else the best of that cut (2.).
 * 2. Else of the main cut (no name, else the server's first) the largest that
 *    plays without converting and fits the screen. None fits: the smallest
 *    that plays without converting. Nothing plays without converting (or it
 *    is not known): the same by size alone. A cut is never changed for size.
 * Of equal ones the server's order wins.
 * [direct]: ids of the versions the device plays as they are; null when not known.
 */
fun pickVersion(versions: List<MediaVersion>, picked: VersionKey?, direct: Set<String>?, screen: Resolution): MediaVersion? {
    if (versions.size < 2) return versions.firstOrNull()
    if (picked != null) {
        picked.id?.let { id -> versions.firstOrNull { it.id == id }?.let { return it } }
        val cut = versions.filter { it.edition.equals(picked.edition, ignoreCase = true) }
        if (cut.isNotEmpty()) {
            cut.firstOrNull { it.resolution == picked.resolution && (it.quality?.hdr != null) == picked.hdr }?.let { return it }
            cut.firstOrNull { it.resolution == picked.resolution }?.let { return it }
            return bestFitting(cut, direct, screen)
        }
    }
    val main = versions.filter { it.edition == null }.ifEmpty { versions.filter { it.edition == versions.first().edition } }
    return bestFitting(main, direct, screen)
}

private fun bestFitting(versions: List<MediaVersion>, direct: Set<String>?, screen: Resolution): MediaVersion? {
    val candidates = direct?.let { ids -> versions.filter { it.id in ids } }?.ifEmpty { null } ?: versions
    val fitting = candidates.filter { it.resolution <= screen }
    return fitting.maxByOrNull { it.resolution } ?: candidates.minByOrNull { it.resolution }
}

/**
 * Short names by version id, for the chip and the list.
 * Sizes alone: "2160p", "1080p". With cuts: the cut's name, [theatrical] for
 * the main one, its size added where a cut has several files ("Theatrical 2160p").
 * Where two still read the same, the picture range tells them apart ("2160p HDR",
 * "2160p SDR"), else a number.
 */
fun versionLabels(versions: List<MediaVersion>, theatrical: String): Map<String, String> {
    val cuts = versions.any { it.edition != null }
    var labels = versions.associate { it.id to if (cuts) it.edition ?: theatrical else it.sizeLabel }
    val size: (MediaVersion) -> String = { it.sizeLabel }
    val range: (MediaVersion) -> String = { rangeLabel(it.quality?.hdr) }
    val details = listOfNotNull(size.takeIf { cuts }, range)
    for (detail in details) {
        val before = labels
        labels = versions.associate { v ->
            val label = before.getValue(v.id)
            v.id to if (before.values.count { it == label } > 1) "$label ${detail(v)}" else label
        }
    }
    val numbered = labels
    return versions.associate { v ->
        val label = numbered.getValue(v.id)
        val same = versions.filter { numbered[it.id] == label }
        v.id to if (same.size > 1) "$label ${same.indexOf(v) + 1}" else label
    }
}

private fun rangeLabel(hdr: HdrFormat?): String = when (hdr) {
    HdrFormat.Hdr10 -> "HDR"
    HdrFormat.Hdr10Plus -> "HDR10+"
    HdrFormat.Hlg -> "HLG"
    HdrFormat.DolbyVision -> "DV"
    null -> "SDR"
}

/**
 * The details as the version [id] plays them: its tracks, its seek previews,
 * its picture format and length. Unknown ids leave the details as they are.
 */
fun ItemDetails.forVersion(id: String?): ItemDetails {
    val version = versions.firstOrNull { it.id == id } ?: return this
    val runtimeMs = version.runtimeMs ?: item.runtimeMs
    val position = item.resumePositionMs
    return copy(
        item = item.copy(
            quality = version.quality,
            runtimeMs = runtimeMs,
            runtimeMinutes = runtimeMs?.let { (it / MS_PER_MINUTE).toInt() } ?: item.runtimeMinutes,
            // Jellyfin keeps one resume point for all files; how much is left depends on the file.
            remainingMinutes = if (runtimeMs != null && position > 0) ((runtimeMs - position).coerceAtLeast(0) / MS_PER_MINUTE).toInt() else item.remainingMinutes,
            progress = if (runtimeMs != null && runtimeMs > 0 && position > 0) (position.toFloat() / runtimeMs).coerceIn(0f, 1f) else item.progress,
        ),
        tracks = version.tracks,
        trickplay = version.trickplay,
    )
}

private const val MS_PER_MINUTE = 60_000L
