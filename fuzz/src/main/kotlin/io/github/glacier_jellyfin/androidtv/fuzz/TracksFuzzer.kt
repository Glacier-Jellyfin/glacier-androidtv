package io.github.glacier_jellyfin.androidtv.fuzz

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import io.github.glacier_jellyfin.androidtv.core.data.media.Languages
import io.github.glacier_jellyfin.androidtv.core.data.media.Track
import io.github.glacier_jellyfin.androidtv.core.data.media.audioDescriptionByName
import io.github.glacier_jellyfin.androidtv.core.data.media.cantoneseOr
import io.github.glacier_jellyfin.androidtv.core.data.media.cleanTrackTitle
import io.github.glacier_jellyfin.androidtv.core.data.media.closedCaptionsByName
import io.github.glacier_jellyfin.androidtv.core.data.media.commentaryByName
import io.github.glacier_jellyfin.androidtv.core.data.media.describeTrack
import io.github.glacier_jellyfin.androidtv.core.data.media.forcedByName
import io.github.glacier_jellyfin.androidtv.core.data.media.hearingImpairedByName
import io.github.glacier_jellyfin.androidtv.core.data.media.regionOf
import io.github.glacier_jellyfin.androidtv.core.data.media.singAlongByName
import java.util.Locale

/**
 * Language tags and stream names as the server reports them, through what the
 * track picker makes of them (Tracks.kt). None of it may throw: the input comes
 * from whatever the file says.
 *
 * The last two bytes choose subtitle or audio and the display language, the
 * rest are newline-separated fields: language, stream name, codec, profile and
 * a word the name should lose.
 */
object TracksFuzzer {

    @JvmStatic
    fun fuzzerTestOneInput(data: FuzzedDataProvider) {
        val subtitle = data.consumeBoolean()
        val display = DISPLAY_LOCALES[data.consumeInt(0, DISPLAY_LOCALES.lastIndex)]
        val fields = data.consumeRemainingAsString().split('\n')
        val language = fields.getOrNull(0)
        val title = fields.getOrNull(1)
        val codec = fields.getOrNull(2)
        val profile = fields.getOrNull(3)
        val word = fields.getOrNull(4)

        checkLanguage(language)
        checkLanguage(word)
        Languages.flag(language, word)
        Languages.name(language, display, word)

        // What MediaStream.toTrack derives from the stream's name.
        val forced = subtitle && forcedByName(title)
        val captions = subtitle && closedCaptionsByName(title)
        val track = Track(
            index = 0,
            language = cantoneseOr(language, title),
            codec = codec,
            channels = fields.size,
            forced = forced,
            hearingImpaired = subtitle && hearingImpairedByName(title),
            closedCaptions = captions,
            singAlong = subtitle && !forced && singAlongByName(title),
            fallbackTitle = title,
            title = title,
            region = regionOf(language, title),
            profile = profile,
            commentary = !subtitle && commentaryByName(title),
            audioDescription = !subtitle && audioDescriptionByName(title),
        )
        describeTrack(track, subtitle, listOf(display))

        val cleaned = cleanTrackTitle(title, listOfNotNull(word))
        check(cleaned == null || (cleaned.isNotEmpty() && cleaned.first() !in TRIMMED && cleaned.last() !in TRIMMED)) {
            "cleanTrackTitle left separators around \"$cleaned\""
        }
    }

    /** Language codes come out as three or two lower case letters, or not at all, and stay what they are. */
    private fun checkLanguage(code: String?) {
        val iso3 = Languages.iso3(code)
        check(iso3 == null || iso3.matches(ISO3)) { "iso3(\"$code\") is \"$iso3\"" }
        check(Languages.iso3(iso3) == iso3) { "iso3 of \"$iso3\" is not \"$iso3\" again" }
        val iso2 = Languages.iso2(code)
        check(iso2 == null || iso2.matches(ISO2)) { "iso2(\"$code\") is \"$iso2\"" }
        val region = Languages.region(code)
        check(region == null || region.isNotEmpty()) { "region(\"$code\") is empty" }
    }

    private val ISO3 = Regex("[a-z]{3}")
    private val ISO2 = Regex("[a-z]{2}")
    private val TRIMMED = setOf(' ', '-', '_', '·', ',', '/', '|', ':', '.')

    // Turkish for its dotless i, Chinese for a script, the root locale without names.
    private val DISPLAY_LOCALES = listOf(
        Locale.ENGLISH,
        Locale.GERMAN,
        Locale.forLanguageTag("es"),
        Locale.forLanguageTag("tr"),
        Locale.forLanguageTag("zh-TW"),
        Locale.ROOT,
    )
}
