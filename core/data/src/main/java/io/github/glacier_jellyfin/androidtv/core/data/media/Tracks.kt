package io.github.glacier_jellyfin.androidtv.core.data.media

import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType
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
    /** Closed captions: the dialogue with sounds, often from a TV broadcast. */
    val closedCaptions: Boolean = false,
    /** The server's own description, used when the language is unknown. */
    val fallbackTitle: String? = null,
    /** The stream's name in the file ("German (CR/ASS)", "Commentary"), if it has one. */
    val title: String? = null,
    /** The language's variant as a region code ("CA", "BR", "419") or a script ("Hant"), from the language tag or the stream name. */
    val region: String? = null,
    /** The codec's profile ("DTS-HD MA", "Dolby TrueHD + Dolby Atmos"). */
    val profile: String? = null,
    /** Marked as default in the file. */
    val default: Boolean = false,
    /** A file next to the video, not a stream in it. */
    val external: Boolean = false,
    /** An audio commentary, known from the stream name only. */
    val commentary: Boolean = false,
    /** An audio description for the blind, known from the stream name only. */
    val audioDescription: Boolean = false,
) {
    /** Commentary and audio description are extras, never the track to start with on their own. */
    val extra: Boolean get() = commentary || audioDescription
}

internal fun MediaStream.toTrack(): Track {
    val subtitle = type == MediaStreamType.SUBTITLE
    val audio = type == MediaStreamType.AUDIO
    val captions = subtitle && (closedCaptionsByName(title) || codec?.lowercase() in CAPTION_CODECS)
    return Track(
        index = index,
        language = cantoneseOr(language, title),
        codec = codec,
        channels = channels,
        forced = isForced || (subtitle && forcedByName(title)),
        // The server's hearing impaired flag on a track named CC means its closed captions.
        hearingImpaired = (isHearingImpaired && !captions) || (subtitle && hearingImpairedByName(title)),
        closedCaptions = captions,
        fallbackTitle = displayTitle ?: title,
        title = title,
        profile = profile,
        region = regionOf(language, title),
        default = isDefault,
        external = isExternal,
        commentary = audio && commentaryByName(title),
        audioDescription = audio && audioDescriptionByName(title),
    )
}

// Some files only say it in the stream name, without the stream's flag. The
// Glacier web plugin reads names the same way (glacier-details.js).

/** "German Forced", "Signs & Songs". */
internal fun forcedByName(title: String?): Boolean = hasWord(title, FORCED_WORDS)

/** "English SDH", "Hörgeschädigt". */
internal fun hearingImpairedByName(title: String?): Boolean = hasWord(title, SDH_WORDS)

/** "English CC", "Closed Captions". */
internal fun closedCaptionsByName(title: String?): Boolean = hasWord(title, CC_WORDS)

/** "Director's Commentary", "Audiokommentar". */
internal fun commentaryByName(title: String?): Boolean = hasWord(title, COMMENTARY_WORDS)

/** "Audio Description", "Hörfilm", "AD". */
internal fun audioDescriptionByName(title: String?): Boolean = hasWord(title, DESCRIPTION_WORDS)

private val FORCED_WORDS = listOf("forced", "erzwungen", "signs")
private val SDH_WORDS = listOf("sdh", "hörgeschädigt", "hearing impaired")
private val CC_WORDS = listOf("closed captions", "closed caption", "cc")
private val CAPTION_CODECS = setOf("eia_608", "eia_708", "cc_dec")
private val COMMENTARY_WORDS = listOf("director's commentary", "audiokommentar", "commentary", "kommentar")
private val DESCRIPTION_WORDS = listOf(
    "audio descriptions", "audio description", "audiodescriptions", "audiodescription", "audiodeskription",
    "descriptive audio", "descriptive", "described", "visually impaired", "sehbehindert", "hörfilmfassung", "hörfilm", "ad",
)

/**
 * Variants of a language and the words stream names use for them ("BTM CA",
 * "Portuguese (Brazilian)"). Only words that fit the stream's language count:
 * "CA" on a French track is Canada.
 */
