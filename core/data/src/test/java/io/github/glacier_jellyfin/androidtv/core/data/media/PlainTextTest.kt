package io.github.glacier_jellyfin.androidtv.core.data.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlainTextTest {

    @Test
    fun `plain text stays as it is`() {
        assertEquals("Born in Tokyo.", plainText("  Born in Tokyo. "))
    }

    @Test
    fun `tags go and paragraphs become line breaks`() {
        val html = """<p><strong>Hobbies:</strong>  Jazz dancing</p> <p><a href="https://twitter.com/x">Twitter</a></p><p>An actress.</p>"""
        assertEquals("Hobbies: Jazz dancing\nTwitter\nAn actress.", plainText(html))
    }

    @Test
    fun `entities are decoded`() {
        assertEquals("Tom & Jerry \"live\" – it's 5 > 3", plainText("Tom &amp; Jerry &quot;live&quot; &ndash; it&#39;s 5 &gt; 3"))
        assertEquals("Café", plainText("Caf&#xE9;"))
    }

    @Test
    fun `unknown entities stay`() {
        assertEquals("a &foo; b", plainText("a &foo; b"))
    }

    @Test
    fun `line breaks and blank lines are tidied`() {
        assertEquals("One\nTwo\n\nThree", plainText("One<br>Two<br/><br />\n\n\nThree"))
    }

    @Test
    fun `nothing readable is null`() {
        assertNull(plainText(null))
        assertNull(plainText("   "))
        assertNull(plainText("<p> </p>"))
    }
}
