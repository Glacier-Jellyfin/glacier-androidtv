package io.github.glacier_jellyfin.androidtv.core.data.geodata

import java.util.IllformedLocaleException
import java.util.Locale

/** Writes a [Place] as "place, region, country". */
object PlaceFormatter {

    /**
     * The place, its admin1 region and the country in the language of [locale], joined by ", ". The region is left out
     * when it only repeats the place, see [repeats]. Missing parts are skipped. Right-to-left languages are handled by
     * the text layout, the order is the same.
     */
    fun format(place: Place, locale: Locale = Locale.getDefault()): String {
        val admin1 = place.admin1?.takeUnless { place.repeatedBy(it) }
        return listOfNotNull(place.name, admin1, place.countryCode?.let { countryName(it, locale) })
            .filter { it.isNotBlank() }
            .joinToString(", ")
    }

    private fun Place.repeatedBy(admin1: String): Boolean {
        val regions = listOfNotNull(admin1, plainAdmin1)
        return listOf(name, plainName).any { place -> regions.any { repeats(place, it) } }
    }

    /**
     * [region] repeats [place] when the words of the place (split at whitespace only, so "Baden" is not part of
     * "Baden-Württemberg") appear in the region as a run, in any case: "Berlin" in "State of Berlin", "Hamburg" in
     * "Freie und Hansestadt Hamburg". "Brandenburg an der Havel" is not in "Brandenburg", so that one stays.
     */
    private fun repeats(place: String, region: String): Boolean {
        val words = place.lowercase().split(whitespace).filter { it.isNotEmpty() }
        val text = region.lowercase().split(whitespace).filter { it.isNotEmpty() }
        if (words.isEmpty()) return false
        return (0..text.size - words.size).any { start -> text.subList(start, start + words.size) == words }
    }

    private val whitespace = Regex("""\s+""")

    /** The name of the country with [code] in the language of [locale]; the code itself when Android does not know it. */
    fun countryName(code: String, locale: Locale): String {
        val region = try {
            Locale.Builder().setRegion(code).build()
        } catch (_: IllformedLocaleException) {
            return code
        }
        return region.getDisplayCountry(locale).takeIf { it.isNotBlank() } ?: code
    }
}
