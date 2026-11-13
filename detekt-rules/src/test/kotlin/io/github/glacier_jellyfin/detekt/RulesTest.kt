package io.github.glacier_jellyfin.detekt

import dev.detekt.api.Config
import dev.detekt.test.lint
import org.junit.Assert.assertEquals
import org.junit.Test

class RulesTest {
    private fun whenFindings(code: String) = WhenConditionPerLine(Config.empty).lint(code, compile = false)

    private fun operandFindings(code: String) = DanglingShortOperand(Config.empty).lint(code, compile = false)

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
    fun `a wrapped call at the end of a chain is reported`() {
        val code = """
            fun f(a: String) = a.trimEnd(
                '/',
            )
        """.trimIndent()
        assertEquals(1, danglingFindings(code).size)
    }

    @Test
    fun `a standalone call with positional arguments that fits one line is reported`() {
        val code = """
            fun f(a: Int) = maxOf(
                a,
                1,
            )
        """.trimIndent()
        assertEquals(1, danglingFindings(code).size)
    }

    @Test
    fun `a standalone call with named arguments passes`() {
        val code = """
            fun g(a: Int, b: Int) = a + b

            fun f(a: Int) = g(
                a = a,
                b = 1,
            )
        """.trimIndent()
        assertEquals(0, danglingFindings(code).size)
    }