private val REGIONS = mapOf(
    "fra" to listOf(
        "CA" to listOf("canada", "canadian", "kanada", "kanadisch", "québec", "quebec", "québécois", "quebecois", "vfq", "ca"),
        "BE" to listOf("belgique", "belgian", "belgium", "belgisch", "belgien", "vfb"),
        "CH" to listOf("suisse", "swiss", "schweiz", "schweizer"),
        "FR" to listOf("france", "frankreich", "parisian", "european", "vff", "fr"),
    ),
    "por" to listOf(
        "BR" to listOf("brazil", "brazilian", "brasil", "brasilien", "brasilianisch", "br"),
        "PT" to listOf("portugal", "european", "europeu", "pt"),
    ),
    "spa" to listOf(
        "AR" to listOf("argentina", "argentine", "argentinian", "argentinien", "argentinisch", "rioplatense"),
        "419" to listOf("latin america", "latin american", "latam", "latino", "lateinamerika", "lateinamerikanisch", "419", "la"),
        "MX" to listOf("mexico", "méxico", "mexican", "mexikanisch", "mx"),
        "ES" to listOf("spain", "españa", "castilian", "castellano", "kastilisch", "european", "es"),
    ),
    "eng" to listOf(
        "US" to listOf("american", "amerikanisch", "usa", "us"),
        "GB" to listOf("british", "britisch", "uk", "gb"),
        "AU" to listOf("australian", "australia", "australien", "australisch"),
        "CA" to listOf("canadian", "canada", "kanada", "kanadisch"),
    ),
    "deu" to listOf(
        "AT" to listOf("austrian", "austria", "österreich", "österreichisch"),
        "CH" to listOf("swiss", "schweiz", "schweizer", "schweizerdeutsch", "schwiizerdütsch", "ch"),
    ),
    "nld" to listOf(
        "BE" to listOf("flemish", "vlaams", "flämisch", "belgian", "belgium", "belgië", "belgien", "belgisch", "be"),
    ),
    // Chinese names its script, not a country: traditional and simplified (USER's call).
    "zho" to listOf(
        "Hant" to listOf("traditional", "traditionell", "hant", "繁體", "繁体"),
        "Hans" to listOf("simplified", "vereinfacht", "hans", "简体", "簡體"),
        "TW" to listOf("taiwan", "tw"),
        "HK" to listOf("hong kong", "hongkong", "hk"),
        "CN" to listOf("mainland", "cn"),
    ),
)

/** A Chinese track named Cantonese is in Cantonese ("yue"), a language of its own. */
internal fun cantoneseOr(language: String?, title: String?): String? =
    if (Languages.iso3(language) == "zho" && hasWord(title, CANTONESE_WORDS)) "yue" else language

private val CANTONESE_WORDS = listOf("cantonese", "kantonesisch", "yue", "粵語", "粤语")

/** The region of a track's language: from a tag ("pt-BR"), else from words in its name. */
internal fun regionOf(language: String?, title: String?): String? {
    Languages.region(language)?.let { return it }
    val regions = REGIONS[Languages.iso3(language)] ?: return null
    return regions.firstOrNull { (_, words) -> hasWord(title, words) }?.first
}

/** The words that named [region] in a stream name, so the facts leave them out. */
private fun regionWords(language: String?, region: String?): List<String> =
    REGIONS[Languages.iso3(language)]?.firstOrNull { it.first == region }?.second.orEmpty()

/**
 * The audio track to start with when the server picked [pick] on its own.
 * Files sometimes mark a commentary or audio description as default. Then
 * the main track of the same language plays, else the first main track.
 */
fun mainInsteadOfExtra(pick: Int?, audio: List<Track>): Int? {
    val picked = audio.firstOrNull { it.index == pick } ?: return pick
    if (!picked.extra) return pick
    val main = audio.filter { !it.extra }
    val language = Languages.iso3(picked.language)
    return (main.firstOrNull { language != null && Languages.iso3(it.language) == language } ?: main.firstOrNull())?.index ?: pick
}

/**
 * The subtitle to start with when the server picked [pick] on its own. A
 * forced track only shows signs and foreign lines, so it reads as "no
 * subtitles" when the audio is in another language: files often mark it as
 * default, and the server follows that flag. Then the full track of the same
 * language plays instead. A forced track stays when it was asked for
 * ([onlyForced]) or when the audio is in its language (a dub with forced signs).
 */
