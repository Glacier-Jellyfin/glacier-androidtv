package io.github.glacier_jellyfin.androidtv.core.data.media

import java.text.Normalizer
import java.util.Locale

/**
 * The app's own A–Z order. The server sorts by a cleaned name that drops punctuation and
 * symbols ("& And" sorts as "and", "-1" next to "1"), so names that start with one end up
 * anywhere and the A–Z rail does not match the grid. Here everything the rail files under
 * '#' comes first (symbols, other scripts, then numbers), then A to Z. Numbers go by value
 * ("2" before "10"), letters ignore case and accents.
 */
object NameOrder : Comparator<String> {

    override fun compare(a: String, b: String): Int {
        val byKey = compareKeys(key(a), key(b))
        return if (byKey != 0) byKey else a.compareTo(b)
    }

    /**
     * What a title is sorted under, with the leading article of its language left out.
     *
     * A sort name that is not what the server makes of the name always wins: either it dropped the
     * first word on purpose (a tag, "El Dorado" with "dorado"), then the name less that word is
     * returned, or it was set by hand and is returned as it is. [libraryLanguage] (a code of
     * [Articles], null for none) is the language of the library's metadata; null leaves names alone.
     * Otherwise the name is cut after its leading article in the language the title is in:
     * - A translation of [originalTitle] is in [libraryLanguage], and so is a name that
     *   [inLibraryLanguage] says the server made up in it (collections). English articles count
     *   too, as libraries keep many English titles. A first word shared with the original title was
     *   not translated, so such a title goes by its origin instead.
     * - Else the title is in the language of the countries in [productionLocations]
     *   ([OriginLanguage]). That also holds without an original title: an artist's name is never
     *   translated, and an album takes its album artist's countries. A language without articles
     *   (Japanese) leaves the name as it is. English "the" counts too, as titles and bands of every
     *   origin use English names.
     * - When the language is not known, only articles that no language uses as an ordinary first
     *   word are removed ("The", "Der", "Das"), see [unknownLanguage].
     */
    @Suppress("LongParameterList")
    fun sortTitle(
        name: String,
        sortName: String?,
        originalTitle: String?,
        productionLocations: List<String>?,
        libraryLanguage: String?,
        inLibraryLanguage: Boolean = false,
    ): String = sortTitle(
        name,
        sortName,
        originalTitle,
        productionLocations,
        libraryLanguage,
        inLibraryLanguage,
        unknownLanguage,
    )

    /** [sortTitle] with the rule for titles of unknown language given, to test both. */
    @Suppress("LongParameterList")
    internal fun sortTitle(
        name: String,
        sortName: String?,
        originalTitle: String?,
        productionLocations: List<String>?,
        libraryLanguage: String?,
        inLibraryLanguage: Boolean,
        unknown: Articles.UnknownLanguage,
    ): String {
        if (!sortName.isNullOrBlank() && !serverDerived(name, sortName)) {
            // A tag or the admin's words to remove dropped the first word on purpose: keep that.
            return withoutFirstWord(name, sortName) ?: sortName
        }
        val library = Articles.canonical(libraryLanguage) ?: return name
        val english = Articles.rules("en")
        val hasOriginal = !originalTitle.isNullOrBlank()
        val translated = hasOriginal && loose(originalTitle.orEmpty()) != loose(name)
        // A first word both titles share was not translated: it is in the original language.
        val sharedFirstWord = translated && firstWord(name).equals(firstWord(originalTitle.orEmpty()), true)
        if (inLibraryLanguage || (translated && !sharedFirstWord)) {
            return Articles.strip(name, listOf(Articles.rules(library), english))
        }
        val origin = OriginLanguage.of(productionLocations)
        return when {
            // Of English only "the": "A" and "An" start titles in other languages too ("An einem Tag im September").
            origin != null -> Articles.strip(name, listOf(Articles.rules(origin), english.without(setOf("a", "an"))))

            unknown == Articles.UnknownLanguage.Unambiguous -> Articles.strip(name, Articles.unambiguousRules)

            else -> name
        }
    }

    /**
     * The single switch for titles of unknown language: [Articles.UnknownLanguage.Unambiguous] removes
     * the articles that no other language or English uses as an ordinary first word ("The Witcher").
     * [Articles.UnknownLanguage.Nothing] would leave every such title as it is.
     */
    internal val unknownLanguage = Articles.UnknownLanguage.Unambiguous

    /** [name] less its first word (letters only) when that is what [sortName] says; else null. */
    private fun withoutFirstWord(name: String, sortName: String): String? {
        val words = name.trim().split(' ', limit = 2)
        val rest = words.getOrNull(1)?.trim() ?: return null
        return rest.takeIf { words[0].all { it.isLetter() } && loose(sortName) == loose(it) }
    }

    private fun firstWord(s: String): String = s.trim().substringBefore(' ')

