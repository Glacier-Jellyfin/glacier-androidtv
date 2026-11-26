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
        assertEquals(
            listOf("2 Two", "10 Ten", "Song 2", "Song 10"),
            listOf("Song 10", "10 Ten", "Song 2", "2 Two").sortedWith(NameOrder),
        )
    }

    @Test
    fun `letters ignore case and accents`() {
        assertEquals(
            listOf("Apfel", "Äpfel", "apple", "Éclair", "eclipse"),
            listOf("eclipse", "apple", "Äpfel", "Éclair", "Apfel").sortedWith(NameOrder),
        )
        assertTrue(NameOrder.compare("Straße", "Strasse") != 0)
        assertTrue(NameOrder.compare("Straße", "Strassf") < 0)
        assertEquals(listOf("Adam", "Æon Flux", "Afro"), listOf("Afro", "Æon Flux", "Adam").sortedWith(NameOrder))
        assertEquals('A', railLetter("Æon Flux"))
        assertEquals('O', railLetter("Øresund"))
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

    private fun title(
        name: String,
        sortName: String?,
        language: String? = "en",
    ) = NameOrder.sortTitle(name, sortName, null, listOf("USA"), language)

    @Test
    fun `titles go by their name, less a leading article`() {
        // Sort names as the server makes them. Replaces NameOrder.title: the language decides now.
        assertEquals("&", title("&", ""))
        assertEquals("...Dots Album", title("...Dots Album", "   dots album"))
        assertEquals("!Bang!", title("!Bang!", "!bang!"))
        assertEquals("Glacier Versions (2026)", title("Glacier Versions (2026)", "glacier versions (0000002026)"))
        assertEquals("Test", title("The Test", "test"))
        assertEquals("Movie", title("Movie", null))
    }

    @Test
    fun `a sort name set by hand is kept`() {
        assertEquals("Star Wars 4", title("A New Hope", "Star Wars 4"))
        assertEquals("Star Wars 4", title("A New Hope", "Star Wars 4", null))
    }

    @Test
    fun `words the server took out of the middle or end do not make a sort name hand-set`() {
        assertEquals("Face the Music", title("Face the Music", "face music"))
        assertEquals("Lord of the Rings", title("The Lord of the Rings", "lord of rings"))
        assertEquals(
            "10 Dinge, die ich an Dir hasse",
            title("10 Dinge, die ich an Dir hasse", "0000000010 dinge die ich dir hasse"),
        )
        assertEquals("Hello", title("Hello", "hello", null))
    }

    @Test
    fun `off leaves the name as it is`() {
        assertEquals("The Matrix", title("The Matrix", "matrix", null))
    }

    @Test
    fun `sort names the server folds and pads are still derived`() {
        assertEquals(
            "Schweigen der Lämmer",
            NameOrder.sortTitle(
                "Das Schweigen der Lämmer",
                "das schweigen der lammer",
                "Das Schweigen der Lämmer",
                listOf("Germany"),
                "en",
            ),
        )
        assertEquals("Æon Flux", title("Æon Flux", "aeon flux", null))
        assertEquals("10.000 B.C.", title("10.000 B.C.", "0000010000 bc", null))
        val long = "101 Dalmatiner - Teil 2: Auf kleinen Pfoten zum großen Star!"
        val sortName = "0000000101 dalmatiner teil 0000000002 auf kleinen pfoten zum grossen star"
        assertEquals(long, title(long, sortName, null))
        assertEquals("Daði Freyr", title("Daði Freyr", "dadi freyr", null))
        assertEquals("tom und jerry", title("Tom and Jerry", "tom und jerry", null))
    }
}