fun fullInsteadOfForced(pick: Int?, subtitles: List<Track>, audio: Track?, onlyForced: Boolean): Int? {
    val picked = subtitles.firstOrNull { it.index == pick } ?: return pick
    if (!picked.forced || onlyForced) return pick
    val language = Languages.iso3(picked.language) ?: return pick
    if (Languages.iso3(audio?.language) == language) return pick
    val full = subtitles.filter { !it.forced && Languages.iso3(it.language) == language }
    return (full.firstOrNull { !it.hearingImpaired && !it.closedCaptions } ?: full.firstOrNull())?.index ?: pick
}

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
        // Not ISO 639-2, but what some tools write for Brazilian Portuguese.
        "pob" to "por",
    )

    private val byIso3: Map<String, Locale> by lazy {
        Locale.getISOLanguages().associate { Locale.forLanguageTag(it).let { locale -> locale.isO3Language to locale } }
    }

    /** Normalises "ger" / "deu" / "de" to the ISO 639-2/T code, or null if unknown. */
    fun iso3(code: String?): String? {
        // A tag's region ("pt-BR") does not change the language.
        val value = code?.trim()?.lowercase()?.replace('_', '-')?.substringBefore('-')?.takeIf { it.isNotEmpty() && it != "und" } ?: return null
        if (value.length == 2) return Locale.forLanguageTag(value).isO3Language.takeIf { it.isNotEmpty() }
        val normal = bibliographic[value] ?: value
        return normal.takeIf { byIso3.containsKey(it) || it in withoutIso2 }
    }

    /** Languages Java knows only by their three-letter code. */
    private val withoutIso2 = setOf("yue")

    private fun locale(iso3: String): Locale? = byIso3[iso3] ?: iso3.takeIf { it in withoutIso2 }?.let(Locale::forLanguageTag)

    /**
     * Flag for a language, as a file name in the app's flags/ assets ("gb",
     * "es-ct"): the country most people link with it. Region tags win
     * ("pt-BR" is Brazil). Null for languages without a clear country.
     */
    fun flag(code: String?, region: String? = null): String? {
        val value = code?.trim()?.lowercase()?.replace('_', '-') ?: return null
        val variant = region ?: region(value)
        if (variant != null) {
            regionFlags[iso2(value) + "-" + variant.lowercase()]?.let { return it }
            // A region without a flag file ("de-LI") keeps the language's flag.
            variant.lowercase().takeIf { it in countryFlags }?.let { return it }
        }
        return flags[iso3(value)]
    }

    /** The region or script of a language tag ("pt-BR" is "BR", "es-419" is "419", "zh-Hant" is "Hant"), or null without one. */
    fun region(code: String?): String? {
        val value = code?.trim()?.replace('_', '-') ?: return null
        if (value.equals("pob", ignoreCase = true)) return "BR"
        val part = value.split('-').drop(1).firstOrNull { part ->
            (part.length in 2..4 && part.all { it.isLetter() }) || (part.length == 3 && part.all { it.isDigit() })
        } ?: return null
        return if (part.length == 4) part.lowercase().replaceFirstChar { it.uppercase() } else part.uppercase()
    }

    private val flags = mapOf(
        "afr" to "za", "amh" to "et", "ara" to "sa", "aze" to "az", "bel" to "by", "ben" to "bd", "bos" to "ba",
        "bul" to "bg", "cat" to "es-ct", "ces" to "cz", "cym" to "gb-wls", "dan" to "dk", "deu" to "de",
        "ell" to "gr", "eng" to "gb", "est" to "ee", "eus" to "es-pv", "fas" to "ir", "fil" to "ph", "fin" to "fi",
        "fra" to "fr", "gle" to "ie", "glg" to "es-ga", "heb" to "il", "hin" to "in", "hrv" to "hr", "hun" to "hu",
        "hye" to "am", "ind" to "id", "isl" to "is", "ita" to "it", "jpn" to "jp", "kan" to "in", "kat" to "ge",
        "kaz" to "kz", "khm" to "kh", "kor" to "kr", "lao" to "la", "lav" to "lv", "lit" to "lt", "ltz" to "lu",
        "mal" to "in", "mar" to "in", "mkd" to "mk", "mlt" to "mt", "mon" to "mn", "msa" to "my", "mya" to "mm",
        "nep" to "np", "nld" to "nl", "nno" to "no", "nob" to "no", "nor" to "no", "pol" to "pl", "por" to "pt",
        "ron" to "ro", "rus" to "ru", "sin" to "lk", "slk" to "sk", "slv" to "si", "som" to "so", "spa" to "es",
        "sqi" to "al", "srp" to "rs", "swa" to "ke", "swe" to "se", "tam" to "in", "tel" to "in", "tgl" to "ph",
        "tha" to "th", "tur" to "tr", "ukr" to "ua", "urd" to "pk", "uzb" to "uz", "vie" to "vn", "yue" to "hk",
        "zho" to "cn", "zul" to "za",
    )

    /** The countries in the app's flags/ assets, for regions that bring their own flag. */
    private val countryFlags = setOf(
        "al", "am", "ar", "at", "au", "az", "ba", "bd", "be", "bg", "br", "by", "ca", "ch", "cn", "cz", "de", "dk",
        "ee", "es", "et", "fi", "fr", "gb", "ge", "gr", "hk", "hr", "hu", "id", "ie", "il", "in", "ir", "is", "it",
        "jp", "ke", "kh", "kr", "kz", "la", "lk", "lt", "lu", "lv", "mk", "mm", "mn", "mt", "mx", "my", "nl", "no",
        "np", "ph", "pk", "pl", "pt", "ro", "rs", "ru", "sa", "se", "si", "sk", "so", "th", "tr", "tw", "ua", "us",
        "uz", "vn", "za",
    )

    private val regionFlags = mapOf(
        "en-us" to "us", "es-419" to "mx", "es-mx" to "mx", "fr-ca" to "ca", "pt-br" to "br", "zh-hk" to "hk",
        "zh-tw" to "tw", "zh-hant" to "tw", "zh-hans" to "cn",
    )

    /** Two-letter code for compact labels ("de"), or null if unknown. */
    fun iso2(code: String?): String? = iso3(code)?.let { byIso3[it]?.language }

    /**
     * Language name in the display language, e.g. "Deutsch" / "German"; with
     * a [region] (or one in the tag) "Französisch (Kanada)".
     */
    fun name(code: String?, display: Locale = Locale.getDefault(), region: String? = null): String? {
        val language = iso3(code)?.let(::locale) ?: return null
        // The country the language's flag stands for needs no name: "Französisch", not "Französisch (Frankreich)".
        val variant = (region ?: region(code))?.takeUnless { it.equals(flags[iso3(code)], ignoreCase = true) }
        val locale = variant?.let {
            runCatching {
                val builder = Locale.Builder().setLanguage(language.language)
                if (it.length == 4) builder.setScript(it) else builder.setRegion(it)
                builder.build()
            }.getOrNull()
        } ?: language
        val name = locale.getDisplayName(display).takeIf { it.isNotEmpty() } ?: return null
        return name.replaceFirstChar { it.titlecase(display) }
    }
}

