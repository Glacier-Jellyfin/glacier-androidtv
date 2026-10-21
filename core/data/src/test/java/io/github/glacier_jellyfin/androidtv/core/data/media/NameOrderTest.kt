package io.github.glacier_jellyfin.androidtv.core.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NameOrderTest {

    @Test
    fun `symbols first, then numbers, then letters`() {
        val names = listOf("Zebra", "1 One", "& And", "Apple", "0 Zero", "-1", "§1", "* Star", "...Dots", "♀", "banana")
        assertEquals(
            listOf("& And", "* Star", "-1", "...Dots", "§1", "♀", "0 Zero", "1 One", "Apple", "banana", "Zebra"),
            names.sortedWith(NameOrder),
        )
    }

    @Test
    fun `numbers go by value`() {
        assertEquals(listOf("2 Two", "10 Ten", "Song 2", "Song 10"), listOf("Song 10", "10 Ten", "Song 2", "2 Two").sortedWith(NameOrder))
    }

    @Test
    fun `letters ignore case and accents`() {
        assertEquals(listOf("Apfel", "Äpfel", "apple", "Éclair", "eclipse"), listOf("eclipse", "apple", "Äpfel", "Éclair", "Apfel").sortedWith(NameOrder))
        assertTrue(NameOrder.compare("Straße", "Strasse") != 0)
        assertTrue(NameOrder.compare("Straße", "Strassf") < 0)
    }

    @Test
    fun `leading blanks are ignored`() {
        assertEquals(listOf("  Abc", "Abd"), listOf("Abd", "  Abc").sortedWith(NameOrder))
    }

    @Test
    fun `everything filed under the hash comes before A`() {
        val names = listOf("Zoo", "Кино", "東京", "ß", "Ångström", "#1", "42")
        val sorted = names.sortedWith(NameOrder)
        val firstLetter = sorted.indexOfFirst { railLetter(it) != '#' }
        assertTrue(sorted.drop(firstLetter).all { railLetter(it) != '#' })
        assertEquals(listOf('A', 'S', 'Z'), sorted.drop(firstLetter).map(::railLetter))
    }

    @Test
    fun `titles go by their name, less a leading article`() {
        // Sort names as the server makes them.
        assertEquals("&", NameOrder.title("&", ""))
        assertEquals("...Dots Album", NameOrder.title("...Dots Album", "   dots album"))
        assertEquals("!Bang!", NameOrder.title("!Bang!", "!bang!"))
        assertEquals("Glacier Versions (2026)", NameOrder.title("Glacier Versions (2026)", "glacier versions (0000002026)"))
        assertEquals("Test", NameOrder.title("The Test", "test"))
        assertEquals("Movie", NameOrder.title("Movie", null))
    }

    @Test
    fun `a sort name set by hand is kept`() {
        assertEquals("Star Wars 4", NameOrder.title("A New Hope", "Star Wars 4"))
    }
}
