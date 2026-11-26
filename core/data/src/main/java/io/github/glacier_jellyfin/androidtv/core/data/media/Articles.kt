package io.github.glacier_jellyfin.androidtv.core.data.media

import java.text.Normalizer
import java.util.Locale

/**
 * The leading articles of titles, per language, for the A–Z order ("The Matrix" is filed under M).
 * Pure Kotlin, so a JVM tool can use it as well.
 *
 * A word counts only as the first word of a title when a blank follows it, or as an elided article
 * attached to the next word ("L'Avare", "Un'altra"). "d'" is not an article. Articles that are part
 * of the word itself, like Arabic "ال", Hebrew "ה" or the Maltese "il-" and "l-", are not stripped:
 * there is no blank to cut at, and stripping them would need morphology and would break names. Nor
 * are the articles that come last: Scandinavian definite forms, Romanian "-ul", Bulgarian, Albanian
 * and Icelandic have none in front of the word. Welsh "'r" is a suffix as well, so only "y" and "yr" count.
 */
object Articles {

    /** The query value that follows the metadata language of the Jellyfin server. */
    const val AUTO = "auto"

    /**
     * The articles per language code, lower case. Entries that end with an apostrophe are elided
     * articles, attached to the next word. Add a language by adding a line here.
     */
    private val tables: Map<String, List<String>> = linkedMapOf(
        "en" to listOf("the", "a", "an"),
        "de" to listOf("der", "die", "das", "ein", "eine"),
        "fr" to listOf("le", "la", "les", "l'", "un", "une"),
        "es" to listOf("el", "la", "los", "las", "un", "una"),
        "ca" to listOf("el", "la", "els", "les", "l'", "un", "una"),
        "it" to listOf("il", "lo", "la", "i", "gli", "le", "l'", "un", "uno", "una", "un'"),
        "pt" to listOf("o", "a", "os", "as", "um", "uma"),
        "nl" to listOf("de", "het", "een", "'t"),
        "sv" to listOf("en", "ett"),
        "da" to listOf("en", "et"),
        "no" to listOf("en", "et", "ei"),
        "ro" to listOf("un", "o"),
        "hu" to listOf("a", "az", "egy"),
        "el" to listOf("ο", "η", "το", "οι", "τα", "ένας", "μια", "ένα"),
        "ga" to listOf("an", "na"),
        "af" to listOf("die", "'n"),
        "gl" to listOf("o", "a", "os", "as", "un", "unha"),
        "ast" to listOf("el", "la", "lo", "los", "les", "l'"),
        "oc" to listOf("lo", "la", "los", "las", "l'", "un", "una"),
        // Here d' is an article, unlike everywhere else.
        "lb" to listOf("den", "d'", "de", "déi", "dat", "en", "eng", "e"),
        "cy" to listOf("y", "yr"),
        "gd" to listOf("an", "am", "a'", "na", "nan", "nam"),
        "br" to listOf("an", "al", "ar", "un", "ul", "ur"),
        "eo" to listOf("la"),
        "fy" to listOf("de", "it", "in"),
    )

    /** Other codes for the languages of [tables]: Norwegian Bokmål and Nynorsk. */
    private val aliases = mapOf("nb" to "no", "nn" to "no")

    /**
     * What to do with the first word of a title whose language is not known.
     * [Nothing] leaves every title as it is, [Unambiguous] removes only the [Unambiguous] articles.
     */
    enum class UnknownLanguage { Nothing, Unambiguous }

    /**
     * Articles that cannot be an ordinary first word in another supported language or in English,
     * and so are safe to remove from a title of unknown language. Greek script is unambiguous too.
     * Deliberately not in it, as they are English words, places, names or bands, or words of another
     * language: a, an, die, i, o, la, le, de, en, et, un, el, lo, les, los, las, den, dat, as, os,
     * um, it, in, e, y, na, am, al, ar ("Die Hard", "I Am Legend", "Los Angeles", "Las Vegas",
     * "El Camino", "Les Paul", German "um").
     */
    val Unambiguous: Set<String> = setOf(
        "the", "der", "das", "ein", "eine", "het", "een", "il", "gli", "une", "una", "uma", "unha", "els", "ett",
        "egy", "ο", "η", "το", "οι", "τα", "ένας",
        "μια", "ένα",
    )