    @Test
    fun `a standalone call that does not fit one line passes`() {
        val code = """
            fun g(a: String, b: String, c: String) = a + b + c

            val aVeryLongPropertyNameThatTakesUpMostOfTheLineSoTheCallCannotFitOnItAnymoreEvenWithoutAnythingElse = g(
                "first argument",
                "second argument",
                "third argument",
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
                    3,
                    4,
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

    @Test
    fun `a short argument list in front of an operator is reported`() {
        val code = """
            fun f(a: String, b: Int) = a.indexOf(
                "x",
                1,
            ) + b
        """.trimIndent()
        assertEquals(1, danglingFindings(code).size)
    }

    @Test
    fun `a short argument list in front of an elvis is reported`() {
        val code = """
            fun f(a: Map<String, Int>) = a.getOrDefault(
                "x",
                1,
            ) ?: 2
        """.trimIndent()
        assertEquals(1, danglingFindings(code).size)
    }

    @Test
    fun `a short argument list in front of a trailing lambda is reported`() {
        val code = """
            fun f(a: List<Int>) = a.fold(
                0,
            ) { sum, it -> sum + it }
        """.trimIndent()
        assertEquals(1, danglingFindings(code).size)
    }

    @Test
    fun `four arguments in front of a trailing lambda pass`() {
        val code = """
            fun g(a: Int, b: Int, c: Int, d: Int, block: () -> Unit) = block()

            fun f() = g(
                1,
                2,
                3,
                4,
            ) { }
        """.trimIndent()
        assertEquals(0, danglingFindings(code).size)
    }

    @Test
    fun `a wrapped call with named arguments followed by a comma passes`() {
        val code = """
            fun g(a: Int) = a

            fun f() = listOf(
                g(
                    a = 1,
                ),
                2,
            )
        """.trimIndent()
        assertEquals(0, danglingFindings(code).size)
    }

    @Test
    fun `a short right operand on its own line is reported`() {
        val code = """
            fun f(kind: String) = if (kind ==
                "Series"
            ) {
                1
            } else {
                2
            }
        """.trimIndent()
        assertEquals(1, operandFindings(code).size)
    }

    @Test
    fun `a short right operand of a less-than on its own line is reported`() {
        val code = """
            fun f(index: Int) = index <
                10
        """.trimIndent()
        assertEquals(1, operandFindings(code).size)
    }

    @Test
    fun `a comparison on one line passes`() {
        val code = """
            fun f(kind: String) = if (kind == "Series") 1 else 2
        """.trimIndent()
        assertEquals(0, operandFindings(code).size)
    }

    @Test
    fun `a long right operand passes`() {
        val code = """
            fun f(kind: String) = if (kind ==
                "a literal that is much longer than the forty characters a short operand may have"
            ) {
                1
            } else {
                2
            }
        """.trimIndent()
        assertEquals(0, operandFindings(code).size)
    }

    @Test
    fun `a logical operator is not a comparison and passes`() {
        val code = """
            fun f(a: Boolean, b: Boolean) = if (a &&
                b
            ) {
                1
            } else {
                2
            }
        """.trimIndent()
        assertEquals(0, operandFindings(code).size)
    }

    @Test
    fun `an operand that would not fit the joined line passes`() {
        val code = """
            fun f(averyveryveryveryveryveryveryveryveryveryveryveryveryveryverylongname: String) =
                averyveryveryveryveryveryveryveryveryveryveryveryveryveryverylongname + averyveryveryveryveryveryveryveryveryveryveryvery ==
                    "short"
        """.trimIndent()
        assertEquals(0, operandFindings(code).size)
    }

    @Test
    fun `a trailing lambda whose call cannot fit one line passes`() {
        val code = """
            fun g(a: String, b: String, c: String, block: () -> Unit) = block()

            fun f() = g(
                "an argument of about thirty-five",
                "another argument of about thirty",
                "and one more of about thirty five",
            ) { }
        """.trimIndent()
        assertEquals(0, danglingFindings(code).size)
    }

    @Test
    fun `an arrow after the closing parenthesis passes`() {
        val code = """
            fun f(a: Int) = when (a) {
                in listOf(
                    1,
                    2,
                ) -> 1
                else -> 2
            }
        """.trimIndent()
        assertEquals(0, danglingFindings(code).size)
    }

    @Test
    fun `an arrow after a negated call in a when branch passes`() {
        val code = """
            fun f(a: Int, b: Int, c: Int) = when {
                a > 1 -> 0

                !g(
                    a,
                    b,
                    c,
                ) -> 1

                else -> 2
            }

            fun g(a: Int, b: Int, c: Int) = a + b + c > 0
        """.trimIndent()
        assertEquals(0, danglingFindings(code).size)
    }

    @Test
    fun `an arrow before a template string passes`() {
        val code = """
            fun f(lowering: Boolean, atMs: Long, now: Long): String? {
                val problem = when {
                    lowering && (atMs ?: 0) >= (now ?: 1) -> "a"

                    !HandoverPlanner.stillWorks(
                        lowering,
                        atMs,
                        now,
                    ) -> "the stream took too long to open (${'$'}{(atMs - now) / 1000} s of the lead left)"

                    else -> null
                }
                return problem
            }
        """.trimIndent()
        assertEquals(0, danglingFindings(code).size)
    }

    @Test
    fun `a lambda whose parameters push the line over the limit passes`() {
        val code = """
            fun g(a: String, b: String, block: (Int, Int, Int) -> Unit) = block(1, 2, 3)

            fun f() = g(
                "an argument of about thirty-five c",
                "another argument of about thirty",
            ) { firstParameter, secondParameter, thirdParameter -> println(firstParameter + secondParameter) }
        """.trimIndent()
        assertEquals(0, danglingFindings(code).size)
    }

    @Test
    fun `a chain end behind a parenthesized receiver is reported even when it does not fit`() {
        val code = """
            fun f(coverHeight: Int, tracks: List<Int>, rowHeight: Int, rowGap: Int, moreSpaceToWaste: Int) =
                (coverHeight - tracks.size * (rowHeight + rowGap) - moreSpaceToWaste - coverHeight - coverHeight).coerceAtLeast(
                    0,
                )
        """.trimIndent()
        assertEquals(1, danglingFindings(code).size)
    }

    @Test
    fun `a chain end behind a plain receiver that does not fit passes`() {
        val code = """
            object Receiver {
                fun call(a: String, b: String, c: String) = a + b + c
            }

            val aVeryLongPropertyNameThatTakesUpMostOfTheLineSoTheCallCannotFitOnItAnymoreEvenWithoutAnythingElse = Receiver.call(
                "first argument",
                "second argument",
                "third argument",
            )
        """.trimIndent()
        assertEquals(0, danglingFindings(code).size)
    }

    @Test
    fun `an enumeration in a collection builder passes`() {
        val code = """
            val numbers = listOf(
                1,
                2,
                3,
            )
        """.trimIndent()
        assertEquals(0, danglingFindings(code).size)
    }
}