/** "2.0", "5.1", "7.1" from a channel count; five or more channels carry an LFE. */
fun channelLayout(channels: Int?): String? = when {
    channels == null || channels <= 0 -> null
    channels >= 5 -> "${channels - 1}.1"
    else -> "$channels.0"
}

/** Subtitle formats as people know them: "SRT", "ASS", "PGS", "VobSub". */
fun subtitleFormat(codec: String?): String? = when (val c = codec?.lowercase()) {
    null, "" -> null
    "subrip", "srt" -> "SRT"
    "ass" -> "ASS"
    "ssa" -> "SSA"
    "pgssub", "pgs", "hdmv_pgs_subtitle" -> "PGS"
    "dvdsub", "dvd_subtitle", "vobsub" -> "VobSub"
    "webvtt", "vtt" -> "WebVTT"
    "mov_text" -> "TX3G"
    else -> c.uppercase()
}

/** What sets a track apart beyond its language, shown as pills. */
enum class TrackMark { Forced, HearingImpaired, ClosedCaptions, Commentary, AudioDescription }

/**
 * A track as the pickers show it, the same way as the Glacier web plugin:
 * [name] is the language in the display language, [facts] the quieter
 * second line, [marks] the pills. External and default follow the facts in
 * the UI, which has the words for them.
 */
data class TrackDescription(val name: String, val facts: List<String>, val marks: List<TrackMark>)

/**
 * Audio facts: the format ("DTS-HD MA 5.1"), then the stream name where it
 * says more ("Director's Cut", not "DTS 5.1 @ 768 kbps" or "Stereo").
 * Subtitle facts: the stream name without what the row already says
 * ("German (CR/ASS)(forced)" becomes "CR/ASS"), then the format unless the
 * name has it. Without a language the stream name leads, else the format.
 */