    /** The [Unambiguous] articles, as the rules of every language that has some. */
    val unambiguousRules: List<Rules> by lazy { tables.keys.map { rules(it, Unambiguous) } }

    /** The language codes with articles, in table order. */
    val languages: List<String> = tables.keys.toList()

    /** The table language for [code] ("de", "de-AT", "nb"), or null when it has no articles to strip. */
    fun canonical(code: String?): String? {
        val base = code?.lowercase(Locale.ROOT)?.substringBefore('-')?.substringBefore('_') ?: return null
        val language = aliases[base] ?: base
        return language.takeIf { it in tables }
    }

    /**
     * The profile's setting as a query carries it: null when [ignore] is off, else [AUTO], the language
     * still to be found out (see [resolveAuto]).
     */
    fun requested(ignore: Boolean): String? = if (ignore) AUTO else null

    /**
     * What [AUTO] means: the table language of the first of [libraryLanguage] (the metadata language of
     * the Jellyfin library), [serverLanguage] (the server-wide one) and [uiLanguage] (the interface)
     * that has articles; English when none has. Region codes count by their language ("pt-BR").
     */
    fun resolveAuto(
        libraryLanguage: String?,
        serverLanguage: String?,
        uiLanguage: String = Locale.getDefault().language,
    ): String = canonical(libraryLanguage) ?: canonical(serverLanguage) ?: canonical(uiLanguage) ?: "en"

    /** The articles of one language, ready to match. */
    class Rules internal constructor(
        internal val words: Set<String>,
        internal val elisions: Set<String>,
        internal val foldAccents: Boolean,
    ) {
        /** These rules without the words in [skipped]. */
        fun without(skipped: Set<String>) = Rules(words - skipped, elisions - skipped, foldAccents)
    }

    /** The rules of [language], or only its articles that are in [only]; none when it has no table. */
    fun rules(language: String?, only: Set<String>? = null): Rules {
        val entries = tables[canonical(language)].orEmpty().filter { only == null || it in only }
        val greek = canonical(language) == "el"
        fun fold(word: String) = if (greek) foldAccents(word) else word
        return Rules(
            words = entries.filterNot { it.endsWith('\'') }.mapTo(HashSet()) { fold(it) },
            elisions = entries.filter { it.endsWith('\'') }.mapTo(HashSet()) { it.dropLast(1) },
            foldAccents = greek,
        )
    }

    /** [name] without its leading article in [language], or as it is. */
    fun strip(name: String, language: String?): String = strip(name, listOf(rules(language)))

    /**
     * [name] without a leading article of any of [rules], and without the blanks after it. A name
     * stays as it is when nothing with a letter or digit would remain ("The", "A", "Die!").
     */
    fun strip(name: String, rules: List<Rules>): String {
        val start = name.indexOfFirst { !it.isWhitespace() }
        if (start < 0) return name
        var end = start
        while (end < name.length && !name[end].isWhitespace()) end++
        val cut = rules.firstNotNullOfOrNull { cutAfterArticle(name, start, end, it) } ?: return name
        val rest = name.substring(cut).trimStart()
        return if (rest.any { it.isLetterOrDigit() }) rest else name
    }

    /** Where the article in [name] from [start] to [end] (its first word) ends under [rule]; null when it is none. */
    private fun cutAfterArticle(
        name: String,
        start: Int,
        end: Int,
        rule: Rules,
    ): Int? {
        val token = name.substring(start, end)
        val apostrophe = token.indexOfFirst { it == '\'' || it == '’' }
        // "a'" of Scottish Gaelic is an article on its own: "a' Chailleach".
        val elided = apostrophe > 0 &&
            (apostrophe < token.length - 1 || end < name.length) &&
            token.substring(0, apostrophe).lowercase(Locale.ROOT) in rule.elisions
        if (elided) return start + apostrophe + 1
        val plain = token.replace('’', '\'')
        val key = if (rule.foldAccents) foldAccents(plain) else plain.lowercase(Locale.ROOT)
        return end.takeIf { end < name.length && key in rule.words }
    }

    /** Lower case without accents, so "μία" is "μια". */
    private fun foldAccents(word: String): String {
        val out = StringBuilder(word.length)
        for (c in Normalizer.normalize(word.lowercase(Locale.ROOT), Normalizer.Form.NFD)) {
            if (Character.getType(c) != Character.NON_SPACING_MARK.toInt()) out.append(c)
        }
        return out.toString()
    }
}
