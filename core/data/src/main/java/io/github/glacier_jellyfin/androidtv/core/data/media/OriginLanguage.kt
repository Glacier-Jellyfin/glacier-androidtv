package io.github.glacier_jellyfin.androidtv.core.data.media

import java.util.Locale

/**
 * The language a title was made in, guessed from the countries it was produced in. Pure Kotlin.
 * Countries are named in English, as TMDb gives them ("United States of America") and as
 * MusicBrainz does for artists ("United States", "Berlin, Germany").
 */
object OriginLanguage {

    private val single: Map<String, List<String>> = mapOf(
        "en" to listOf(
            "united states of america", "united states", "usa", "us", "american", "united kingdom", "uk",
            "great britain", "britain", "british", "england", "scotland", "wales", "northern ireland",
            "ireland", "australia", "new zealand",
        ),
        "de" to listOf(
            "germany",
            "german",
            "austria",
            "liechtenstein",
            "federal republic of germany",
            "west germany",
            "east germany",
            "german democratic republic",
        ),
        "fr" to listOf("france", "french", "monaco"),
        "es" to listOf(
            "spain", "mexico", "argentina", "chile", "colombia", "peru", "venezuela", "cuba", "uruguay",
            "ecuador", "bolivia", "paraguay", "costa rica", "panama", "guatemala", "dominican republic",
            "honduras", "el salvador", "nicaragua",
        ),
        "it" to listOf("italy", "italian", "san marino", "vatican city"),
        "pt" to listOf("brazil", "portugal", "angola", "mozambique", "cape verde", "guinea-bissau"),
        "nl" to listOf("netherlands", "the netherlands", "holland"),
        "sv" to listOf("sweden"),
        "da" to listOf("denmark"),
        "no" to listOf("norway"),
        "hu" to listOf("hungary"),
        "ro" to listOf("romania", "moldova"),
        "el" to listOf("greece"),
        "ca" to listOf("andorra"),
        // Languages without free-standing articles: known, so their titles are left as they are.
        "ja" to listOf("japan"),
        "zh" to listOf("china", "hong kong", "taiwan"),
        "ko" to listOf("south korea", "korea", "north korea"),
        "ru" to listOf("russia", "russian federation", "soviet union", "ussr"),
        "pl" to listOf("poland"),
        "cs" to listOf("czech republic", "czechia", "czechoslovakia"),
        "sk" to listOf("slovakia"),
        "tr" to listOf("turkey", "türkiye"),
        "is" to listOf("iceland"),
        "hr" to listOf("croatia"),
        "sr" to listOf("serbia"),
        "sl" to listOf("slovenia"),
        "bg" to listOf("bulgaria"),
        "uk" to listOf("ukraine"),
        "th" to listOf("thailand"),
        "vi" to listOf("vietnam"),
        "id" to listOf("indonesia"),
        "fa" to listOf("iran"),
        "lt" to listOf("lithuania"),
        "lv" to listOf("latvia"),
        "et" to listOf("estonia"),
    )

    /** Countries with several languages. India, South Africa, Singapore and the like are left out: they decide nothing. */
    private val several: Map<String, Set<String>> = mapOf(
        "canada" to setOf("en", "fr"),
        "switzerland" to setOf("de", "fr", "it"),
        "belgium" to setOf("nl", "fr", "de"),
        "luxembourg" to setOf("lb", "fr", "de"),
        "south africa" to setOf("af", "en"),
        "finland" to setOf("fi", "sv"),
        "cyprus" to setOf("el", "tr"),
        "israel" to setOf("he", "ar", "en"),
    )

    private val languagesByCountry: Map<String, Set<String>> = buildMap {
        for ((language, countries) in single) for (country in countries) put(country, setOf(language))
        putAll(several)
    }

    /** The languages of one entry of a title's production locations; null when it is not a country we know. */
    fun languagesOf(location: String): Set<String>? {
        val last = location.substringAfterLast(',').trim().lowercase(Locale.ROOT)
        return languagesByCountry[last]
    }

    /**
     * The one language [locations] agree on: what is left when the languages of every entry we know
     * are intersected. Entries we do not know are ignored. Null when none is known, or when the
     * entries leave none or several ("Switzerland", "Germany" with "France").
     */
    fun of(locations: List<String>?): String? {
        var left: Set<String>? = null
        for (location in locations.orEmpty()) {
            val languages = languagesOf(location) ?: continue
            left = left?.intersect(languages) ?: languages
        }
        return left?.singleOrNull()
    }
}
