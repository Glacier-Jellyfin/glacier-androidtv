package io.github.glacier_jellyfin.androidtv.core.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArticlesTest {

    private fun stripped(name: String, language: String) = Articles.strip(name, language)

    private fun sorted(
        name: String,
        library: String?,
        original: String? = null,
        places: List<String>? = null,
        sortName: String? = null,
    ) = NameOrder.sortTitle(name, sortName, original, places, library)

    @Test
    fun `every table strips its articles`() {
        val cases = mapOf(
            "en" to listOf("The X", "A X", "An X"),
            "de" to listOf("Der X", "Die X", "Das X", "Ein X", "Eine X"),
            "fr" to listOf("Le X", "La X", "Les X", "L'X", "L’X", "Un X", "Une X"),
            "es" to listOf("El X", "La X", "Los X", "Las X", "Un X", "Una X"),
            "ca" to listOf("El X", "La X", "Els X", "Les X", "L'X", "Un X", "Una X"),
            "it" to listOf("Il X", "Lo X", "La X", "I X", "Gli X", "Le X", "L'X", "Un X", "Uno X", "Una X", "Un'X"),
            "pt" to listOf("O X", "A X", "Os X", "As X", "Um X", "Uma X"),
            "nl" to listOf("De X", "Het X", "Een X", "'t X", "’t X"),
            "sv" to listOf("En X", "Ett X"),
            "da" to listOf("En X", "Et X"),
            "no" to listOf("En X", "Et X", "Ei X"),
            "ro" to listOf("Un X", "O X"),
            "hu" to listOf("A X", "Az X", "Egy X"),
            "el" to listOf("Ο X", "Η X", "Το X", "Οι X", "Τα X", "Ένας X", "Μια X", "Μία X", "Ένα X"),
            "ga" to listOf("An X", "Na X"),
            "af" to listOf("Die X", "'n X", "’n X"),
            "gl" to listOf("O X", "A X", "Os X", "As X", "Un X", "Unha X"),
            "ast" to listOf("El X", "La X", "Lo X", "Los X", "Les X", "L'X"),
            "oc" to listOf("Lo X", "La X", "Los X", "Las X", "L'X", "Un X", "Una X"),
            "lb" to listOf("Den X", "D'X", "De X", "Déi X", "Dat X", "En X", "Eng X", "E X"),
            "cy" to listOf("Y X", "Yr X"),
            "gd" to listOf("An X", "Am X", "A' X", "Na X", "Nan X", "Nam X"),
            "br" to listOf("An X", "Al X", "Ar X", "Un X", "Ul X", "Ur X"),
            "eo" to listOf("La X"),
            "fy" to listOf("De X", "It X", "In X"),
        )
        assertEquals(Articles.languages.toSet(), cases.keys)
        for ((language, titles) in cases) {
            for (title in titles) assertEquals("$language: $title", "X", stripped(title, language))
        }
    }

    @Test
    fun `matching ignores case and keeps the rest`() {
        assertEquals("matrix", stripped("THE   matrix", "en"))
        assertEquals("Untergang", stripped("  Der Untergang", "de"))
    }

    @Test
    fun `d is an article only in Luxembourgish`() {
        assertEquals("D'Artagnan", stripped("D'Artagnan", "fr"))
        assertEquals("D'Artagnan", stripped("D'Artagnan", "it"))
        assertEquals("Artagnan", stripped("D'Artagnan", "lb"))
    }

    @Test
    fun `nothing is stripped when nothing would remain`() {
        assertEquals("The", stripped("The", "en"))
        assertEquals("A", stripped("A", "en"))
        assertEquals("Die!", stripped("Die!", "de"))
        assertEquals("The !", stripped("The !", "en"))
        assertEquals("L'", stripped("L'", "fr"))
    }

    @Test
    fun `articles attached to the word are not stripped`() {
        val arabic = "الكتاب"
        assertEquals(arabic, stripped(arabic, "de"))
        assertEquals("Thefts", stripped("Thefts", "en"))
        assertEquals("Lavare", stripped("Lavare", "fr"))
    }

    @Test
    fun `automatic follows library, server, interface, then English`() {
        assertEquals("fr", Articles.resolveAuto("fr", "de", "es"))
        assertEquals("de", Articles.resolveAuto(null, "de", "es"))
        assertEquals("es", Articles.resolveAuto(null, null, "es"))
        assertEquals("en", Articles.resolveAuto(null, null, "ja"))
        assertEquals("en", Articles.resolveAuto("", "", ""))
    }

    @Test
    fun `automatic skips languages without articles and maps region codes`() {
        assertEquals("de", Articles.resolveAuto("ja", "de-DE", "es"))
        assertEquals("pt", Articles.resolveAuto("pt-BR", "de", "es"))
        assertEquals("no", Articles.resolveAuto("nb-NO", null, "es"))
        assertEquals("de", Articles.resolveAuto("", "de", "es"))
        assertEquals("es", Articles.resolveAuto("ja", "zh", "es"))
    }

    @Test
    fun `requested is automatic or nothing`() {
        assertNull(Articles.requested(false))
        assertEquals("auto", Articles.requested(true))
    }

    private fun unknown(
        name: String,
        rule: Articles.UnknownLanguage,
        library: String = "de",
    ) = NameOrder.sortTitle(name, null, null, null, library, false, rule)

    @Test
    fun `a title in an unknown language loses only unambiguous articles by default`() {
        assertEquals(Articles.UnknownLanguage.Unambiguous, NameOrder.unknownLanguage)
        assertEquals("Witcher", sorted("The Witcher", "de", "The Witcher"))
        assertEquals("A Quiet Place", sorted("A Quiet Place", "de", "A Quiet Place"))
        assertEquals("An klaren Tagen", sorted("An klaren Tagen", "de"))
        assertEquals("Die Anstalt", sorted("Die Anstalt", "de", "Die Anstalt"))
    }

    @Test
    fun `with the other switch value a title in an unknown language keeps its articles`() {
        for (title in listOf("The Office", "An klaren Tagen", "A Quiet Place", "Die Anstalt", "Das Boot")) {
            assertEquals(title, unknown(title, Articles.UnknownLanguage.Nothing))
        }
    }

    @Test
    fun `unambiguous articles are removed from a title in an unknown language`() {
        val rule = Articles.UnknownLanguage.Unambiguous
        assertEquals("Office", unknown("The Office", rule))
        assertEquals("An klaren Tagen", unknown("An klaren Tagen", rule))
        assertEquals("A Quiet Place", unknown("A Quiet Place", rule))
        assertEquals("Die Anstalt", unknown("Die Anstalt", rule))
        assertEquals("Boot", unknown("Das Boot", rule))
        assertEquals("Postino", unknown("Il Postino", rule, "it"))
        assertEquals("I Am Legend", unknown("I Am Legend", rule, "it"))
        assertEquals("Los Angeles", unknown("Los Angeles", rule, "es"))
    }

    @Test
    fun `an album takes the language of its artist`() {
        assertEquals("Sterne", sorted("Die Sterne", "de", null, listOf("Germany", "Hamburg, Germany")))
        assertEquals("Dark Side of the Moon", sorted("The Dark Side of the Moon", "de", null, listOf("United Kingdom")))
    }

    @Test
    fun `the original language decides`() {
        val us = listOf("United States of America")
        assertEquals("Boot", sorted("Das Boot", "en", "Das Boot", listOf("Germany")))
        assertEquals("Boat", sorted("The Boat", "en", "Das Boot", listOf("Germany")))
        assertEquals("Die Hard", sorted("Die Hard", "de", "Die Hard", us))
        // A translation uses the library language.
        assertEquals("Dunkle Ritter", sorted("Der Dunkle Ritter", "de", "The Dark Knight", us))
        // A first word both titles share was not translated: it is in the original language.
        assertEquals(
            "Die Hard: Jetzt erst recht",
            sorted("Die Hard: Jetzt erst recht", "de", "Die Hard: With a Vengeance", us),
        )
        // Japanese has no articles to strip, English "the" counts everywhere.
        assertEquals("Der Ring", sorted("Der Ring", "en", "Der Ring", listOf("Japan")))
        assertEquals("Ring", sorted("The Ring", "en", "The Ring", listOf("Japan")))
    }

    @Test
    fun `several countries leave one language or none`() {
        // Switzerland alone and Germany with France leave several: unknown, so the ambiguous "Die" stays.
        assertEquals("Die Welle", sorted("Die Welle", "de", "Die Welle", listOf("Switzerland")))
        assertEquals("Die Welle", sorted("Die Welle", "de", "Die Welle", listOf("Germany", "France")))
        // Canada and the USA leave English, Belgium and France leave French, Switzerland and Germany German.
        assertEquals("Die Hard", sorted("Die Hard", "de", "Die Hard", listOf("Canada", "United States of America")))
        assertEquals("Amour", sorted("L'Amour", "de", "L'Amour", listOf("Belgium", "France")))
        assertEquals("Welle", sorted("Die Welle", "en", "Die Welle", listOf("Switzerland", "Germany")))
    }

    @Test
    fun `artists have no original title and go by their places`() {
        assertEquals("Ärzte", sorted("Die Ärzte", "en", null, listOf("Germany")))
        assertEquals("Beatles", sorted("The Beatles", "de", null, listOf("Liverpool, England")))
        assertEquals("Beatles", sorted("The Beatles", "de", null, listOf("California", "United Kingdom")))
        assertEquals("Die Band", sorted("Die Band", "de", null, listOf("California")))
    }

    @Test
    fun `off keeps the name and a sort name set by hand still wins`() {
        assertEquals("The Matrix", sorted("The Matrix", null, "The Matrix", listOf("USA")))
        assertEquals("Star Wars 4", sorted("A New Hope", null, sortName = "Star Wars 4"))
    }

    @Test
    fun `english the counts in every case`() {
        assertEquals("Hives", sorted("The Hives", "de", "The Hives", listOf("Sweden")))
        assertEquals("Bates", sorted("The Bates", "de", null, listOf("Germany")))
        assertEquals("Stranger", sorted("The Stranger", "de", "The Stranger", listOf("Chile")))
        // English "a" and "an" only where the countries do not name another language.
        assertEquals(
            "An einem Tag im September",
            sorted("An einem Tag im September", "en", "An einem Tag im September", listOf("Germany")),
        )
        assertEquals("Quiet Place", sorted("A Quiet Place", "de", "A Quiet Place", listOf("United States of America")))
        assertEquals("Die Hard", sorted("Die Hard", "de"))
        assertEquals("I Am Legend", sorted("I Am Legend", "en", "I Am Legend", listOf("USA")))
    }

    @Test
    fun `a translation uses the library language and english`() {
        val us = listOf("United States of America")
        val first = sorted("The First Avenger: Civil War", "de", "Captain America: Civil War", us)
        assertEquals("First Avenger: Civil War", first)
        assertEquals("Poderoso Chefão", sorted("O Poderoso Chefão", "pt", "The Godfather", us))
    }

    @Test
    fun `a collection is named in the library language`() {
        val name = "Die Bestimmung Filmreihe"
        val sorted = NameOrder.sortTitle(name, null, null, null, "de", inLibraryLanguage = true)
        assertEquals("Bestimmung Filmreihe", sorted)
        assertEquals(name, NameOrder.sortTitle(name, null, null, null, "de"))
    }

    @Test
    fun `a sort name that dropped the first word on purpose is honored`() {
        assertEquals("Dorado", sorted("El Dorado", "de", sortName = "dorado"))
        assertEquals("Isla Bonita", sorted("La Isla Bonita", "de", sortName = "isla bonita"))
        assertEquals("Untergang", sorted("Der Untergang", "de", sortName = "untergang"))
        assertEquals("Dorado", sorted("El Dorado", null, sortName = "dorado"))
        assertEquals("Die Hard", sorted("Die Hard", "de", sortName = "die hard"))
    }
}
