package io.github.glacier_jellyfin.detekt

import dev.detekt.api.Config
import dev.detekt.test.lint
import org.junit.Assert.assertEquals
import org.junit.Test

class RulesTest {
    private fun whenFindings(code: String) = WhenConditionPerLine(Config.empty).lint(code, compile = false)

    private fun danglingFindings(code: String) = DanglingShortArguments(Config.empty).lint(code, compile = false)

    @Test
    fun `several conditions sharing lines are reported`() {
        val code = """
            fun f(x: Int) = when (x) {
                1, 2,
                3,
                -> "a"
                else -> "b"
            }
        """.trimIndent()
        assertEquals(1, whenFindings(code).size)
    }

    @Test
    fun `one condition per line passes`() {
        val code = """
            fun f(x: Int) = when (x) {
                1,
                2,
                3,
                -> "a"
                else -> "b"
            }
        """.trimIndent()
        assertEquals(0, whenFindings(code).size)
    }

    @Test
    fun `conditions on one line pass`() {
        val code = """
            fun f(x: Int) = when (x) {
                1, 2, 3 -> "a"
                else -> "b"
            }
        """.trimIndent()
        assertEquals(0, whenFindings(code).size)
    }

    @Test
    fun `a single condition spanning lines passes`() {
        val code = """
            fun f(x: String) = when (x) {
                "a" +
                    "b" -> 1
                else -> 2
            }
        """.trimIndent()
        assertEquals(0, whenFindings(code).size)
    }

    @Test
    fun `a dangling single argument in front of a dot is reported`() {
        val code = """
            fun f(a: String, b: String) = a.trimEnd(
                '/',
            ).equals(b, ignoreCase = true)
        """.trimIndent()
        assertEquals(1, danglingFindings(code).size)
    }

    @Test
    fun `a dangling single argument in front of a safe call is reported`() {
        val code = """
            fun f(a: List<Int>?) = a?.getOrNull(
                1,
            )?.plus(1)
        """.trimIndent()
        assertEquals(1, danglingFindings(code).size)
    }

    @Test
    fun `a single line call passes`() {
        val code = """
            fun f(a: String, b: String) = a.trimEnd('/').equals(b, ignoreCase = true)
        """.trimIndent()
        assertEquals(0, danglingFindings(code).size)
    }

    @Test
    fun `a wrapped call without a trailing chain passes`() {
        val code = """
            fun f(a: String) = a.trimEnd(
                '/',
            )
        """.trimIndent()
        assertEquals(0, danglingFindings(code).size)
    }

    @Test
    fun `two arguments in front of a dot are reported`() {
        val code = """
            fun f(a: String) = a.replace(
                "a",
                "b",
            ).length
        """.trimIndent()
        assertEquals(1, danglingFindings(code).size)
    }

    @Test
    fun `three arguments in front of a safe call are reported`() {
        val code = """
            fun f(a: String?) = a?.replace(
                "a",
                "b",
                ignoreCase = true,
            )?.length
        """.trimIndent()
        assertEquals(1, danglingFindings(code).size)
    }

    @Test
    fun `four arguments pass`() {
        val code = """
            fun f(a: Int) = listOf(
                a,
                a + 1,
                a + 2,
                a + 3,
            ).size
        """.trimIndent()
        assertEquals(0, danglingFindings(code).size)
    }

    @Test
    fun `a long argument passes`() {
        val code = """
            fun f(a: String) = a.replace(
                "an argument that is longer than the forty characters a short one may have",
                "b",
            ).length
        """.trimIndent()
        assertEquals(0, danglingFindings(code).size)
    }

    @Test
    fun `a multi-line argument passes`() {
        val code = """
            fun f(a: List<Int>) = a.map(
                listOf(
                    1,
                    2,
                ),
            ).size
        """.trimIndent()
        assertEquals(0, danglingFindings(code).size)
    }

    @Test
    fun `a lambda argument passes`() {
        val code = """
            fun f(a: List<Int>) = a.map(
                { it + 1 },
            ).size
        """.trimIndent()
        assertEquals(0, danglingFindings(code).size)
    }
}
