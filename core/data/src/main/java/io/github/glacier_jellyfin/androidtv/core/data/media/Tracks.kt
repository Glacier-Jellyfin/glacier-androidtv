package io.github.glacier_jellyfin.androidtv.core.data.media

import java.util.Locale

/** An audio or subtitle stream of a title, as offered in the track picker. */
data class Track(
    /** Stream index on the server; passed to playback. */
    val index: Int,
    /** ISO 639-2 code as the server reports it ("ger", "eng"), null when unknown. */
    val language: String?,
    val codec: String?,
    val channels: Int?,
    val forced: Boolean = false,
    val hearingImpaired: Boolean = false,
    /** The server's own description, used when the language is unknown. */
    val fallbackTitle: String? = null,
)

/** Audio and subtitle choices of a title, with the server's defaults for this user. */
data class TrackChoices(
    val audio: List<Track>,
    val subtitles: List<Track>,
    val defaultAudio: Int?,
    /** Null means subtitles off. */
    val defaultSubtitle: Int?,
)

object Languages {
    /**
     * ISO 639-2/B (bibliographic) codes that Java only knows by their
     * terminological /T form. Jellyfin reports whichever the file contains.
     */
    private val bibliographic = mapOf(
        "alb" to "sqi", "arm" to "hye", "baq" to "eus", "bur" to "mya", "chi" to "zho", "cze" to "ces",
        "dut" to "nld", "fre" to "fra", "geo" to "kat", "ger" to "deu", "gre" to "ell", "ice" to "isl",
        "mac" to "mkd", "mao" to "mri", "may" to "msa", "per" to "fas", "rum" to "ron", "slo" to "slk",
        "tib" to "bod", "wel" to "cym",
    )

    private val byIso3: Map<String, Locale> by lazy {
        Locale.getISOLanguages().associate { Locale.forLanguageTag(it).let { locale -> locale.isO3Language to locale } }
    }

    /** Normalises "ger" / "deu" / "de" to the ISO 639-2/T code, or null if unknown. */
    fun iso3(code: String?): String? {
        val value = code?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && it != "und" } ?: return null
        if (value.length == 2) return Locale.forLanguageTag(value).isO3Language.takeIf { it.isNotEmpty() }
        val normal = bibliographic[value] ?: value
        return normal.takeIf { byIso3.containsKey(it) }
    }

    /** Two-letter code for compact labels ("de"), or null if unknown. */
    fun iso2(code: String?): String? = iso3(code)?.let { byIso3[it]?.language }

    /** Language name in the display language, e.g. "Deutsch" / "German". */
    fun name(code: String?, display: Locale = Locale.getDefault()): String? {
        val iso3 = iso3(code) ?: return null
        val name = byIso3[iso3]?.getDisplayLanguage(display)?.takeIf { it.isNotEmpty() } ?: return null
        return name.replaceFirstChar { it.titlecase(display) }
    }
}

/** "2.0", "5.1", "7.1" from a channel count; five or more channels carry an LFE. */
fun channelLayout(channels: Int?): String? = when {
    channels == null || channels <= 0 -> null
    channels >= 5 -> "${channels - 1}.1"
    else -> "$channels.0"
}

/** Short codec names as the design writes them: "DTS", "AAC", "EAC3", "TrueHD". */
fun codecName(codec: String?): String? = when (val c = codec?.lowercase()) {
    null, "" -> null
    "truehd" -> "TrueHD"
    "dca" -> "DTS"
    "opus", "vorbis" -> c.replaceFirstChar { it.uppercase() }
    else -> c.uppercase()
}
