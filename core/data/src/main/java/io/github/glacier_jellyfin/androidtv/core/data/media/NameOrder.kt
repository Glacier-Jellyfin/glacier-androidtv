package io.github.glacier_jellyfin.androidtv.core.data.media

import java.text.Normalizer

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
     * What a title is sorted under. The server's sort name is its name lowered, with some
     * symbols taken out ("&" becomes empty), others turned into blanks ("...Dots" becomes
     * "   dots"), numbers padded with zeros and a leading "The", "A" or "An" dropped. The app
     * sorts the name itself, less only that article. A sort name set by hand (it says
     * something else than the name) is kept.
     */
    fun title(name: String, sortName: String?): String {
        if (sortName.isNullOrBlank()) return name
        val plain = loose(sortName)
        if (plain == loose(name)) return name
        val words = name.trim().split(' ', limit = 2)
        if (words.size == 2 && words[0].all { it.isLetter() } && plain == loose(words[1])) return words[1].trim()
        return sortName
    }

    /** Only letters and numbers, lower case, numbers without leading zeros. */
    private fun loose(s: String): String {
        val out = StringBuilder()
        var inNumber = false
        for (c in s.lowercase()) {
            when {
                c.isDigit() -> {
                    if (c == '0' && !inNumber) continue
                    inNumber = true
                    out.append(c)
                }
                c.isLetter() -> {
                    inNumber = false
                    out.append(c)
                }
                else -> inNumber = false
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
            when (c) {
                'ß' -> out.append("ss")
                else -> out.append(c.lowercaseChar())
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