fun describeTrack(track: Track, subtitle: Boolean, locales: List<Locale>): TrackDescription {
    val languageNames = (locales + Locale.ENGLISH).mapNotNull { Languages.name(track.language, it) }.distinct()
    val marks = buildList {
        if (subtitle && track.forced) add(TrackMark.Forced)
        if (subtitle && track.hearingImpaired) add(TrackMark.HearingImpaired)
        if (subtitle && track.closedCaptions) add(TrackMark.ClosedCaptions)
        if (!subtitle && track.commentary) add(TrackMark.Commentary)
        if (!subtitle && track.audioDescription) add(TrackMark.AudioDescription)
    }
    var title: String?
    var format: String?
    if (subtitle) {
        // "Full" only says the track is not the forced one, which the missing mark already says.
        val known = languageNames + regionWords(track.language, track.region) +
            listOf("forced", "erzwungen", "full", "vollständig", "komplett") +
            (if (track.hearingImpaired) SDH_WORDS else emptyList()) +
            if (track.closedCaptions) CC_WORDS else emptyList()
        title = cleanTrackTitle(track.title, known)
        format = subtitleFormat(track.codec)
    } else {
        // The format is in the facts already: "BTM TrueHD Atmos" says "BTM".
        val codec = audioFormat(track.codec, track.profile, channels = null)
        val known = languageNames + regionWords(track.language, track.region) + listOf("stereo", "mono", "surround") +
            listOfNotNull(codec) + codec?.split(' ').orEmpty() +
            (if (track.commentary) COMMENTARY_WORDS else emptyList()) +
            if (track.audioDescription) DESCRIPTION_WORDS else emptyList()
        // Names like "DTS 5.1 @ 768 kbps" only repeat the format.
        title = cleanTrackTitle(track.title, known)?.takeIf { name -> name.none { it.isDigit() } && name.count { it.isLetter() } >= 3 }
        format = audioFormat(track.codec, track.profile, track.channels)
    }
    var name = Languages.name(track.language, locales.firstOrNull() ?: Locale.getDefault(), track.region)
    if (name == null) {
        name = title ?: format ?: track.fallbackTitle ?: "—"
        if (name == title) title = null else format = null
    }
    val facts = if (subtitle) {
        listOfNotNull(title, format?.takeUnless { title != null && title.contains(it, ignoreCase = true) })
    } else {
        listOfNotNull(format, title)
    }
    return TrackDescription(name, facts, marks)
}

/**
 * The audio format as people know it, then the channels: "DTS-HD MA 5.1",
 * "TrueHD Atmos 7.1", "AAC 2.0". DTS names its kind in the profile.
 */
fun audioFormat(codec: String?, profile: String?, channels: Int?): String? {
    val c = codec?.lowercase().orEmpty()
    val p = profile?.trim().orEmpty()
    var name = if (c.startsWith("pcm")) "PCM" else codecName(codec)
    if (p.isNotEmpty() && (c == "dts" || c == "dca")) {
        name = p
    } else if (name != null && p.contains("atmos", ignoreCase = true)) {
        name += " Atmos"
    }
    return listOfNotNull(name, channelLayout(channels)).joinToString(" ").takeIf { it.isNotEmpty() }
}

/** [title] without the [known] words, empty brackets and stray separators; null when nothing is left. */
internal fun cleanTrackTitle(title: String?, known: List<String>): String? {
    var text = title ?: return null
    known.forEach { word -> text = replaceWord(text, word) }
    text = text.replace(EMPTY_BRACKETS, " ")
        .replace(BRACKETS, " ")
        .replace(SPACES, " ")
        .trim(' ', '-', '_', '·', ',', '/', '|', ':', '.')
    return text.takeIf { it.isNotEmpty() }
}

private val EMPTY_BRACKETS = Regex("[(\\[]\\s*[)\\]]")
private val BRACKETS = Regex("[()\\[\\]]")
private val SPACES = Regex("\\s+")

// Whole words, ignoring case, found without Regex: a pattern with \p{L} and
// IGNORE_CASE takes about 20 ms to compile on a Fire TV, so one per word and
// track held the track sheet back for seconds.

/** Whether [text] holds one of [words] as a whole word ("ad" in "German AD", not in "Advanced"). */
private fun hasWord(text: String?, words: List<String>): Boolean =
    text != null && words.any { wordAt(text, it, 0) >= 0 }

/** [text] with every whole-word [word] replaced by a space. */
private fun replaceWord(text: String, word: String): String {
    val out = StringBuilder()
    var from = 0
    while (true) {
        val at = wordAt(text, word, from)
        if (at < 0) break
        out.append(text, from, at).append(' ')
        from = at + word.length
    }
    return out.append(text, from, text.length).toString()
}

/** Where [word] next stands as a whole word in [text] from [from] on, or -1. */
private fun wordAt(text: String, word: String, from: Int): Int {
    var at = text.indexOf(word, from, ignoreCase = true)
    while (at >= 0) {
        val end = at + word.length
        if ((at == 0 || !text[at - 1].isLetter()) && (end == text.length || !text[end].isLetter())) return at
        at = text.indexOf(word, at + 1, ignoreCase = true)
    }
    return -1
}

/** Short codec names as the design writes them: "DTS", "AAC", "EAC3", "TrueHD". */
fun codecName(codec: String?): String? = when (val c = codec?.lowercase()) {
    null, "" -> null
    "truehd" -> "TrueHD"
    "dca" -> "DTS"
    "opus", "vorbis" -> c.replaceFirstChar { it.uppercase() }
    else -> c.uppercase()
}