    /**
     * Whether the server made [sortName] from [name]: it lower cases, takes out and blanks symbols,
     * pads numbers and removes its words to remove (by default "the", "a", "an") at the start, in the
     * middle and at the end. So [sortName] is derived when it is what the words of [name] make, leaving
     * out only those default words. Anything else was set by hand, or by a tag.
     */
    private fun serverDerived(name: String, sortName: String): Boolean {
        val target = loose(sortName)
        val plain = name.trim().split(Blanks).map(::loose)
        // Each word either stays, or goes when it is an article-like word.
        var reach: Set<Int> = setOf(0)
        for (word in plain) {
            val next = mutableSetOf<Int>()
            for (at in reach) {
                if (target.startsWith(word, at)) next += at + word.length
                if (word in RemovableWords) next += at
            }
            reach = next
            if (reach.isEmpty()) return false
        }
        return target.length in reach
    }

    private val Blanks = Regex("""\s+""")

    /** What the server removes from a name by default. */
    private val RemovableWords = setOf("the", "a", "an")

    /**
     * The form names and sort names are compared in. The server folds accents and special letters
     * ("Lämmer" is "lammer", "großen" is "grossen", "Æon" is "aeon") and pads numbers with zeros,
     * also inside them ("10.000" is "0000010000"). So: lower case, those letters folded, accents
     * off, only letters and digits kept, and no zeros at all. The server also turns other scripts
     * into Latin letters ("ガゼット" is "kasetto"); such sort names are not recognized and count as
     * set by hand, as they always did here.
     */
    private fun loose(s: String): String {
        val out = StringBuilder()
        for (c in Normalizer.normalize(fold(s), Normalizer.Form.NFD)) {
            if (c == '0' || Character.getType(c) == Character.NON_SPACING_MARK.toInt()) continue
            if (c.isLetterOrDigit()) out.append(c)
        }
        return out.toString()
    }

    /** Lower case, with the letters NFD does not take apart written the way the server does. */
    private fun fold(s: String): String {
        val out = StringBuilder(s.length)
        for (c in s.lowercase(Locale.ROOT)) {
            when (c) {
                '\u00df' -> out.append("ss")
                '\u00e6' -> out.append("ae")
                '\u0153' -> out.append("oe")
                '\u00f8' -> out.append('o')
                '\u0111', '\u00f0' -> out.append('d')
                '\u0142' -> out.append('l')
                '\u00fe' -> out.append("th")
                else -> out.append(c)
            }
        }
        return out.toString()
    }

    /**
     * The folded form [compareKeys] works on: no leading blanks, accents taken off, lower
     * case. Worth keeping per name when sorting many of them.
     */
    fun key(name: String): String {
        val plain = Normalizer.normalize(name.trim(), Normalizer.Form.NFD)
        val out = StringBuilder(plain.length)
        for (c in plain) {
            if (Character.getType(c) == Character.NON_SPACING_MARK.toInt()) continue
            // Letters without a base letter of their own, so "Æon Flux" goes under A, not '#'.
            when (val lower = c.lowercaseChar()) {
                'ß' -> out.append("ss")
                'æ' -> out.append("ae")
                'œ' -> out.append("oe")
                'ø' -> out.append('o')
                'đ', 'ð' -> out.append('d')
                'ł' -> out.append('l')
                'þ' -> out.append("th")
                else -> out.append(lower)
            }
        }
        return out.toString()
    }

    /** Compares two [key]s. */
    fun compareKeys(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isAsciiDigit() && cb.isAsciiDigit()) {
                val endA = digitsEnd(a, i)
                val endB = digitsEnd(b, j)
                val byValue = compareNumbers(a.substring(i, endA), b.substring(j, endB))
                if (byValue != 0) return byValue
                i = endA
                j = endB
                continue
            }
            val byRank = rank(ca).compareTo(rank(cb))
            if (byRank != 0) return byRank
            if (ca != cb) return ca.compareTo(cb)
            i++
            j++
        }
        return (a.length - i).compareTo(b.length - j)
    }

    /** Blanks, then symbols and other scripts, then numbers: all under '#'. Then a to z. */
    private fun rank(c: Char): Int = when {
        c.isWhitespace() -> 0
        c.isAsciiDigit() -> 2
        c in 'a'..'z' -> 3
        else -> 1
    }

    private fun Char.isAsciiDigit() = this in '0'..'9'

    private fun digitsEnd(s: String, from: Int): Int {
        var end = from
        while (end < s.length && s[end].isAsciiDigit()) end++
        return end
    }

    /** By value, leading zeros aside; "01" and "1" are equal here and fall back to the plain name. */
    private fun compareNumbers(a: String, b: String): Int {
        val x = a.trimStart('0')
        val y = b.trimStart('0')
        if (x.length != y.length) return x.length.compareTo(y.length)
        return x.compareTo(y)
    }
}
